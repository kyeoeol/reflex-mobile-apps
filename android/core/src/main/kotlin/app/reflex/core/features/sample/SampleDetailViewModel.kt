package app.reflex.core.features.sample

import app.reflex.core.foundation.ViewModel
import app.reflex.core.foundation.attempt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class SampleDetailViewModel(
    itemId: String,
    private val service: SampleService,
    scope: CoroutineScope,
) : ViewModel<SampleDetailViewModel.State, SampleDetailViewModel.Action, SampleDetailViewModel.Message>(State(itemId), scope) {

    data class State(
        val itemId: String,
        val title: String = "",
        val count: Int = 0,
        val isLoading: Boolean = true,
        val loadError: String? = null,
        val isSaving: Boolean = false,
        val isSaved: Boolean = false,
        val saveError: String? = null,
    ) {
        /** Derived in the ViewModel, so neither the View nor the node needs an `if`. */
        val canDecrement: Boolean get() = count > 0
    }

    sealed interface Action {
        data object Increment : Action
        data object Decrement : Action
        data class SetCount(val count: Int) : Action
        data object Save : Action
    }

    sealed interface Message {
        data class ViewAction(val action: Action) : Message
        data object Load : Message
        data class Loaded(val item: SampleItem) : Message
        data object LoadFailed : Message
        data object Saved : Message
        data object SaveFailed : Message
    }

    init {
        send(Message.Load)
    }

    override fun wrap(action: Action) = Message.ViewAction(action)

    override fun update(state: State, message: Message): State = when (message) {
        is Message.ViewAction -> when (val action = message.action) {
            Action.Increment -> edit(state, state.count + 1)
            // Business rules live only here: never below zero; no double save.
            Action.Decrement -> if (state.count > 0) edit(state, state.count - 1) else state
            is Action.SetCount -> if (action.count >= 0) edit(state, action.count) else state
            Action.Save -> if (state.isSaving) state else {
                scope.launch {
                    attempt { service.save(state.itemId, state.count) }.fold({ send(Message.Saved) }, { send(Message.SaveFailed) })
                }
                state.copy(isSaving = true, saveError = null)
            }
        }
        Message.Load -> {
            scope.launch {
                attempt { service.item(state.itemId) }.fold({ send(Message.Loaded(it)) }, { send(Message.LoadFailed) })
            }
            state
        }
        is Message.Loaded -> state.copy(title = message.item.title, count = message.item.count, isLoading = false)
        Message.LoadFailed -> state.copy(isLoading = false, loadError = "Couldn't load this item.")
        Message.Saved -> state.copy(isSaving = false, isSaved = true)
        Message.SaveFailed -> state.copy(isSaving = false, saveError = "Couldn't save. Try again.")
    }

    private fun edit(state: State, count: Int) = state.copy(count = count, isSaved = false, saveError = null)
}
