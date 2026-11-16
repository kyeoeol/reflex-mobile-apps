package app.reflex.core.features.health

import app.reflex.core.foundation.ViewModel
import kotlinx.coroutines.CoroutineScope

class MedicationDetailViewModel(
    medicationId: String,
    private val store: HealthStore,
    private val navigation: HealthNavigating,
    scope: CoroutineScope,
) : ViewModel<MedicationDetailViewModel.State, MedicationDetailViewModel.Action, MedicationDetailViewModel.Message>(
    render(store.current, placeholder(medicationId)), scope,
) {

    data class State(
        val id: String,
        val name: String,
        val kind: String,
        val dose: String,
        val notes: String,
        val schedule: String,
        val startDate: String,
        val endDate: String?,
        val status: String,
        val adherenceLabel: String,
        val takenCount: Int,
        val skippedCount: Int,
        val missedCount: Int,
        val history: List<DoseItem>,
        val canArchive: Boolean,
        val canRestore: Boolean,
        val isConfirmingDelete: Boolean,
    )

    sealed interface Action {
        data object Edit : Action
        data object Archive : Action
        data object Restore : Action
        data object Delete : Action
        data object CancelDelete : Action
        data object ConfirmDelete : Action
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
        is Message.ViewAction -> when (message.action) {
            Action.Edit -> {
                if (state.id in store.current.days.medications) navigation.showMedicationEditor(state.id)
                state
            }
            Action.Archive -> {
                store.archive(state.id)
                render(store.current, state)
            }
            Action.Restore -> {
                store.restore(state.id)
                render(store.current, state)
            }
            Action.Delete -> state.copy(isConfirmingDelete = true)
            Action.CancelDelete -> state.copy(isConfirmingDelete = false)
            Action.ConfirmDelete -> when {
                !state.isConfirmingDelete -> state
                store.delete(state.id) is Change.Applied -> {
                    navigation.close(this)
                    state.copy(isConfirmingDelete = false)
                }
                else -> state
            }
        }
        Message.StoreChanged -> render(store.current, state)
    }

    companion object {
        /** The adherence window: the 30 days ending today. */
        private const val WINDOW_DAYS = 30L

        /** What the screen shows before the medication is known (deep link before load, unknown id). */
        fun placeholder(id: String) = State(
            id = id, name = "", kind = "", dose = "", notes = "", schedule = "", startDate = "", endDate = null,
            status = MedicationStatus.Active.code, adherenceLabel = "No data yet", takenCount = 0, skippedCount = 0,
            missedCount = 0, history = emptyList(), canArchive = false, canRestore = false, isConfirmingDelete = false,
        )

        /** A medication that no longer exists (just deleted) keeps showing what it showed. */
        fun render(snapshot: HealthSnapshot, previous: State): State {
            val medication = snapshot.days.medications[previous.id] ?: return previous
            val today = snapshot.today
            val window = (WINDOW_DAYS - 1 downTo 0L).map { today.minusDays(it) }
            val occurrences = window.flatMap { day -> snapshot.days.occurrences(day).filter { it.medicationId == medication.id } }
            val statuses = occurrences.map { it.status(snapshot.now, snapshot.zone) }
            val taken = statuses.count { it == DoseStatus.Taken }
            val skipped = statuses.count { it == DoseStatus.Skipped }
            val missed = statuses.count { it == DoseStatus.Missed }
            val scheduled = occurrences.zip(statuses)
                .filter { (_, status) -> status != DoseStatus.Upcoming }
                .map { (occurrence, _) -> DoseItem.of(occurrence, snapshot.now, snapshot.zone) }
            val asNeeded = window.flatMap { day -> snapshot.days.asNeededLogs(day).filter { it.medicationId == medication.id } }
                .map(DoseItem::of)
            val history = (scheduled + asNeeded).sortedWith(
                compareByDescending<DoseItem> { it.date }.thenByDescending { it.time ?: it.takenAt.orEmpty() }.thenBy { it.id },
            )
            val status = medication.status(today)
            return previous.copy(
                name = medication.name,
                kind = medication.kind,
                dose = medication.dose,
                notes = medication.notes,
                schedule = medication.scheduleSummary(today),
                startDate = HealthTime.date(medication.startDate),
                endDate = medication.endDate?.let(HealthTime::date),
                status = status.code,
                adherenceLabel = adherencePercent(taken, taken + skipped + missed)?.let { "$it%" } ?: "No data yet",
                takenCount = taken,
                skippedCount = skipped,
                missedCount = missed,
                history = history,
                canArchive = status != MedicationStatus.Archived,
                canRestore = status == MedicationStatus.Archived,
            )
        }
    }
}
