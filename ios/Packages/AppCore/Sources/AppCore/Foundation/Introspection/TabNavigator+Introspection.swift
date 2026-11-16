import IntrospectionKit

extension TabNavigator: IntrospectionProviding {
    public var introspectionNode: any IntrospectionNode { Introspection(navigator: self) }

    private struct Introspection: IntrospectionNode {
        unowned let navigator: TabNavigator
        let name = "TabNavigator"

        var state: JSONObject {
            [
                "tabs": .array(navigator.tabs.map { .string($0.rawValue) }),
                "selectedTab": .string(navigator.selectedTab.rawValue),
            ]
        }

        var actions: [IntrospectionAction] {
            [IntrospectionAction(title: "selectTab", input: SelectTabInput.self, scope: .lent) { navigator.select(id: $0.tab) }]
        }

        var child: (any IntrospectionNode)? { navigator.selectedStack.introspectionNode }
    }
}

struct SelectTabInput: IntrospectionInput {
    let tab: String
}
