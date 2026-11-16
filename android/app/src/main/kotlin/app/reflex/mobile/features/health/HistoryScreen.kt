package app.reflex.mobile.features.health

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.reflex.core.features.health.DoseItem
import app.reflex.core.features.health.HistoryViewModel
import app.reflex.core.features.health.HistoryViewModel.Action
import app.reflex.mobile.designsystem.EmptyStateView
import app.reflex.mobile.designsystem.HealthTheme
import app.reflex.mobile.designsystem.HealthTopBar
import app.reflex.mobile.designsystem.HealthType
import app.reflex.mobile.designsystem.HistoryDayPager
import app.reflex.mobile.designsystem.InlineErrorBanner

/** One day at a time. The date picker has no bound of its own: Core rejects a future `SelectDate`. */
@Composable
fun HistoryScreen(vm: HistoryViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var takeAtDose by remember { mutableStateOf<DoseItem?>(null) }

    HealthScreen {
        HealthTopBar("Health Beta")
        ScreenTitle("History")
        HistoryDayPager(HealthFormat.date(state.date), state.canGoNext, onPrevious = { vm.send(Action.PreviousDay) },
            onNext = { vm.send(Action.NextDay) }, onSelectDate = { pickingDate = true }, tagPrefix = "history")
        if (state.isLoading) LoadingIndicator()
        state.summaryLabel?.let {
            Text(it, style = HealthType.meta, color = HealthTheme.colors.secondary, modifier = Modifier.testTag("history.summary"))
        }
        state.doseError?.let { InlineErrorBanner(it, Modifier.testTag("history.doseError")) }
        state.message?.let {
            EmptyStateView(it, icon = null, messageModifier = Modifier.testTag("history.message"))
        }
        if (state.doses.isNotEmpty()) Column {
            for (dose in state.doses) {
                DoseRowFor(dose, "history", onTake = { vm.send(Action.Take(it)) }, onTakeAt = { takeAtDose = dose },
                    onSkip = { vm.send(Action.Skip(it)) }, onUndo = { vm.send(Action.Undo(it)) }, showTime = true)
            }
        }
    }
    if (pickingDate) HealthDatePickerDialog(state.date, onConfirm = { vm.send(Action.SelectDate(it)) }, onDismiss = { pickingDate = false })
    takeAtDose?.let { dose ->
        TakeAtDialog(dose.date, dose.time, onConfirm = { vm.send(Action.TakeAt(dose.id, it)) }, onDismiss = { takeAtDose = null })
    }
}
