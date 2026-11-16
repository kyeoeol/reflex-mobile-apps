package app.reflex.core.features.health

import app.reflex.core.foundation.ViewModel
import kotlinx.coroutines.CoroutineScope

class MedicationsViewModel(
    private val store: HealthStore,
    private val navigation: HealthNavigating,
    scope: CoroutineScope,
) : ViewModel<MedicationsViewModel.State, MedicationsViewModel.Action, MedicationsViewModel.Message>(render(store.current), scope) {

    data class State(
        val isLoading: Boolean,
        val isEmpty: Boolean,
        val active: List<MedicationItem>,
        val ended: List<MedicationItem>,
        val archived: List<MedicationItem>,
    )

    data class MedicationItem(val id: String, val name: String, val kind: String, val dose: String, val schedule: String)

    sealed interface Action {
        data object Add : Action
        data class Open(val id: String) : Action
    }

    sealed interface Message {
        data class ViewAction(val action: Action) : Message
        data object StoreChanged : Message
    }

    init {
        store.observe(this, scope) { it.send(Message.StoreChanged) }
    }

    override fun wrap(action: Action) = Message.ViewAction(action)

    override fun update(state: State, message: Message): State = when (message) {
        is Message.ViewAction -> when (val action = message.action) {
            Action.Add -> {
                navigation.showMedicationEditor(null)
                state
            }
            is Action.Open -> {
                if (action.id in store.current.days.medications) navigation.showMedication(action.id)
                state
            }
        }
        Message.StoreChanged -> render(store.current)
    }

    companion object {
        fun render(snapshot: HealthSnapshot): State {
            val today = snapshot.today
            val byStatus = snapshot.data.medications
                .sortedWith(compareBy({ nameKey(it.name) }, { it.id }))
                .groupBy { it.status(today) }
            fun items(status: MedicationStatus) = byStatus[status].orEmpty().map {
                MedicationItem(it.id, it.name, it.kind, it.dose, it.scheduleSummary(today))
            }
            return State(
                isLoading = snapshot.isLoading,
                isEmpty = !snapshot.isLoading && snapshot.data.medications.isEmpty(),
                active = items(MedicationStatus.Active),
                ended = items(MedicationStatus.Ended),
                archived = items(MedicationStatus.Archived),
            )
        }
    }
}
