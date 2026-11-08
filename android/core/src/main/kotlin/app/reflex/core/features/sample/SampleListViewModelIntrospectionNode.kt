package app.reflex.core.features.sample

import app.reflex.core.features.sample.SampleListViewModel.Action
import app.reflex.introspection.IntrospectionAction
import app.reflex.introspection.IntrospectionNode
import app.reflex.introspection.JsonValue
import kotlinx.serialization.Serializable

class SampleListViewModelIntrospectionNode(private val viewModel: SampleListViewModel) : IntrospectionNode {
    override val name = "SampleList"

    override val state: Map<String, JsonValue>
        get() = viewModel.state.value.let {
            linkedMapOf(
                "items" to JsonValue.Arr(it.items.map { item ->
                    JsonValue.obj("id" to JsonValue.Str(item.id), "title" to JsonValue.Str(item.title), "count" to JsonValue.Num(item.count))
                }),
                "isLoading" to JsonValue.Bool(it.isLoading),
                "loadError" to JsonValue.of(it.loadError),
            )
        }

    override val actions
        get() = listOf(
            IntrospectionAction("SelectItem", SelectItemInput.serializer()) { viewModel.send(Action.SelectItem(it.id)) },
            IntrospectionAction("Refresh") { viewModel.send(Action.Refresh) },
        )

    override val child: IntrospectionNode? = null
}

@Serializable
data class SelectItemInput(val id: String)
