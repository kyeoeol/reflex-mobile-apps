import IntrospectionKit

extension MedicationEditorViewModel: IntrospectionProviding {
    public var introspectionNode: any IntrospectionNode { Introspection(viewModel: self) }

    private struct Introspection: IntrospectionNode {
        unowned let viewModel: MedicationEditorViewModel
        let name = "MedicationEditor"

        var state: JSONObject {
            [
                "mode": .string(viewModel.state.mode),
                "medicationId": JSONValue(viewModel.state.medicationId),
                "name": .string(viewModel.state.name),
                "kind": .string(viewModel.state.kind),
                "dose": .string(viewModel.state.dose),
                "notes": .string(viewModel.state.notes),
                "asNeeded": .bool(viewModel.state.asNeeded),
                "times": JSONValue(viewModel.state.times),
                "weekdays": JSONValue(viewModel.state.weekdays),
                "startDate": .string(viewModel.state.startDate),
                "endDate": JSONValue(viewModel.state.endDate),
                "nameError": JSONValue(viewModel.state.nameError),
                "timesError": JSONValue(viewModel.state.timesError),
                "weekdaysError": JSONValue(viewModel.state.weekdaysError),
                "datesError": JSONValue(viewModel.state.datesError),
                "timeError": JSONValue(viewModel.state.timeError),
                "canAddTime": .bool(viewModel.state.canAddTime),
                "canSave": .bool(viewModel.state.canSave),
                "isSaving": .bool(viewModel.state.isSaving),
            ]
        }

        var actions: [IntrospectionAction] {
            [
                IntrospectionAction(title: "SetName", input: NameInput.self) { viewModel.send(action: .setName($0.name)) },
                IntrospectionAction(title: "SetKind", input: KindInput.self) { viewModel.send(action: .setKind($0.kind)) },
                IntrospectionAction(title: "SetDose", input: DoseTextInput.self) { viewModel.send(action: .setDose($0.dose)) },
                IntrospectionAction(title: "SetNotes", input: NotesInput.self) { viewModel.send(action: .setNotes($0.notes)) },
                IntrospectionAction(title: "SetAsNeeded", input: AsNeededInput.self) { viewModel.send(action: .setAsNeeded($0.asNeeded)) },
                IntrospectionAction(title: "AddTime", input: TimeInput.self) { viewModel.send(action: .addTime($0.time)) },
                IntrospectionAction(title: "RemoveTime", input: TimeInput.self) { viewModel.send(action: .removeTime($0.time)) },
                IntrospectionAction(title: "ToggleWeekday", input: WeekdayInput.self) { viewModel.send(action: .toggleWeekday($0.weekday)) },
                IntrospectionAction(title: "SetStartDate", input: DateInput.self) { viewModel.send(action: .setStartDate($0.date)) },
                IntrospectionAction(title: "SetEndDate", input: OptionalDateInput.self) { viewModel.send(action: .setEndDate($0.date)) },
                IntrospectionAction(title: "Save") { viewModel.send(action: .save) },
            ]
        }

        var child: (any IntrospectionNode)? { nil }
    }
}

struct NameInput: IntrospectionInput {
    let name: String
}

struct KindInput: IntrospectionInput {
    let kind: String
}

struct DoseTextInput: IntrospectionInput {
    let dose: String
}

struct NotesInput: IntrospectionInput {
    let notes: String
}

struct AsNeededInput: IntrospectionInput {
    let asNeeded: Bool
}

struct TimeInput: IntrospectionInput {
    let time: String
}

struct WeekdayInput: IntrospectionInput {
    let weekday: Int
}

/// `{date: string|null}`: null clears the end date.
struct OptionalDateInput: IntrospectionInput {
    let date: String?
}
