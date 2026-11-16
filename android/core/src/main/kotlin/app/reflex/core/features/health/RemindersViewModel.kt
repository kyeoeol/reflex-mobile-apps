package app.reflex.core.features.health

import app.reflex.core.foundation.NotificationPermission
import app.reflex.core.foundation.ViewModel
import kotlinx.coroutines.CoroutineScope

class RemindersViewModel(
    private val store: HealthStore,
    scope: CoroutineScope,
) : ViewModel<RemindersViewModel.State, RemindersViewModel.Action, RemindersViewModel.Message>(render(store.current), scope) {

    data class State(
        val permission: String,
        val mayBeLate: Boolean,
        val statusLabel: String,
        val reminders: List<ReminderItem>,
    )

    /** One pending notification; [fireAt] carries the offset the current zone has at that instant. */
    data class ReminderItem(val id: String, val kind: String, val fireAt: String, val title: String, val body: String)

    sealed interface Action {
        data object TurnOnReminders : Action
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
        is Message.ViewAction -> when (message.action) {
            Action.TurnOnReminders -> {
                store.turnOnReminders()
                state
            }
        }
        Message.StoreChanged -> render(store.current)
    }

    companion object {
        fun render(snapshot: HealthSnapshot): State = State(
            permission = snapshot.permission.code,
            mayBeLate = snapshot.mayBeLate,
            statusLabel = when (snapshot.permission) {
                NotificationPermission.Authorized ->
                    if (snapshot.mayBeLate) "Reminders may arrive a few minutes late" else "Reminders are on"
                NotificationPermission.Denied -> "Reminders are off. Turn them on in Settings."
                NotificationPermission.NotDetermined -> "Reminders aren't set up yet"
            },
            reminders = snapshot.pending.map {
                ReminderItem(it.id, it.kind, HealthTime.formatInstant(it.fireAt, snapshot.zone), it.title, it.body)
            },
        )
    }
}
