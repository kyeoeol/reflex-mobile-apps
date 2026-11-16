import IntrospectionKit

extension MedicationsViewModel: IntrospectionProviding {
    public var introspectionNode: any IntrospectionNode { Introspection(viewModel: self) }

    private struct Introspection: IntrospectionNode {
        unowned let viewModel: MedicationsViewModel
        let name = "Medications"

        var state: JSONObject {
            [
                "isLoading": .bool(viewModel.state.isLoading),
                "isEmpty": .bool(viewModel.state.isEmpty),
                "active": .array(viewModel.state.active.map(Self.row)),
                "ended": .array(viewModel.state.ended.map(Self.row)),
                "archived": .array(viewModel.state.archived.map(Self.row)),
            ]
        }

        static func row(_ row: MedicationsViewModel.Row) -> JSONValue {
            [
                "id": .string(row.id),
                "name": .string(row.name),
                "kind": .string(row.kind),
                "dose": .string(row.dose),
                "schedule": .string(row.schedule),
            ]
        }

        var actions: [IntrospectionAction] {
            [
                IntrospectionAction(title: "Add") { viewModel.send(action: .add) },
                IntrospectionAction(title: "Open", input: IdInput.self) { viewModel.send(action: .open(id: $0.id)) },
            ]
        }

        var child: (any IntrospectionNode)? { nil }
    }
}
