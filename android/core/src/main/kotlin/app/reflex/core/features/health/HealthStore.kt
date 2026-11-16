package app.reflex.core.features.health

import app.reflex.core.foundation.Clock
import app.reflex.core.foundation.HealthRepository
import app.reflex.core.foundation.NotificationPermission
import app.reflex.core.foundation.Reminder
import app.reflex.core.foundation.ReminderScheduler
import app.reflex.core.foundation.attempt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Everything the Health screens are computed from, as one immutable value. */
data class HealthSnapshot(
    /** True until the stored data has been loaded (or failed to load). */
    val isLoading: Boolean,
    val loadFailed: Boolean,
    val data: HealthData,
    val now: Instant,
    val zone: ZoneId,
    val permission: NotificationPermission,
    val mayBeLate: Boolean,
    /** The scheduler's pending list, read back after every reconciliation. */
    val pending: List<Reminder>,
) {
    val today: LocalDate get() = HealthTime.today(now, zone)
    val canWrite: Boolean get() = !isLoading && !loadFailed
    val days: HealthDays by lazy { HealthDays(data) }
}

/**
 * The single owner of the Health data. Loads once through [HealthRepository], applies changes synchronously on the
 * core thread (Main in the app), saves asynchronously (serialized, latest snapshot wins), and after every change
 * recomputes the reminder set and reconciles it with the [ReminderScheduler] (`replaceAll`, then `pending`;
 * serialized). Screens observe [snapshots]; they call the methods below from their `update` and render [current].
 *
 * Not thread-safe by design: every call happens on the one thread all ViewModels run on.
 */
class HealthStore(
    private val repository: HealthRepository,
    private val scheduler: ReminderScheduler,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val newId: () -> String = { UUID.randomUUID().toString().lowercase() },
) {
    private var data = HealthData()
    private var isLoading = true
    private var loadFailed = false
    private var permission = NotificationPermission.NotDetermined
    private var mayBeLate = false
    private var pending: List<Reminder> = emptyList()
    private var isRequestingPermission = false
    private val afterLoad = mutableListOf<() -> Unit>()

    private val _snapshots = MutableStateFlow(snapshot())
    val snapshots: StateFlow<HealthSnapshot> = _snapshots.asStateFlow()
    val current: HealthSnapshot get() = _snapshots.value

    private val saves = Channel<HealthData>(Channel.CONFLATED)
    private val reconciliations = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch { for (snapshot in saves) attempt { repository.save(snapshot) } }
        scope.launch { for (request in reconciliations) reconcile() }
        scope.launch { load() }
        scope.launch {
            clock.changes.collect {
                readPermission()
                publish()
                reconcileLater()
            }
        }
    }

    /** Runs [action] once the data has loaded (right away if it has); dropped if loading failed. Used by deep links. */
    fun whenLoaded(action: () -> Unit) {
        when {
            isLoading -> afterLoad += action
            !loadFailed -> action()
        }
    }

    // Dose actions (Today, History) and notification responses ---------------------------------------------------

    fun take(doseId: String): Change = change { data, context -> HealthMutations.take(data, doseId, context) }
    fun takeAt(doseId: String, takenAt: String): Change = change { data, context -> HealthMutations.takeAt(data, doseId, takenAt, context) }
    fun skip(doseId: String): Change = change { data, context -> HealthMutations.skip(data, doseId, context) }
    fun undo(doseId: String): Change = change { data, _ -> HealthMutations.undo(data, doseId) }
    fun logAsNeeded(medicationId: String): Change = change { data, context -> HealthMutations.logAsNeeded(data, medicationId, context) }
    fun undoAsNeeded(medicationId: String): Change = change { data, context -> HealthMutations.undoAsNeeded(data, medicationId, context) }
    fun respond(slot: Slot, response: ReminderResponse): Change = change { data, context -> HealthMutations.respond(data, slot, response, context) }

    // Medications -----------------------------------------------------------------------------------------------

    /** Stores a validated draft; returns the medication's id, or null when nothing can be written. */
    fun saveMedication(draft: MedicationDraft): String? {
        var id: String? = null
        change { data, context ->
            HealthMutations.saveMedication(data, draft, context)?.let { (changed, savedId) ->
                id = savedId
                Change.Applied(changed)
            } ?: Change.NoOp
        }
        return id
    }

    fun archive(id: String): Change = change { data, context -> HealthMutations.archive(data, id, context) }
    fun restore(id: String): Change = change { data, context -> HealthMutations.restore(data, id, context) }
    fun delete(id: String): Change = change { data, _ -> HealthMutations.delete(data, id) }

    // Reminders permission ------------------------------------------------------------------------------------------

    /** notDetermined → ask; denied, or authorized but late → open the system settings; otherwise nothing. */
    fun turnOnReminders() {
        when {
            permission == NotificationPermission.NotDetermined -> requestPermission()
            permission == NotificationPermission.Denied || mayBeLate -> scope.launch { attempt { scheduler.openSettings() } }
        }
    }

    /** After saving a medication with times: ask, but only if the user was never asked. */
    fun requestPermissionIfNotDetermined() {
        if (permission == NotificationPermission.NotDetermined) requestPermission()
    }

    // Internals ---------------------------------------------------------------------------------------------------

    private fun requestPermission() {
        if (isRequestingPermission) return
        isRequestingPermission = true
        scope.launch {
            attempt { scheduler.requestPermission() }.onSuccess { permission = it }
            attempt { scheduler.mayBeLate() }.onSuccess { mayBeLate = it }
            isRequestingPermission = false
            publish()
            reconcileLater()
        }
    }

    private suspend fun load() {
        val loaded = attempt { repository.load() }
        readPermission()
        loaded.fold({ data = it }, { loadFailed = true })
        isLoading = false
        val queued = afterLoad.toList()
        afterLoad.clear()
        if (!loadFailed) queued.forEach { it() }
        publish()
        if (loadFailed) {
            // Never overwrite what we couldn't read: show what is pending, but don't replace it.
            attempt { scheduler.pending() }.onSuccess { pending = it }
            publish()
        } else {
            reconcileLater()
        }
    }

    private suspend fun readPermission() {
        attempt { scheduler.permission() }.onSuccess { permission = it }
        attempt { scheduler.mayBeLate() }.onSuccess { mayBeLate = it }
    }

    /** Applies one change; persists, publishes and reschedules only when the data actually changed. */
    private fun change(body: (HealthData, ChangeContext) -> Change): Change {
        if (isLoading || loadFailed) return Change.NoOp
        val result = body(data, ChangeContext(clock.now(), clock.zone(), newId))
        if (result is Change.Applied && result.data != data) {
            data = result.data
            saves.trySend(data)
            publish()
            reconcileLater()
        }
        return result
    }

    private fun reconcileLater() {
        reconciliations.trySend(Unit)
    }

    private suspend fun reconcile() {
        if (isLoading || loadFailed) return
        val desired = ReminderPlanner.plan(data, clock.now(), clock.zone(), permission)
        attempt { scheduler.replaceAll(desired) }
        attempt { scheduler.pending() }.onSuccess { pending = it }
        publish()
    }

    private fun publish() {
        _snapshots.value = snapshot()
    }

    private fun snapshot() = HealthSnapshot(isLoading, loadFailed, data, clock.now(), clock.zone(), permission, mayBeLate, pending)
}

/**
 * Delivers every store change to [owner] for as long as it is alive: a screen that left the navigation tree is
 * garbage-collected and stops listening, so popped screens don't keep recomputing.
 */
fun <O : Any> HealthStore.observe(owner: O, scope: CoroutineScope, onChange: (O) -> Unit) {
    val ref = WeakReference(owner)
    scope.launch {
        snapshots.takeWhile { ref.get() != null }.collect { ref.get()?.let(onChange) }
    }
}

/** `doseError` after a dose action: cleared on success, set on rejection, unchanged by a no-op. */
fun doseErrorAfter(previous: String?, change: Change): String? = when (change) {
    is Change.Applied -> null
    is Change.Rejected -> change.message
    Change.NoOp -> previous
}
