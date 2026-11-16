package app.reflex.mobile.features.health

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.reflex.core.features.health.RemindersViewModel
import app.reflex.core.features.health.RemindersViewModel.Action
import app.reflex.mobile.designsystem.Banner
import app.reflex.mobile.designsystem.HealthTopBar
import app.reflex.mobile.designsystem.ReminderRow

/** The scheduler's pending list as Core read it back. The action button exists only while `actionLabel` names one. */
@Composable
fun RemindersScreen(vm: RemindersViewModel, backTitle: String, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    HealthScreen {
        HealthTopBar("", backTitle = backTitle, onBack = onBack)
        ScreenTitle("Reminders")
        // The status and its action share one panel (the button only when Core names one).
        Banner(state.statusLabel, actionTitle = state.actionLabel, onAction = { vm.send(Action.TurnOnReminders) },
            titleModifier = Modifier.testTag("reminders.status"), actionModifier = Modifier.testTag("reminders.turnOn"))
        if (state.reminders.isNotEmpty()) Column {
            for (reminder in state.reminders) {
                ReminderRow(HealthFormat.reminderTime(reminder.fireAt), reminder.title, reminder.body,
                    modifier = Modifier.testTag("reminders.item.${reminder.id}").padding(bottom = 8.dp))
            }
        }
    }
}
