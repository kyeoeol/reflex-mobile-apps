package app.reflex.core.features.sample

import app.reflex.core.foundation.ViewModel
import app.reflex.core.foundation.attempt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Navigation the Sample list needs. Implemented by `AppRoot`. */
interface SampleNavigating {
    fun showSampleDetail(id: String)
}

class SampleListViewModel(
    private val service: SampleService,
    private val navigation: SampleNavigating,
    scope: CoroutineScope,
) : ViewModel<SampleListViewModel.State, SampleListViewModel.Action, SampleListViewModel.Message>(State(), scope) {

    data class State(
        val items: List<SampleItem> = emptyList(),
        val isLoading: Boolean = false,
        val loadError: String? = null,
    )

    sealed interface Action {
        data class SelectItem(val id: String) : Action
        data object Refresh : Action
    }

    sealed interface Message {
        data class ViewAction(val action: Action) : Message
        data object Load : Message
        data class Loaded(val items: List<SampleItem>) : Message
        data object LoadFailed : Message
    }

    init {
        send(Message.Load)
    }

    override fun wrap(action: Action) = Message.ViewAction(action)

    override fun update(state: State, message: Message): State = when (message) {
        is Message.ViewAction -> when (val action = message.action) {
            is Action.SelectItem -> {
                navigation.showSampleDetail(action.id)
                state
            }
            Action.Refresh -> load(state)
        }
        Message.Load -> load(state)
        is Message.Loaded -> state.copy(items = message.items, isLoading = false)
        Message.LoadFailed -> state.copy(isLoading = false, loadError = "Couldn't load items.")
    }

    private fun load(state: State): State {
        scope.launch {
            attempt { service.items() }.fold({ send(Message.Loaded(it)) }, { send(Message.LoadFailed) })
        }
        return state.copy(isLoading = true, loadError = null)
    }
}
