import IntrospectionKit

extension SampleDetailViewModel: IntrospectionProviding {
    public var introspectionNode: any IntrospectionNode { Introspection(viewModel: self) }

    private struct Introspection: IntrospectionNode {
        unowned let viewModel: SampleDetailViewModel
        let name = "SampleDetail"

        var state: JSONObject {
            [
                "itemId": .string(viewModel.state.itemId),
                "title": .string(viewModel.state.title),
                "count": .number(viewModel.state.count),
                "canDecrement": .bool(viewModel.state.canDecrement),
                "isLoading": .bool(viewModel.state.isLoading),
                "loadError": JSONValue(viewModel.state.loadError),
                "isSaving": .bool(viewModel.state.isSaving),
                "isSaved": .bool(viewModel.state.isSaved),
                "saveError": JSONValue(viewModel.state.saveError),
            ]
        }

        var actions: [IntrospectionAction] {
            [
                IntrospectionAction(title: "Increment") { viewModel.send(action: .increment) },
                IntrospectionAction(title: "Decrement") { viewModel.send(action: .decrement) },
                IntrospectionAction(title: "SetCount", input: SetCountInput.self) { viewModel.send(action: .setCount($0.count)) },
                IntrospectionAction(title: "Save") { viewModel.send(action: .save) },
            ]
        }

        var child: (any IntrospectionNode)? { nil }
    }
}

struct SetCountInput: IntrospectionInput {
    let count: Int
}
