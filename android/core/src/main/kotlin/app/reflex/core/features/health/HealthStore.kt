package app.reflex.core.features.health

import app.reflex.core.foundation.Clock
import app.reflex.core.foundation.HealthRepository
import app.reflex.core.foundation.NotificationPermission
import app.reflex.core.foundation.Reminder
import app.reflex.core.foundation.ReminderScheduler
import app.reflex.core.foundation.attempt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
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
    /** The latest save failed; cleared by the next successful one. */
    val saveFailed: Boolean,
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
    val days: HealthDays by lazy { HealthDays(data, zone) }
}

/**
 * The single owner of the Health data. Loads once through [HealthRepository], applies changes synchronously on the
 * core thread (Main in the app), saves asynchronously (serialized; only the latest pending snapshot is kept), and
 * after every change, permission change and clock change recomputes the reminder set and reconciles it with the
 * [ReminderScheduler] (re-read permission and mayBeLate, `replaceAll`, then `pending`; serialized and coalesced the
 * same way). Screens observe [snapshots]; they call the methods below from their `update` and render [current].
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
    private var isReloading = false
    private var loadFailed = false
    private var saveFailed = false
    private var permission = NotificationPermission.NotDetermined
    private var mayBeLate = false
    private var pending: List<Reminder> = emptyList()
    private var isRequestingPermission = false
    private val afterLoad = mutableListOf<(Boolean) -> Unit>()
    private val dataListeners = mutableListOf<(HealthData) -> Unit>()

    private val _snapshots = MutableStateFlow(snapshot())
    val snapshots: StateFlow<HealthSnapshot> = _snapshots.asStateFlow()
    val current: HealthSnapshot get() = _snapshots.value

    /**
     * Saves and reconciliations are numbered when requested; the workers record the number of the last request
     * they finished. Both channels are conflated (only the latest pending request is kept), and finishing request n
     * covers every earlier one, which is what [settle] waits for.
     */
    private class Request<T>(val number: Long, val value: T)
    private data class Finished(val save: Long = 0, val reconcile: Long = 0)

    private var saveRequests = 0L
    private var reconcileRequests = 0L
    private val finished = MutableStateFlow(Finished())
    private val initialLoad = CompletableDeferred<Unit>()
    private val saves = Channel<Request<HealthData>>(Channel.CONFLATED)
    private val reconciliations = Channel<Request<Unit>>(Channel.CONFLATED)

    init {
        scope.launch {
            for (request in saves) {
                saveFailed = attempt { repository.save(request.value) }.isFailure
                finished.update { it.copy(save = request.number) }
                publish()
            }
        }
        scope.launch {
            for (request in reconciliations) {
                reconcile()
                finished.update { it.copy(reconcile = request.number) }
            }
        }
        scope.launch {
            load()
            initialLoad.complete(Unit)
        }
        scope.launch {
            clock.changes.collect {
                publish()
                reconcileLater()
            }
        }
    }

    /**
     * Runs [action] once the initial load has finished (right away if it has), with `true` if the data loaded and
     * `false` if it failed (then nothing may be applied: queued responses and links are dropped).
     */
    fun whenLoaded(action: (loaded: Boolean) -> Unit) {
        if (isLoading) afterLoad += action else action(!loadFailed)
    }

    /** Suspends until the initial load has finished; true if the data loaded. */
    suspend fun awaitLoad(): Boolean {
        initialLoad.await()
        return !loadFailed
    }

    /** Returns once the initial load and every save/reconciliation requested so far have finished. */
    suspend fun settle() {
        initialLoad.await()
        val save = saveRequests
        val reconcile = reconcileRequests
        finished.first { it.save >= save && it.reconcile >= reconcile }
    }

    /** Called synchronously with the new data after every change (and after every successful load). */
    fun onDataChange(listener: (HealthData) -> Unit) {
        dataListeners += listener
    }

    // Dose actions (Today, History) and notification responses ---------------------------------------------------

    fun take(doseId: String): Change = change { data, context -> HealthMutations.take(data, doseId, context) }
    fun takeAt(doseId: String, takenAt: String): Change = change { data, context -> HealthMutations.takeAt(data, doseId, takenAt, context) }
    fun skip(doseId: String): Change = change { data, context -> HealthMutations.skip(data, doseId, context) }
    fun undo(doseId: String): Change = change { data, context -> HealthMutations.undo(data, doseId, context) }
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

    // Errors ------------------------------------------------------------------------------------------------------

    /** Load error → load again; save error → save the whole snapshot again; otherwise nothing. */
    fun retry() {
        when {
            isLoading -> Unit
            loadFailed -> if (!isReloading) {
                isReloading = true
                scope.launch {
                    load()
                    isReloading = false
                }
            }
            saveFailed -> saveLater()
        }
    }

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
            isRequestingPermission = false
            publish()
            reconcileLater()
        }
    }

    /**
     * Loads the stored data (initially and on Retry after a failed load). Whatever happens, the reminders are
     * reconciled afterwards (after a failure only read back, see [reconcile]) and queued work runs or is dropped.
     */
    private suspend fun load() {
        val loaded = attempt { repository.load() }
        readPermission()
        loaded.onSuccess { data = it }
        loadFailed = loaded.isFailure
        isLoading = false
        if (!loadFailed) dataListeners.forEach { it(data) }
        val queued = afterLoad.toList()
        afterLoad.clear()
        queued.forEach { it(!loadFailed) }
        publish()
        reconcileLater()
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
            saveLater()
            dataListeners.forEach { it(data) }
            publish()
            reconcileLater()
        }
        return result
    }

    private fun saveLater() {
        saves.trySend(Request(++saveRequests, data))
    }

    private fun reconcileLater() {
        reconciliations.trySend(Request(++reconcileRequests, Unit))
    }

    /**
     * Re-reads permission and mayBeLate, hands the full desired set to the scheduler, and reads its pending list
     * back. After a failed load the scheduler's reminders are never replaced (they were computed from data we
     * can't read), only read.
     */
    private suspend fun reconcile() {
        if (isLoading) return
        readPermission()
        if (!loadFailed) {
            val plan = ReminderPlanner.plan(data, clock.now(), clock.zone(), permission)
            attempt { scheduler.replaceAll(plan.reminders, plan.openSlots) }
        }
        attempt { scheduler.pending() }.onSuccess { pending = it }
        publish()
    }

    private fun publish() {
        _snapshots.value = snapshot()
    }

    private fun snapshot() = HealthSnapshot(
        isLoading, loadFailed, saveFailed, data, clock.now(), clock.zone(), permission, mayBeLate, pending,
    )
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
