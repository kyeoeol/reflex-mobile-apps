package app.reflex.core.foundation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A route wraps the ViewModel of one screen. */
interface Routable {
    val viewModel: Any
}

/**
 * Push navigation as plain state. Pushing a screen is a state change, so the CLI can walk the whole app
 * (deep links included) without creating a single view. The Compose UI only *reflects* [routes] and routes
 * system back to [goBack].
 *
 * Introspection: node "StackNavigator", state `{"routes": [<node names>]}`, lends `goBack` to the focused child.
 */
class StackNavigator<R : Routable>(routes: List<R> = emptyList()) {
    private val _routes = MutableStateFlow(routes)
    val routes: StateFlow<List<R>> = _routes.asStateFlow()

    val top: R? get() = _routes.value.lastOrNull()
    val canGoBack: Boolean get() = _routes.value.size > 1

    fun push(route: R) { _routes.value = _routes.value + route }

    /** No-op on the root screen (the action is still always listed). */
    fun goBack() { if (_routes.value.size > 1) _routes.value = _routes.value.dropLast(1) }

    fun reset(routes: List<R>) { _routes.value = routes }
}
