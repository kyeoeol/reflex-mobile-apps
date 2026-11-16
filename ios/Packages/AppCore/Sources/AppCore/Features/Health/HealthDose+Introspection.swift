import IntrospectionKit

// Shared by the Health nodes (Today, History, MedicationDetail): the dose object and the dose action inputs.

extension DoseItem {
    /// `{id, medicationId, name, dose, date, time, status, takenAt, timing, canTake, canSkip, canUndo}`
    var introspectionJSON: JSONValue {
        [
            "id": .string(id),
            "medicationId": .string(medicationId),
            "name": .string(name),
            "dose": .string(dose),
            "date": .string(date),
            "time": JSONValue(time),
            "status": .string(status),
            "takenAt": JSONValue(takenAt),
            "timing": JSONValue(timing),
            "canTake": .bool(canTake),
            "canSkip": .bool(canSkip),
            "canUndo": .bool(canUndo),
        ]
    }
}

struct DoseIdInput: IntrospectionInput {
    let doseId: String
}

struct TakeAtInput: IntrospectionInput {
    let doseId: String
    let takenAt: String
}

/// `{id}`: OpenMedication (Today) and Open (Medications).
struct IdInput: IntrospectionInput {
    let id: String
}

/// `{date}`: History.SelectDate and MedicationEditor.SetStartDate.
struct DateInput: IntrospectionInput {
    let date: String
}
