import Observation

/// Tabs as plain state: one `StackNavigator` per tab, each keeping its own screens. Selecting a tab is a state
/// change, so the CLI can switch tabs without a view. The Mobile UI only *reflects* `selectedTab` and routes the tab
/// bar to `select(_:)`.
///
/// Introspection: node "TabNavigator", state `{"tabs": [<tab ids>], "selectedTab": <tab id>}`, lends
/// `selectTab {tab}` (unknown id: no-op); its child is the selected tab's stack.
@MainActor @Observable
public final class TabNavigator<Tab: Hashable & RawRepresentable, Route: Routable> where Tab.RawValue == String {
    public let tabs: [Tab]
    public private(set) var selectedTab: Tab
    @ObservationIgnored private let stacks: [Tab: StackNavigator<Route>]

    /// Every tab starts with an empty stack; the owner fills them with `stack(_:).reset(...)`.
    public init(tabs: [Tab], selected: Tab) {
        precondition(tabs.contains(selected), "the selected tab must be one of the tabs")
        self.tabs = tabs
        self.selectedTab = selected
        self.stacks = Dictionary(uniqueKeysWithValues: tabs.map { ($0, StackNavigator<Route>()) })
    }

    public func stack(_ tab: Tab) -> StackNavigator<Route> { stacks[tab]! }

    public var selectedStack: StackNavigator<Route> { stack(selectedTab) }

    public func select(_ tab: Tab) {
        if stacks[tab] != nil { selectedTab = tab }
    }

    /// By tab id (what the tab bar and the CLI speak); unknown ids are ignored.
    public func select(id: String) {
        if let tab = Tab(rawValue: id) { select(tab) }
    }
}
