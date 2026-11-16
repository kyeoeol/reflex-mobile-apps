package app.reflex.core.features.health

import app.reflex.core.features.health.RemindersViewModel.Action
import app.reflex.introspection.IntrospectionAction
import app.reflex.introspection.IntrospectionNode
import app.reflex.introspection.JsonValue

class RemindersViewModelIntrospectionNode(private val viewModel: RemindersViewModel) : IntrospectionNode {
    override val name = "Reminders"

    override val state: Map<String, JsonValue>
        get() = viewModel.state.value.let {
            linkedMapOf(
                "permission" to JsonValue.Str(it.permission),
                "mayBeLate" to JsonValue.Bool(it.mayBeLate),
                "statusLabel" to JsonValue.Str(it.statusLabel),
                "reminders" to JsonValue.Arr(it.reminders.map { reminder ->
                    JsonValue.obj(
                        "id" to JsonValue.Str(reminder.id),
                        "kind" to JsonValue.Str(reminder.kind),
                        "fireAt" to JsonValue.Str(reminder.fireAt),
                        "title" to JsonValue.Str(reminder.title),
                        "body" to JsonValue.Str(reminder.body),
                    )
                }),
            )
        }

    override val actions
        get() = listOf(
            IntrospectionAction("TurnOnReminders") { viewModel.send(Action.TurnOnReminders) },
        )

    override val child: IntrospectionNode? = null
}
