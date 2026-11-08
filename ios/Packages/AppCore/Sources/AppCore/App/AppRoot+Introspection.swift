import IntrospectionKit

extension AppRoot: IntrospectionProviding {
    public var introspectionNode: any IntrospectionNode { Introspection(root: self) }

    private struct Introspection: IntrospectionNode {
        unowned let root: AppRoot
        let name = "App"
        var state: JSONObject { [:] }
        var actions: [IntrospectionAction] { [] }
        var child: (any IntrospectionNode)? { root.navigator.introspectionNode }
    }
}
