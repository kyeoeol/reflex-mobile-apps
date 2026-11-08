package app.reflex.core.introspection

import app.reflex.core.app.AppRoot
import app.reflex.core.foundation.StackNavigator
import app.reflex.introspection.IntrospectionAction
import app.reflex.introspection.IntrospectionNode
import app.reflex.introspection.IntrospectionRegistry
import app.reflex.introspection.JsonValue

class AppRootIntrospectionNode(private val root: AppRoot) : IntrospectionNode {
    override val name = "App"
    override val state: Map<String, JsonValue> = emptyMap()
    override val actions: List<IntrospectionAction> = emptyList()
    override val child: IntrospectionNode get() = StackNavigatorIntrospectionNode(root.navigator)
}

class StackNavigatorIntrospectionNode(private val navigator: StackNavigator<*>) : IntrospectionNode {
    override val name = "StackNavigator"
    override val state: Map<String, JsonValue>
        get() = linkedMapOf(
            "routes" to JsonValue.Arr(navigator.routes.value.map {
                JsonValue.Str(IntrospectionRegistry.nodeFor(it.viewModel)?.name ?: it.viewModel::class.simpleName.orEmpty())
            }),
        )
    override val actions get() = listOf(IntrospectionAction("goBack", IntrospectionAction.Scope.LENT) { navigator.goBack() })
    override val child: IntrospectionNode? get() = navigator.top?.let { IntrospectionRegistry.nodeFor(it.viewModel) }
}
