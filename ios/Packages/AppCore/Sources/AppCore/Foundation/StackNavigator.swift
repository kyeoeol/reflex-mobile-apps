import Observation

/// A route wraps the ViewModel of one screen.
public protocol Routable {
    var viewModel: AnyObject { get }
}

/// Push navigation as plain state. Pushing a screen is a state change, so the CLI can walk the whole
/// app (deep links included) without creating a single view. The Mobile UI only *reflects* `routes`,
/// and routes system back/swipe gestures back to `goBack()`.
///
/// Introspection: node "StackNavigator", state `{"routes": [<node names>]}`, lends `goBack` to the focused child.
@MainActor @Observable
public final class StackNavigator<Route: Routable> {
    public private(set) var routes: [Route]

    public init(routes: [Route] = []) { self.routes = routes }

    public var top: Route? { routes.last }
    public var canGoBack: Bool { routes.count > 1 }

    public func push(_ route: Route) { routes.append(route) }

    /// No-op on the root screen (the action is still always listed).
    public func goBack() { if routes.count > 1 { routes.removeLast() } }

    public func reset(_ routes: [Route]) { self.routes = routes }
}
