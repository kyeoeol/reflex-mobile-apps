package app.reflex.core.features.health

import app.reflex.core.features.health.MedicationDetailViewModel.Action
import app.reflex.introspection.IntrospectionAction
import app.reflex.introspection.IntrospectionNode
import app.reflex.introspection.JsonValue

class MedicationDetailViewModelIntrospectionNode(private val viewModel: MedicationDetailViewModel) : IntrospectionNode {
    override val name = "MedicationDetail"

    override val state: Map<String, JsonValue>
        get() = viewModel.state.value.let {
            linkedMapOf(
                "id" to JsonValue.Str(it.id),
                "name" to JsonValue.Str(it.name),
                "kind" to JsonValue.Str(it.kind),
                "dose" to JsonValue.Str(it.dose),
                "notes" to JsonValue.Str(it.notes),
                "schedule" to JsonValue.Str(it.schedule),
                "startDate" to JsonValue.Str(it.startDate),
                "endDate" to JsonValue.of(it.endDate),
                "status" to JsonValue.Str(it.status),
                "adherenceLabel" to JsonValue.Str(it.adherenceLabel),
                "takenCount" to JsonValue.Num(it.takenCount),
                "skippedCount" to JsonValue.Num(it.skippedCount),
                "missedCount" to JsonValue.Num(it.missedCount),
                "history" to JsonValue.Arr(it.history.map(DoseItem::json)),
                "canArchive" to JsonValue.Bool(it.canArchive),
                "canRestore" to JsonValue.Bool(it.canRestore),
                "isConfirmingDelete" to JsonValue.Bool(it.isConfirmingDelete),
            )
        }

    override val actions
        get() = listOf(
            IntrospectionAction("Edit") { viewModel.send(Action.Edit) },
            IntrospectionAction("Archive") { viewModel.send(Action.Archive) },
            IntrospectionAction("Restore") { viewModel.send(Action.Restore) },
            IntrospectionAction("Delete") { viewModel.send(Action.Delete) },
            IntrospectionAction("CancelDelete") { viewModel.send(Action.CancelDelete) },
            IntrospectionAction("ConfirmDelete") { viewModel.send(Action.ConfirmDelete) },
        )

    override val child: IntrospectionNode? = null
}
