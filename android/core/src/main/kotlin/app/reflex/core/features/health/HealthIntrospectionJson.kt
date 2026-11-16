package app.reflex.core.features.health

import app.reflex.introspection.JsonValue
import kotlinx.serialization.Serializable

// Shared by the Health nodes only (the app never uses it): the dose object and the action inputs several
// screens accept. Unconditional, like the nodes themselves.

/** `{id, medicationId, name, dose, date, time, status, takenAt, timing, canTake, canSkip, canUndo}` */
internal fun DoseItem.json(): JsonValue = JsonValue.obj(
    "id" to JsonValue.Str(id),
    "medicationId" to JsonValue.Str(medicationId),
    "name" to JsonValue.Str(name),
    "dose" to JsonValue.Str(dose),
    "date" to JsonValue.Str(date),
    "time" to JsonValue.of(time),
    "status" to JsonValue.Str(status),
    "takenAt" to JsonValue.of(takenAt),
    "timing" to JsonValue.of(timing),
    "canTake" to JsonValue.Bool(canTake),
    "canSkip" to JsonValue.Bool(canSkip),
    "canUndo" to JsonValue.Bool(canUndo),
)

@Serializable
data class DoseIdInput(val doseId: String)

@Serializable
data class TakeAtInput(val doseId: String, val takenAt: String)

@Serializable
data class MedicationIdInput(val medicationId: String)

@Serializable
data class IdInput(val id: String)
