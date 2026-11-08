import Foundation

/// Focus and lent-action resolution, identical to Android's `Resolution.resolve`.
///
/// - The focused node is the deepest node in the `child` chain (what the user is looking at).
/// - Available actions = the focused node's own actions, then actions lent by ancestors
///   (nearest ancestor first). On a title collision the child wins.
/// - Anything else is off-limits, just as anything off-screen is to a user.
@MainActor
public struct Resolution {
    public struct Available {
        public let action: IntrospectionAction
        /// Name of the ancestor that lent this action, or nil for the focused node's own action.
        public let lentBy: String?
    }

    public let chain: [any IntrospectionNode]
    public let available: [Available]

    public var focused: any IntrospectionNode { chain[chain.count - 1] }
    public var path: [String] { chain.map(\.name) }

    public static func resolve(_ root: any IntrospectionNode) -> Resolution {
        var chain: [any IntrospectionNode] = [root]
        while let next = chain[chain.count - 1].child { chain.append(next) }
        let focused = chain[chain.count - 1]
        var available = focused.actions.filter { $0.scope == .local }.map { Available(action: $0, lentBy: nil) }
        for ancestor in chain.dropLast().reversed() {
            for action in ancestor.actions where action.scope == .lent && !available.contains(where: { $0.action.title == action.title }) {
                available.append(Available(action: action, lentBy: ancestor.name))
            }
        }
        return Resolution(chain: chain, available: available)
    }

    public func action(titled title: String) -> Available? {
        available.first { $0.action.title == title }
    }

    /// `{"name", "path", "state", "actions": [{"title", "input"?, "lentBy"?}]}`
    public func snapshot() -> JSONValue {
        [
            "name": .string(focused.name),
            "path": .array(path.map(JSONValue.string)),
            "state": .object(focused.state),
            "actions": .array(available.map { Resolution.describe($0.action, lentBy: $0.lentBy) }),
        ]
    }

    /// The whole chain from root to focus, for debugging (`tree`).
    public func tree() -> JSONValue {
        .array(chain.map { node in
            [
                "name": .string(node.name),
                "state": .object(node.state),
                "actions": .array(node.actions.map { Resolution.describe($0, lentBy: nil, scope: true) }),
            ]
        })
    }

    static func describe(_ action: IntrospectionAction, lentBy: String?, scope: Bool = false) -> JSONValue {
        var object: JSONObject = ["title": .string(action.title)]
        if let schema = action.inputSchema { object["input"] = schema }
        if let lentBy { object["lentBy"] = .string(lentBy) }
        if scope, action.scope == .lent { object["scope"] = "lent" }
        return .object(object)
    }
}
