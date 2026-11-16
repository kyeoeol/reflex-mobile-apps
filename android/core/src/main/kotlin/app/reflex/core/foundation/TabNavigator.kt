package app.reflex.core.foundation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tabs as plain state: a fixed list of tabs, each with its own [StackNavigator], and the selected tab id.
 * The Compose UI only *reflects* [selectedTab] and calls [select] from the tab bar.
 *
 * Introspection: node "TabNavigator", state `{"tabs": [<tab ids>], "selectedTab": <tab id>}`, lends
 * `selectTab {tab}` (unknown id: no-op) to the focused child; its child is the selected tab's stack.
 */
class TabNavigator<R : Routable>(val tabs: List<Tab<R>>, initial: String = tabs.first().id) {
    class Tab<R : Routable>(val id: String, val stack: StackNavigator<R>)

    init {
        require(tabs.isNotEmpty() && tabs.map { it.id }.toSet().size == tabs.size) { "tab ids must be unique" }
        require(tabs.any { it.id == initial }) { "unknown initial tab '$initial'" }
    }

    private val _selectedTab = MutableStateFlow(initial)
    val selectedTab: StateFlow<String> = _selectedTab.asStateFlow()

    val selected: Tab<R> get() = tabs.first { it.id == _selectedTab.value }

    fun stack(id: String): StackNavigator<R>? = tabs.firstOrNull { it.id == id }?.stack

    /** Unknown ids are ignored (the action is still always listed). */
    fun select(id: String) {
        if (tabs.any { it.id == id }) _selectedTab.value = id
    }
}
