package app.reflex.core.features.health

import app.reflex.core.features.health.MedicationsViewModel.Action
import app.reflex.core.features.health.MedicationsViewModel.MedicationItem
import app.reflex.introspection.IntrospectionAction
import app.reflex.introspection.IntrospectionNode
import app.reflex.introspection.JsonValue

class MedicationsViewModelIntrospectionNode(private val viewModel: MedicationsViewModel) : IntrospectionNode {
    override val name = "Medications"

    override val state: Map<String, JsonValue>
        get() = viewModel.state.value.let {
            linkedMapOf(
                "isLoading" to JsonValue.Bool(it.isLoading),
                "isEmpty" to JsonValue.Bool(it.isEmpty),
                "active" to JsonValue.Arr(it.active.map(::item)),
                "ended" to JsonValue.Arr(it.ended.map(::item)),
                "archived" to JsonValue.Arr(it.archived.map(::item)),
            )
        }

    override val actions
        get() = listOf(
            IntrospectionAction("Add") { viewModel.send(Action.Add) },
            IntrospectionAction("Open", IdInput.serializer()) { viewModel.send(Action.Open(it.id)) },
        )

    override val child: IntrospectionNode? = null

    private fun item(item: MedicationItem): JsonValue = JsonValue.obj(
        "id" to JsonValue.Str(item.id),
        "name" to JsonValue.Str(item.name),
        "kind" to JsonValue.Str(item.kind),
        "dose" to JsonValue.Str(item.dose),
        "schedule" to JsonValue.Str(item.schedule),
    )
}
