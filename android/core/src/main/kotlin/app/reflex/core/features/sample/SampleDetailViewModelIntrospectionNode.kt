package app.reflex.core.features.sample

import app.reflex.core.features.sample.SampleDetailViewModel.Action
import app.reflex.introspection.IntrospectionAction
import app.reflex.introspection.IntrospectionNode
import app.reflex.introspection.JsonValue
import kotlinx.serialization.Serializable

class SampleDetailViewModelIntrospectionNode(private val viewModel: SampleDetailViewModel) : IntrospectionNode {
    override val name = "SampleDetail"

    override val state: Map<String, JsonValue>
        get() = viewModel.state.value.let {
            linkedMapOf(
                "itemId" to JsonValue.Str(it.itemId),
                "title" to JsonValue.Str(it.title),
                "count" to JsonValue.Num(it.count),
                "canDecrement" to JsonValue.Bool(it.canDecrement),
                "isLoading" to JsonValue.Bool(it.isLoading),
                "loadError" to JsonValue.of(it.loadError),
                "isSaving" to JsonValue.Bool(it.isSaving),
                "isSaved" to JsonValue.Bool(it.isSaved),
                "saveError" to JsonValue.of(it.saveError),
            )
        }

    override val actions
        get() = listOf(
            IntrospectionAction("Increment") { viewModel.send(Action.Increment) },
            IntrospectionAction("Decrement") { viewModel.send(Action.Decrement) },
            IntrospectionAction("SetCount", SetCountInput.serializer()) { viewModel.send(Action.SetCount(it.count)) },
            IntrospectionAction("Save") { viewModel.send(Action.Save) },
        )

    override val child: IntrospectionNode? = null
}

@Serializable
data class SetCountInput(val count: Int)
