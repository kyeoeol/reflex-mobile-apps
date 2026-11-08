import IntrospectionKit

extension StackNavigator: IntrospectionProviding {
    public var introspectionNode: any IntrospectionNode { Introspection(navigator: self) }

    private struct Introspection: IntrospectionNode {
        unowned let navigator: StackNavigator
        let name = "StackNavigator"

        var state: JSONObject {
            ["routes": .array(navigator.routes.map { .string(Self.node(of: $0)?.name ?? "\(type(of: $0.viewModel))") })]
        }

        var actions: [IntrospectionAction] {
            [IntrospectionAction(title: "goBack", scope: .lent) { navigator.goBack() }]
        }

        var child: (any IntrospectionNode)? { navigator.top.flatMap(Self.node(of:)) }

        static func node(of route: Route) -> (any IntrospectionNode)? {
            (route.viewModel as? IntrospectionProviding)?.introspectionNode
        }
    }
}
