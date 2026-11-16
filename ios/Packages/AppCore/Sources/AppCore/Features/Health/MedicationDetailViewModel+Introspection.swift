import IntrospectionKit

extension MedicationDetailViewModel: IntrospectionProviding {
    public var introspectionNode: any IntrospectionNode { Introspection(viewModel: self) }

    private struct Introspection: IntrospectionNode {
        unowned let viewModel: MedicationDetailViewModel
        let name = "MedicationDetail"

        var state: JSONObject {
            [
                "id": .string(viewModel.state.id),
                "name": .string(viewModel.state.name),
                "kind": .string(viewModel.state.kind),
                "dose": .string(viewModel.state.dose),
                "notes": .string(viewModel.state.notes),
                "schedule": .string(viewModel.state.schedule),
                "startDate": .string(viewModel.state.startDate),
                "endDate": JSONValue(viewModel.state.endDate),
                "status": .string(viewModel.state.status),
                "adherenceLabel": .string(viewModel.state.adherenceLabel),
                "adherenceCaption": JSONValue(viewModel.state.adherenceCaption),
                "takenCount": .number(viewModel.state.takenCount),
                "skippedCount": .number(viewModel.state.skippedCount),
                "missedCount": .number(viewModel.state.missedCount),
                "history": .array(viewModel.state.history.map(\.introspectionJSON)),
                "canArchive": .bool(viewModel.state.canArchive),
                "canRestore": .bool(viewModel.state.canRestore),
                "isConfirmingDelete": .bool(viewModel.state.isConfirmingDelete),
            ]
        }

        var actions: [IntrospectionAction] {
            [
                IntrospectionAction(title: "Edit") { viewModel.send(action: .edit) },
                IntrospectionAction(title: "Archive") { viewModel.send(action: .archive) },
                IntrospectionAction(title: "Restore") { viewModel.send(action: .restore) },
                IntrospectionAction(title: "Delete") { viewModel.send(action: .delete) },
                IntrospectionAction(title: "CancelDelete") { viewModel.send(action: .cancelDelete) },
                IntrospectionAction(title: "ConfirmDelete") { viewModel.send(action: .confirmDelete) },
            ]
        }

        var child: (any IntrospectionNode)? { nil }
    }
}
