package app.reflex.core.features.health

import app.reflex.core.foundation.ViewModel
import kotlinx.coroutines.CoroutineScope

class MedicationEditorViewModel(
    medicationId: String?,
    private val store: HealthStore,
    private val navigation: HealthNavigating,
    scope: CoroutineScope,
) : ViewModel<MedicationEditorViewModel.State, MedicationEditorViewModel.Action, MedicationEditorViewModel.Message>(
    render(store.current, initial(store.current, medicationId)), scope,
) {

    data class State(
        val mode: String,
        val medicationId: String?,
        val name: String,
        val kind: String,
        val dose: String,
        val notes: String,
        val asNeeded: Boolean,
        val times: List<String>,
        val weekdays: List<Int>,
        val startDate: String,
        val endDate: String?,
        val nameError: String?,
        val timesError: String?,
        val weekdaysError: String?,
        val datesError: String?,
        val timeError: String?,
        val canAddTime: Boolean,
        /** Add mode only: an existing medication can't switch between scheduled and as-needed. */
        val canChangeAsNeeded: Boolean,
        /** No validation error (whether or not the errors are shown yet). */
        val isValid: Boolean,
        /** Not saving and the stored data loaded without error; Save stays tappable on an invalid form. */
        val canSave: Boolean,
        val isSaving: Boolean,
        /** The four validation errors are shown only after the first `Save` attempt. */
        val hasTriedToSave: Boolean = false,
    )

    sealed interface Action {
        data class SetName(val name: String) : Action
        data class SetKind(val kind: String) : Action
        data class SetDose(val dose: String) : Action
        data class SetNotes(val notes: String) : Action
        data class SetAsNeeded(val asNeeded: Boolean) : Action
        data class AddTime(val time: String) : Action
        data class RemoveTime(val time: String) : Action
        data class ToggleWeekday(val weekday: Int) : Action
        data class SetStartDate(val date: String) : Action
        data class SetEndDate(val date: String?) : Action
        data object Save : Action
    }

    sealed interface Message {
        data class ViewAction(val action: Action) : Message
        data object StoreChanged : Message
    }

    /** The medication this editor edits; null in add mode. (AppRoot removes the editor when it is deleted.) */
    val editedMedicationId: String? = state.value.medicationId

    init {
        store.observe(this, scope) { it.send(Message.StoreChanged) }
    }

    override fun wrap(action: Action) = Message.ViewAction(action)

    override fun update(state: State, message: Message): State = render(
        store.current,
        when (message) {
            is Message.ViewAction -> when (val action = message.action) {
                is Action.SetName -> state.copy(name = action.name)
                is Action.SetKind -> if (action.kind in Medication.KINDS) state.copy(kind = action.kind) else state
                is Action.SetDose -> state.copy(dose = action.dose)
                is Action.SetNotes -> state.copy(notes = action.notes)
                is Action.SetAsNeeded -> if (state.canChangeAsNeeded) state.copy(asNeeded = action.asNeeded) else state
                is Action.AddTime -> addTime(state, action.time)
                is Action.RemoveTime ->
                    if (action.time in state.times) state.copy(times = state.times - action.time, timeError = null) else state
                is Action.ToggleWeekday -> when {
                    action.weekday !in 1..7 -> state
                    action.weekday in state.weekdays -> state.copy(weekdays = state.weekdays - action.weekday)
                    else -> state.copy(weekdays = (state.weekdays + action.weekday).sorted())
                }
                is Action.SetStartDate ->
                    HealthTime.parseDate(action.date)?.let { state.copy(startDate = HealthTime.date(it)) } ?: state
                is Action.SetEndDate -> when (val date = action.date) {
                    null -> state.copy(endDate = null)
                    else -> HealthTime.parseDate(date)?.let { state.copy(endDate = HealthTime.date(it)) } ?: state
                }
                Action.Save -> save(state)
            }
            Message.StoreChanged -> state
        },
    )

    private fun addTime(state: State, input: String): State {
        if (state.times.size >= MAX_TIMES) return state
        val time = HealthTime.parseTime(input) ?: return state.copy(timeError = "Enter a time as HH:mm")
        val text = HealthTime.time(time)
        if (text in state.times) return state.copy(timeError = "Each time can only be added once")
        return state.copy(times = (state.times + text).sorted(), timeError = null)
    }

    /** Invalid → reveal the errors; valid → store, pop, and ask for notifications if never asked. */
    private fun save(state: State): State {
        if (!render(store.current, state).canSave) return state
        val attempted = state.copy(hasTriedToSave = true)
        if (!render(store.current, attempted).isValid) return attempted
        val draft = MedicationDraft(
            id = state.medicationId,
            name = state.name,
            kind = state.kind,
            dose = state.dose,
            notes = state.notes,
            asNeeded = state.asNeeded,
            times = if (state.asNeeded) emptyList() else state.times.mapNotNull(HealthTime::parseTime),
            weekdays = if (state.asNeeded) emptyList() else state.weekdays,
            startDate = HealthTime.parseDate(state.startDate) ?: return attempted,
            endDate = state.endDate?.let(HealthTime::parseDate),
        )
        store.saveMedication(draft) ?: return attempted
        navigation.close(this)
        if (draft.times.isNotEmpty()) store.requestPermissionIfNotDetermined()
        return attempted.copy(isSaving = true)
    }

    companion object {
        const val MAX_TIMES = 12
        private val ALL_WEEKDAYS = (1..7).toList()

        /** Add mode defaults, or the medication's values with the latest version's times/weekdays. */
        fun initial(snapshot: HealthSnapshot, medicationId: String?): State {
            val today = snapshot.today
            val medication = medicationId?.let { snapshot.days.medications[it] }
            val version = medication?.latestVersion
            return State(
                mode = if (medication == null) "add" else "edit",
                medicationId = medication?.id,
                name = medication?.name.orEmpty(),
                kind = medication?.kind ?: Medication.KIND_MEDICATION,
                dose = medication?.dose.orEmpty(),
                notes = medication?.notes.orEmpty(),
                asNeeded = medication?.asNeeded ?: false,
                times = version?.times.orEmpty().distinct().sorted().map(HealthTime::time),
                weekdays = version?.weekdays?.distinct()?.sorted() ?: ALL_WEEKDAYS,
                startDate = HealthTime.date(medication?.startDate ?: today),
                endDate = medication?.endDate?.let(HealthTime::date),
                nameError = null, timesError = null, weekdaysError = null, datesError = null, timeError = null,
                canAddTime = true, canChangeAsNeeded = medication == null, isValid = false, canSave = false,
                isSaving = false,
            )
        }

        /** Recomputes the validation fields (errors shown once a save was attempted) from the other fields. */
        fun render(snapshot: HealthSnapshot, state: State): State {
            val today = snapshot.today
            val key = duplicateKey(state.name)
            val nameError = when {
                key.isEmpty() -> "Name is required"
                snapshot.data.medications.any {
                    it.id != state.medicationId && it.status(today) == MedicationStatus.Active && duplicateKey(it.name) == key
                } -> "A medication with this name already exists"
                else -> null
            }
            val timesError = if (!state.asNeeded && state.times.isEmpty()) "Add at least one time" else null
            val weekdaysError = if (!state.asNeeded && state.weekdays.isEmpty()) "Pick at least one day" else null
            val endDate = state.endDate
            val datesError = if (endDate != null && endDate < state.startDate) "End date can't be before start date" else null
            val shown = state.hasTriedToSave
            return state.copy(
                nameError = nameError.takeIf { shown },
                timesError = timesError.takeIf { shown },
                weekdaysError = weekdaysError.takeIf { shown },
                datesError = datesError.takeIf { shown },
                canAddTime = state.times.size < MAX_TIMES,
                isValid = nameError == null && timesError == null && weekdaysError == null && datesError == null,
                canSave = !state.isSaving && snapshot.canWrite,
            )
        }
    }
}
