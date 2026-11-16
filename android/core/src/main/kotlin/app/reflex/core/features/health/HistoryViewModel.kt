package app.reflex.core.features.health

import app.reflex.core.foundation.ViewModel
import kotlinx.coroutines.CoroutineScope
import java.time.LocalDate

class HistoryViewModel(
    private val store: HealthStore,
    scope: CoroutineScope,
) : ViewModel<HistoryViewModel.State, HistoryViewModel.Action, HistoryViewModel.Message>(
    render(store.current, store.current.today, null), scope,
) {

    data class State(
        val date: String,
        val canGoNext: Boolean,
        val summaryLabel: String?,
        val doses: List<DoseItem>,
        val message: String?,
        val doseError: String?,
    )

    sealed interface Action {
        data object PreviousDay : Action
        data object NextDay : Action
        data class SelectDate(val date: String) : Action
        data class Take(val doseId: String) : Action
        data class TakeAt(val doseId: String, val takenAt: String) : Action
        data class Skip(val doseId: String) : Action
        data class Undo(val doseId: String) : Action
    }

    sealed interface Message {
        data class ViewAction(val action: Action) : Message
        data object StoreChanged : Message
    }

    init {
        store.observe(this, scope) { it.send(Message.StoreChanged) }
    }

    override fun wrap(action: Action) = Message.ViewAction(action)

    override fun update(state: State, message: Message): State {
        val date = shownDate(state)
        val today = store.current.today
        return when (message) {
            is Message.ViewAction -> when (val action = message.action) {
                Action.PreviousDay -> render(store.current, date.minusDays(1), state.doseError)
                Action.NextDay -> if (date < today) render(store.current, date.plusDays(1), state.doseError) else state
                is Action.SelectDate -> {
                    val selected = HealthTime.parseDate(action.date)
                    if (selected == null || selected > today) state else render(store.current, selected, state.doseError)
                }
                is Action.Take -> render(store.current, date, doseErrorAfter(state.doseError, store.take(action.doseId)))
                is Action.TakeAt -> render(store.current, date, doseErrorAfter(state.doseError, store.takeAt(action.doseId, action.takenAt)))
                is Action.Skip -> render(store.current, date, doseErrorAfter(state.doseError, store.skip(action.doseId)))
                is Action.Undo -> render(store.current, date, doseErrorAfter(state.doseError, store.undo(action.doseId)))
            }
            Message.StoreChanged -> render(store.current, date, state.doseError)
        }
    }

    private fun shownDate(state: State): LocalDate = HealthTime.parseDate(state.date) ?: store.current.today

    companion object {
        const val EMPTY_MESSAGE = "Nothing recorded on this day"

        /** That day's occurrences plus its as-needed log entries, by time (`takenAt` for as-needed), then name. */
        fun render(snapshot: HealthSnapshot, date: LocalDate, doseError: String?): State {
            val occurrences = snapshot.days.occurrences(date)
            val scheduled = occurrences.map { DoseItem.of(it, snapshot.now, snapshot.zone) }
            val asNeeded = snapshot.days.asNeededLogs(date).map(DoseItem::of)
            val doses = (scheduled + asNeeded).sortedWith(
                compareBy({ it.time ?: it.takenAt.orEmpty() }, { nameKey(it.name) }, { it.medicationId }, { it.id }),
            )
            return State(
                date = HealthTime.date(date),
                canGoNext = date < snapshot.today,
                summaryLabel = progressLabel(occurrences.count { it.log?.status == LogStatus.Taken }, occurrences.size),
                doses = doses,
                message = if (doses.isEmpty()) EMPTY_MESSAGE else null,
                doseError = doseError,
            )
        }
    }
}
