package app.reflex.core.features.health

import app.reflex.core.foundation.ViewModel
import kotlinx.coroutines.CoroutineScope

class MedicationDetailViewModel(
    /** The medication this screen shows. (AppRoot removes the screen when it is deleted.) */
    val medicationId: String,
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
        val adherenceCaption: String?,
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
                if (medicationId in store.current.days.medications) navigation.showMedicationEditor(medicationId)
                state
            }
            Action.Archive -> {
                store.archive(medicationId)
                render(store.current, state)
            }
            Action.Restore -> {
                store.restore(medicationId)
                render(store.current, state)
            }
            Action.Delete -> render(store.current, state.copy(isConfirmingDelete = true))
            Action.CancelDelete -> state.copy(isConfirmingDelete = false)
            // The screen then disappears: AppRoot removes every screen of a medication that no longer exists.
            Action.ConfirmDelete -> if (state.isConfirmingDelete) {
                store.delete(medicationId)
                render(store.current, state.copy(isConfirmingDelete = false))
            } else {
                state
            }
        }
        Message.StoreChanged -> render(store.current, state)
    }

    companion object {
        /** The adherence window: the 30 days ending today. */
        private const val WINDOW_DAYS = 30L
        private const val NO_DATA = "No data yet"

        /** What the screen shows while the medication isn't known (before load, unknown or deleted id). */
        fun placeholder(id: String) = State(
            id = id, name = "", kind = "", dose = "", notes = "", schedule = "", startDate = "", endDate = null,
            status = MedicationStatus.Active.code, adherenceLabel = NO_DATA, adherenceCaption = null, takenCount = 0,
            skippedCount = 0, missedCount = 0, history = emptyList(), canArchive = false, canRestore = false,
            isConfirmingDelete = false,
        )

        fun render(snapshot: HealthSnapshot, previous: State): State {
            val medication = snapshot.days.medications[previous.id] ?: return placeholder(previous.id)
            val today = snapshot.today
            val window = (WINDOW_DAYS - 1 downTo 0L).map { today.minusDays(it) }
            val occurrences = window.flatMap { day -> snapshot.days.occurrences(day).filter { it.medicationId == medication.id } }
            val statuses = occurrences.map { it.status(snapshot.now, snapshot.zone) }
            val taken = statuses.count { it == DoseStatus.Taken }
            val skipped = statuses.count { it == DoseStatus.Skipped }
            val missed = statuses.count { it == DoseStatus.Missed }
            val resolved = taken + skipped + missed
            val scheduled = occurrences.zip(statuses)
                .filter { (_, status) -> status != DoseStatus.Upcoming }
                .map { (occurrence, _) -> DoseItem.of(occurrence, snapshot.now, snapshot.zone) }
            val asNeeded = window.flatMap { day -> snapshot.days.asNeededLogs(day).filter { it.medicationId == medication.id } }
                .map(DoseItem::of)
            // Newest first: date desc, time desc (`takenAt` for as-needed entries), then id asc.
            val history = (scheduled + asNeeded).sortedWith(
                compareByDescending<DoseItem> { it.date }.thenByDescending { it.time ?: it.takenAt.orEmpty() }.thenBy { it.id },
            )
            val status = medication.status(today)
            val percent = adherencePercent(taken, resolved)
            return previous.copy(
                name = medication.name,
                kind = medication.kind,
                dose = medication.dose,
                notes = medication.notes,
                schedule = medication.scheduleSummary(),
                startDate = HealthTime.date(medication.startDate),
                endDate = medication.endDate?.let(HealthTime::date),
                status = status.code,
                adherenceLabel = percent?.let { "$it%" } ?: NO_DATA,
                adherenceCaption = percent?.let { "$taken of $resolved doses taken in the last 30 days" },
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
