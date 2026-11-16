import IntrospectionKit

extension HistoryViewModel: IntrospectionProviding {
    public var introspectionNode: any IntrospectionNode { Introspection(viewModel: self) }

    private struct Introspection: IntrospectionNode {
        unowned let viewModel: HistoryViewModel
        let name = "History"

        var state: JSONObject {
            [
                "date": .string(viewModel.state.date),
                "canGoNext": .bool(viewModel.state.canGoNext),
                "summaryLabel": JSONValue(viewModel.state.summaryLabel),
                "doses": .array(viewModel.state.doses.map(\.introspectionJSON)),
                "message": JSONValue(viewModel.state.message),
                "doseError": JSONValue(viewModel.state.doseError),
            ]
        }

        var actions: [IntrospectionAction] {
            [
                IntrospectionAction(title: "PreviousDay") { viewModel.send(action: .previousDay) },
                IntrospectionAction(title: "NextDay") { viewModel.send(action: .nextDay) },
                IntrospectionAction(title: "SelectDate", input: DateInput.self) { viewModel.send(action: .selectDate($0.date)) },
                IntrospectionAction(title: "Take", input: DoseIdInput.self) { viewModel.send(action: .take(doseId: $0.doseId)) },
                IntrospectionAction(title: "TakeAt", input: TakeAtInput.self) {
                    viewModel.send(action: .takeAt(doseId: $0.doseId, takenAt: $0.takenAt))
                },
                IntrospectionAction(title: "Skip", input: DoseIdInput.self) { viewModel.send(action: .skip(doseId: $0.doseId)) },
                IntrospectionAction(title: "Undo", input: DoseIdInput.self) { viewModel.send(action: .undo(doseId: $0.doseId)) },
            ]
        }

        var child: (any IntrospectionNode)? { nil }
    }
}
