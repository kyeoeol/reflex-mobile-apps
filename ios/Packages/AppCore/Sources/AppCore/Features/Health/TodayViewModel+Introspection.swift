import IntrospectionKit

extension TodayViewModel: IntrospectionProviding {
    public var introspectionNode: any IntrospectionNode { Introspection(viewModel: self) }

    private struct Introspection: IntrospectionNode {
        unowned let viewModel: TodayViewModel
        let name = "Today"

        var state: JSONObject {
            [
                "isLoading": .bool(viewModel.state.isLoading),
                "date": .string(viewModel.state.date),
                "progressLabel": JSONValue(viewModel.state.progressLabel),
                "takenCount": .number(viewModel.state.takenCount),
                "scheduledCount": .number(viewModel.state.scheduledCount),
                "message": JSONValue(viewModel.state.message),
                "reminderBanner": JSONValue(viewModel.state.reminderBanner),
                "groups": .array(viewModel.state.groups.map { group in
                    [
                        "key": .string(group.key),
                        "date": .string(group.date),
                        "time": .string(group.time),
                        "doses": .array(group.doses.map(\.introspectionJSON)),
                    ]
                }),
                "asNeeded": .array(viewModel.state.asNeeded.map { item in
                    [
                        "medicationId": .string(item.medicationId),
                        "name": .string(item.name),
                        "dose": .string(item.dose),
                        "takenToday": .number(item.takenToday),
                        "lastTakenAt": JSONValue(item.lastTakenAt),
                        "summary": .string(item.summary),
                    ]
                }),
                "doseError": JSONValue(viewModel.state.doseError),
            ]
        }

        var actions: [IntrospectionAction] {
            [
                IntrospectionAction(title: "Take", input: DoseIdInput.self) { viewModel.send(action: .take(doseId: $0.doseId)) },
                IntrospectionAction(title: "TakeAt", input: TakeAtInput.self) {
                    viewModel.send(action: .takeAt(doseId: $0.doseId, takenAt: $0.takenAt))
                },
                IntrospectionAction(title: "Skip", input: DoseIdInput.self) { viewModel.send(action: .skip(doseId: $0.doseId)) },
                IntrospectionAction(title: "Undo", input: DoseIdInput.self) { viewModel.send(action: .undo(doseId: $0.doseId)) },
                IntrospectionAction(title: "LogAsNeeded", input: MedicationIdInput.self) {
                    viewModel.send(action: .logAsNeeded(medicationId: $0.medicationId))
                },
                IntrospectionAction(title: "UndoAsNeeded", input: MedicationIdInput.self) {
                    viewModel.send(action: .undoAsNeeded(medicationId: $0.medicationId))
                },
                IntrospectionAction(title: "TurnOnReminders") { viewModel.send(action: .turnOnReminders) },
                IntrospectionAction(title: "OpenReminders") { viewModel.send(action: .openReminders) },
                IntrospectionAction(title: "AddMedication") { viewModel.send(action: .addMedication) },
                IntrospectionAction(title: "OpenMedication", input: IdInput.self) { viewModel.send(action: .openMedication(id: $0.id)) },
            ]
        }

        var child: (any IntrospectionNode)? { nil }
    }
}

struct MedicationIdInput: IntrospectionInput {
    let medicationId: String
}
