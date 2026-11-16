package app.reflex.core.features.health

import app.reflex.core.features.health.HistoryViewModel.Action
import app.reflex.introspection.IntrospectionAction
import app.reflex.introspection.IntrospectionNode
import app.reflex.introspection.JsonValue
import kotlinx.serialization.Serializable

class HistoryViewModelIntrospectionNode(private val viewModel: HistoryViewModel) : IntrospectionNode {
    override val name = "History"

    override val state: Map<String, JsonValue>
        get() = viewModel.state.value.let {
            linkedMapOf(
                "isLoading" to JsonValue.Bool(it.isLoading),
                "date" to JsonValue.Str(it.date),
                "canGoNext" to JsonValue.Bool(it.canGoNext),
                "summaryLabel" to JsonValue.of(it.summaryLabel),
                "doses" to JsonValue.Arr(it.doses.map(DoseItem::json)),
                "message" to JsonValue.of(it.message),
                "doseError" to JsonValue.of(it.doseError),
            )
        }

    override val actions
        get() = listOf(
            IntrospectionAction("PreviousDay") { viewModel.send(Action.PreviousDay) },
            IntrospectionAction("NextDay") { viewModel.send(Action.NextDay) },
            IntrospectionAction("SelectDate", SelectDateInput.serializer()) { viewModel.send(Action.SelectDate(it.date)) },
            IntrospectionAction("Take", DoseIdInput.serializer()) { viewModel.send(Action.Take(it.doseId)) },
            IntrospectionAction("TakeAt", TakeAtInput.serializer()) { viewModel.send(Action.TakeAt(it.doseId, it.takenAt)) },
            IntrospectionAction("Skip", DoseIdInput.serializer()) { viewModel.send(Action.Skip(it.doseId)) },
            IntrospectionAction("Undo", DoseIdInput.serializer()) { viewModel.send(Action.Undo(it.doseId)) },
        )

    override val child: IntrospectionNode? = null
}

@Serializable
data class SelectDateInput(val date: String)
