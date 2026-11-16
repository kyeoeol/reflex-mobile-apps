package app.reflex.core.features.health

import app.reflex.core.foundation.NotificationPermission
import app.reflex.core.foundation.ViewModel
import kotlinx.coroutines.CoroutineScope

/** Navigation the Health screens need. Implemented by `AppRoot`; screens are pushed on the current tab's stack. */
interface HealthNavigating {
    fun showReminders()
    /** `null`: add mode; else edit that medication. */
    fun showMedicationEditor(medicationId: String?)
    fun showMedication(id: String)
    /** Pops [screen] (a ViewModel) off the stack it is the top of. */
    fun close(screen: Any)
}

class TodayViewModel(
    private val store: HealthStore,
    private val navigation: HealthNavigating,
    scope: CoroutineScope,
) : ViewModel<TodayViewModel.State, TodayViewModel.Action, TodayViewModel.Message>(render(store.current, null), scope) {

    data class State(
        val isLoading: Boolean,
        val date: String,
        val progressLabel: String?,
        val takenCount: Int,
        val scheduledCount: Int,
        val message: String?,
        val reminderBanner: String?,
        val groups: List<DoseGroup>,
        val asNeeded: List<AsNeededItem>,
        val doseError: String?,
    )

    /** One time slot: `key` = `"<date>T<time>"`. */
    data class DoseGroup(val key: String, val date: String, val time: String, val doses: List<DoseItem>)

    data class AsNeededItem(
        val medicationId: String,
        val name: String,
        val dose: String,
        val takenToday: Int,
        val lastTakenAt: String?,
        val summary: String,
    )

    sealed interface Action {
        data class Take(val doseId: String) : Action
        data class TakeAt(val doseId: String, val takenAt: String) : Action
        data class Skip(val doseId: String) : Action
        data class Undo(val doseId: String) : Action
        data class LogAsNeeded(val medicationId: String) : Action
        data class UndoAsNeeded(val medicationId: String) : Action
        data object TurnOnReminders : Action
        data object OpenReminders : Action
        data object AddMedication : Action
        data class OpenMedication(val id: String) : Action
    }

    sealed interface Message {
        data class ViewAction(val action: Action) : Message
        data object StoreChanged : Message
    }

    init {
        store.observe(this, scope) { it.send(Message.StoreChanged) }
    }

    override fun wrap(action: Action) = Message.ViewAction(action)

    override fun update(state: State, message: Message): State = when (message) {
        is Message.ViewAction -> when (val action = message.action) {
            is Action.Take -> dose(state, store.take(action.doseId))
            is Action.TakeAt -> dose(state, store.takeAt(action.doseId, action.takenAt))
            is Action.Skip -> dose(state, store.skip(action.doseId))
            is Action.Undo -> dose(state, store.undo(action.doseId))
            is Action.LogAsNeeded -> {
                store.logAsNeeded(action.medicationId)
                render(store.current, state.doseError)
            }
            is Action.UndoAsNeeded -> {
                store.undoAsNeeded(action.medicationId)
                render(store.current, state.doseError)
            }
            Action.TurnOnReminders -> {
                store.turnOnReminders()
                state
            }
            Action.OpenReminders -> {
                navigation.showReminders()
                state
            }
            Action.AddMedication -> {
                navigation.showMedicationEditor(null)
                state
            }
            is Action.OpenMedication -> {
                if (action.id in store.current.days.medications) navigation.showMedication(action.id)
                state
            }
        }
        Message.StoreChanged -> render(store.current, state.doseError)
    }

    private fun dose(state: State, change: Change) = render(store.current, doseErrorAfter(state.doseError, change))

    companion object {
        /** Days before today whose late doses can still show (a 23:30 dose stays until 01:30). */
        private const val LOOK_BACK_DAYS = 2L

        fun render(snapshot: HealthSnapshot, doseError: String?): State {
            val now = snapshot.now
            val zone = snapshot.zone
            val today = snapshot.today
            val days = snapshot.days
            val todays = days.occurrences(today)
            val earlier = (LOOK_BACK_DAYS downTo 1L).flatMap { days.occurrences(today.minusDays(it)) }
                .filter { it.scheduledAt(zone) + HealthTime.MISSED_AFTER > now }
            val groups = (earlier + todays).groupBy { it.slot }.entries
                .sortedWith(compareBy({ it.key.date }, { it.key.time }))
                .map { (slot, doses) ->
                    DoseGroup(
                        key = slot.key,
                        date = HealthTime.date(slot.date),
                        time = HealthTime.time(slot.time),
                        doses = doses.sortedWith(compareBy({ nameKey(it.name) }, { it.medicationId })).map { DoseItem.of(it, now, zone) },
                    )
                }
            val taken = todays.count { it.log?.status == LogStatus.Taken }
            val active = snapshot.data.medications.filter { it.status(today) == MedicationStatus.Active }
            val message = when {
                snapshot.isLoading -> null
                snapshot.loadFailed -> "Couldn't load your data"
                active.isEmpty() -> "No medications yet"
                todays.isEmpty() -> "Nothing scheduled today"
                todays.all { it.log != null } -> "All done for today"
                else -> null
            }
            val reminderBanner = when {
                active.none { it.hasTimes(today) } -> null
                snapshot.permission == NotificationPermission.Denied -> "Reminders are off"
                snapshot.permission == NotificationPermission.NotDetermined -> "Turn on reminders to get notified"
                snapshot.mayBeLate -> "Reminders may arrive a few minutes late"
                else -> null
            }
            val asNeededLogs = days.asNeededLogs(today)
            val asNeeded = active.filter { it.asNeeded }
                .sortedWith(compareBy({ nameKey(it.name) }, { it.id }))
                .map { medication ->
                    val logs = asNeededLogs.filter { it.medicationId == medication.id }
                    val last = logs.withIndex().maxWithOrNull(compareBy({ it.value.takenAt?.toInstant() }, { it.index }))?.value
                    val lastTakenAt = last?.takenAt?.let(HealthTime::clockTime)
                    AsNeededItem(
                        medicationId = medication.id,
                        name = medication.name,
                        dose = medication.dose,
                        takenToday = logs.size,
                        lastTakenAt = lastTakenAt,
                        summary = when (logs.size) {
                            0 -> "Not taken today"
                            1 -> "Taken once today, last at $lastTakenAt"
                            else -> "Taken ${logs.size} times today, last at $lastTakenAt"
                        },
                    )
                }
            return State(
                isLoading = snapshot.isLoading,
                date = HealthTime.date(today),
                progressLabel = progressLabel(taken, todays.size),
                takenCount = taken,
                scheduledCount = todays.size,
                message = message,
                reminderBanner = reminderBanner,
                groups = groups,
                asNeeded = asNeeded,
                doseError = doseError,
            )
        }
    }
}
