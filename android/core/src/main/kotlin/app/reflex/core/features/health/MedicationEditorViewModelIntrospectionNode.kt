package app.reflex.core.features.health

import app.reflex.core.features.health.MedicationEditorViewModel.Action
import app.reflex.introspection.IntrospectionAction
import app.reflex.introspection.IntrospectionNode
import app.reflex.introspection.JsonValue
import kotlinx.serialization.Serializable

class MedicationEditorViewModelIntrospectionNode(private val viewModel: MedicationEditorViewModel) : IntrospectionNode {
    override val name = "MedicationEditor"

    override val state: Map<String, JsonValue>
        get() = viewModel.state.value.let {
            linkedMapOf(
                "mode" to JsonValue.Str(it.mode),
                "medicationId" to JsonValue.of(it.medicationId),
                "name" to JsonValue.Str(it.name),
                "kind" to JsonValue.Str(it.kind),
                "dose" to JsonValue.Str(it.dose),
                "notes" to JsonValue.Str(it.notes),
                "asNeeded" to JsonValue.Bool(it.asNeeded),
                "times" to JsonValue.Arr(it.times.map { time -> JsonValue.Str(time) }),
                "weekdays" to JsonValue.Arr(it.weekdays.map { weekday -> JsonValue.Num(weekday) }),
                "startDate" to JsonValue.Str(it.startDate),
                "endDate" to JsonValue.of(it.endDate),
                "nameError" to JsonValue.of(it.nameError),
                "timesError" to JsonValue.of(it.timesError),
                "weekdaysError" to JsonValue.of(it.weekdaysError),
                "datesError" to JsonValue.of(it.datesError),
                "timeError" to JsonValue.of(it.timeError),
                "canAddTime" to JsonValue.Bool(it.canAddTime),
                "canSave" to JsonValue.Bool(it.canSave),
                "isSaving" to JsonValue.Bool(it.isSaving),
            )
        }

    override val actions
        get() = listOf(
            IntrospectionAction("SetName", SetNameInput.serializer()) { viewModel.send(Action.SetName(it.name)) },
            IntrospectionAction("SetKind", SetKindInput.serializer()) { viewModel.send(Action.SetKind(it.kind)) },
            IntrospectionAction("SetDose", SetDoseInput.serializer()) { viewModel.send(Action.SetDose(it.dose)) },
            IntrospectionAction("SetNotes", SetNotesInput.serializer()) { viewModel.send(Action.SetNotes(it.notes)) },
            IntrospectionAction("SetAsNeeded", SetAsNeededInput.serializer()) { viewModel.send(Action.SetAsNeeded(it.asNeeded)) },
            IntrospectionAction("AddTime", TimeInput.serializer()) { viewModel.send(Action.AddTime(it.time)) },
            IntrospectionAction("RemoveTime", TimeInput.serializer()) { viewModel.send(Action.RemoveTime(it.time)) },
            IntrospectionAction("ToggleWeekday", ToggleWeekdayInput.serializer()) { viewModel.send(Action.ToggleWeekday(it.weekday)) },
            IntrospectionAction("SetStartDate", SetStartDateInput.serializer()) { viewModel.send(Action.SetStartDate(it.date)) },
            IntrospectionAction("SetEndDate", SetEndDateInput.serializer()) { viewModel.send(Action.SetEndDate(it.date)) },
            IntrospectionAction("Save") { viewModel.send(Action.Save) },
        )

    override val child: IntrospectionNode? = null
}

@Serializable data class SetNameInput(val name: String)
@Serializable data class SetKindInput(val kind: String)
@Serializable data class SetDoseInput(val dose: String)
@Serializable data class SetNotesInput(val notes: String)
@Serializable data class SetAsNeededInput(val asNeeded: Boolean)
@Serializable data class TimeInput(val time: String)
@Serializable data class ToggleWeekdayInput(val weekday: Int)
@Serializable data class SetStartDateInput(val date: String)

/** `date: null` clears the end date. */
@Serializable data class SetEndDateInput(val date: String?)
