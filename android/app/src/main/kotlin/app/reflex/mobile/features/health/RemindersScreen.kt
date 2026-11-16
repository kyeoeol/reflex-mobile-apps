package app.reflex.mobile.features.health

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.reflex.core.features.health.RemindersViewModel
import app.reflex.core.features.health.RemindersViewModel.Action
import app.reflex.mobile.designsystem.HealthTheme
import app.reflex.mobile.designsystem.HealthTopBar
import app.reflex.mobile.designsystem.HealthType
import app.reflex.mobile.designsystem.PillButton
import app.reflex.mobile.designsystem.PillButtonKind
import app.reflex.mobile.designsystem.ReminderRow

/** The scheduler's pending list as Core read it back. Turn on reminders stays available; Core decides what it does. */
@Composable
fun RemindersScreen(vm: RemindersViewModel, backTitle: String, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    HealthScreen {
        HealthTopBar("", backTitle = backTitle, onBack = onBack)
        ScreenTitle("Reminders")
        Text(state.statusLabel, style = HealthType.body, color = HealthTheme.colors.body, modifier = Modifier.testTag("reminders.status"))
        state.actionLabel?.let { label ->
            PillButton(label, { vm.send(Action.TurnOnReminders) }, modifier = Modifier.testTag("reminders.turnOn"),
                kind = PillButtonKind.Outline)
        }
        if (state.reminders.isNotEmpty()) Column {
            for (reminder in state.reminders) {
                ReminderRow(reminder.kind, HealthFormat.reminderTime(reminder.fireAt), reminder.title, reminder.body,
                    modifier = Modifier.testTag("reminders.item.${reminder.id}").padding(bottom = 8.dp))
            }
        }
    }
}
