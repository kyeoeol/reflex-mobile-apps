package app.reflex.core.features.health

import app.reflex.core.features.health.TodayViewModel.Action
import app.reflex.introspection.IntrospectionAction
import app.reflex.introspection.IntrospectionNode
import app.reflex.introspection.JsonValue

class TodayViewModelIntrospectionNode(private val viewModel: TodayViewModel) : IntrospectionNode {
    override val name = "Today"

    override val state: Map<String, JsonValue>
        get() = viewModel.state.value.let {
            linkedMapOf(
                "isLoading" to JsonValue.Bool(it.isLoading),
                "date" to JsonValue.Str(it.date),
                "progressLabel" to JsonValue.of(it.progressLabel),
                "takenCount" to JsonValue.Num(it.takenCount),
                "scheduledCount" to JsonValue.Num(it.scheduledCount),
                "message" to JsonValue.of(it.message),
                "reminderBanner" to JsonValue.of(it.reminderBanner),
                "groups" to JsonValue.Arr(it.groups.map { group ->
                    JsonValue.obj(
                        "key" to JsonValue.Str(group.key),
                        "date" to JsonValue.Str(group.date),
                        "time" to JsonValue.Str(group.time),
                        "doses" to JsonValue.Arr(group.doses.map(DoseItem::json)),
                    )
                }),
                "asNeeded" to JsonValue.Arr(it.asNeeded.map { item ->
                    JsonValue.obj(
                        "medicationId" to JsonValue.Str(item.medicationId),
                        "name" to JsonValue.Str(item.name),
                        "dose" to JsonValue.Str(item.dose),
                        "takenToday" to JsonValue.Num(item.takenToday),
                        "lastTakenAt" to JsonValue.of(item.lastTakenAt),
                        "summary" to JsonValue.Str(item.summary),
                    )
                }),
                "doseError" to JsonValue.of(it.doseError),
            )
        }

    override val actions
        get() = listOf(
            IntrospectionAction("Take", DoseIdInput.serializer()) { viewModel.send(Action.Take(it.doseId)) },
            IntrospectionAction("TakeAt", TakeAtInput.serializer()) { viewModel.send(Action.TakeAt(it.doseId, it.takenAt)) },
            IntrospectionAction("Skip", DoseIdInput.serializer()) { viewModel.send(Action.Skip(it.doseId)) },
            IntrospectionAction("Undo", DoseIdInput.serializer()) { viewModel.send(Action.Undo(it.doseId)) },
            IntrospectionAction("LogAsNeeded", MedicationIdInput.serializer()) { viewModel.send(Action.LogAsNeeded(it.medicationId)) },
            IntrospectionAction("UndoAsNeeded", MedicationIdInput.serializer()) { viewModel.send(Action.UndoAsNeeded(it.medicationId)) },
            IntrospectionAction("TurnOnReminders") { viewModel.send(Action.TurnOnReminders) },
            IntrospectionAction("OpenReminders") { viewModel.send(Action.OpenReminders) },
            IntrospectionAction("AddMedication") { viewModel.send(Action.AddMedication) },
            IntrospectionAction("OpenMedication", IdInput.serializer()) { viewModel.send(Action.OpenMedication(it.id)) },
        )

    override val child: IntrospectionNode? = null
}
