import IntrospectionKit

extension SampleListViewModel: IntrospectionProviding {
    public var introspectionNode: any IntrospectionNode { Introspection(viewModel: self) }

    private struct Introspection: IntrospectionNode {
        unowned let viewModel: SampleListViewModel
        let name = "SampleList"

        var state: JSONObject {
            [
                "items": .array(viewModel.state.items.map { item in
                    ["id": .string(item.id), "title": .string(item.title), "count": .number(item.count)]
                }),
                "isLoading": .bool(viewModel.state.isLoading),
                "loadError": JSONValue(viewModel.state.loadError),
            ]
        }

        var actions: [IntrospectionAction] {
            [
                IntrospectionAction(title: "SelectItem", input: SelectItemInput.self) { viewModel.send(action: .selectItem(id: $0.id)) },
                IntrospectionAction(title: "Refresh") { viewModel.send(action: .refresh) },
            ]
        }

        var child: (any IntrospectionNode)? { nil }
    }
}

struct SelectItemInput: IntrospectionInput {
    let id: String
}
