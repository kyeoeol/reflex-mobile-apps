package app.reflex.mobile.features.health

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.reflex.core.features.health.DoseItem
import app.reflex.core.features.health.TodayViewModel
import app.reflex.core.features.health.TodayViewModel.Action
import app.reflex.mobile.designsystem.AsNeededRow
import app.reflex.mobile.designsystem.Banner
import app.reflex.mobile.designsystem.DoseRow
import app.reflex.mobile.designsystem.EmptyStateView
import app.reflex.mobile.designsystem.HealthIcons
import app.reflex.mobile.designsystem.HealthTheme
import app.reflex.mobile.designsystem.HealthTopBar
import app.reflex.mobile.designsystem.HealthType
import app.reflex.mobile.designsystem.InlineErrorBanner
import app.reflex.mobile.designsystem.PillButton
import app.reflex.mobile.designsystem.ProgressLine
import app.reflex.mobile.designsystem.SectionHeader
import app.reflex.mobile.designsystem.scaledIconSize

@Composable
fun TodayScreen(vm: TodayViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var takeAtDose by remember { mutableStateOf<DoseItem?>(null) }

    HealthScreen {
        HealthTopBar("Health Beta", actionTitle = "Reminders", onAction = { vm.send(Action.OpenReminders) },
            actionIcon = HealthIcons.Bell, actionModifier = Modifier.testTag("today.openReminders"))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ScreenTitle("Today")
            Text(HealthFormat.dayDate(state.date), style = HealthType.smallBody, color = HealthTheme.colors.secondary,
                modifier = Modifier.testTag("today.date"))
        }
        if (state.isLoading) LoadingIndicator()
        state.progressLabel?.let { label ->
            val fraction = if (state.scheduledCount > 0) state.takenCount.toFloat() / state.scheduledCount else 0f
            ProgressLine(fraction, label, Modifier.testTag("today.progress").semantics(mergeDescendants = true) {})
        }
        state.message?.let { message -> TodayMessage(message, state.messageKind, vm) }
        state.reminderBanner?.let { banner ->
            Banner(banner, actionTitle = state.reminderBannerAction, onAction = { vm.send(Action.TurnOnReminders) },
                titleModifier = Modifier.testTag("today.banner"), actionModifier = Modifier.testTag("today.banner.action"))
        }
        state.doseError?.let { InlineErrorBanner(it, Modifier.testTag("today.doseError")) }
        for (group in state.groups) {
            Column(Modifier.testTag("today.group.${group.key}")) {
                SectionHeader(group.label, modifier = Modifier.padding(bottom = 8.dp))
                for (dose in group.doses) {
                    DoseRowFor(dose, "today", onTake = { vm.send(Action.Take(it)) }, onTakeAt = { takeAtDose = dose },
                        onSkip = { vm.send(Action.Skip(it)) }, onUndo = { vm.send(Action.Undo(it)) }, showTime = false)
                }
            }
        }
        if (state.asNeeded.isNotEmpty()) Column {
            SectionHeader("As needed", modifier = Modifier.padding(bottom = 8.dp))
            for (item in state.asNeeded) {
                AsNeededRow(item.name, item.dose, item.summary, item.canUndo,
                    onLog = { vm.send(Action.LogAsNeeded(item.medicationId)) },
                    onUndo = { vm.send(Action.UndoAsNeeded(item.medicationId)) },
                    tag = "today.asNeeded.${item.medicationId}")
            }
        }
    }
    takeAtDose?.let { dose ->
        TakeAtDialog(dose.date, dose.time, onConfirm = { vm.send(Action.TakeAt(dose.id, it)) }, onDismiss = { takeAtDose = null })
    }
}

/** The message panel: its treatment and button come from `messageKind`, its text is the literal `message`. */
@Composable
private fun TodayMessage(message: String, kind: String?, vm: TodayViewModel) {
    val messageTag = Modifier.testTag("today.message")
    when (kind) {
        "loadError" -> EmptyStateView(message, icon = HealthIcons.Warning, actionTitle = "Retry",
            onAction = { vm.send(Action.Retry) }, messageModifier = messageTag, actionModifier = Modifier.testTag("today.retry"),
            messageColor = HealthTheme.colors.destructive)
        "saveError" -> Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(HealthTheme.colors.destructiveBackground).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(HealthIcons.Warning, contentDescription = null, tint = HealthTheme.colors.destructive,
                    modifier = Modifier.size(scaledIconSize(20.dp)))
                Text(message, style = HealthType.row, color = HealthTheme.colors.destructive,
                    modifier = messageTag.semantics { liveRegion = LiveRegionMode.Polite })
            }
            PillButton("Retry", { vm.send(Action.Retry) }, modifier = Modifier.testTag("today.retry"))
        }
        "noMedications" -> EmptyStateView(message, icon = HealthIcons.Pill, actionTitle = "Add medication",
            onAction = { vm.send(Action.AddMedication) }, messageModifier = messageTag,
            actionModifier = Modifier.testTag("today.addMedication"))
        "nothingToday" -> EmptyStateView(message, icon = HealthIcons.Calendar, messageModifier = messageTag)
        else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (kind == "allDone") Icon(HealthIcons.Check, contentDescription = null, tint = HealthTheme.colors.success,
                modifier = Modifier.size(scaledIconSize(20.dp)))
            Text(message, style = HealthType.row, color = HealthTheme.colors.primary,
                modifier = messageTag.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

/** A dose object bound to [DoseRow]: every flag comes from state; the ids are `<prefix>.dose.<doseId>…`. */
@Composable
fun DoseRowFor(dose: DoseItem, prefix: String, onTake: (String) -> Unit, onTakeAt: () -> Unit, onSkip: (String) -> Unit,
               onUndo: (String) -> Unit, showTime: Boolean) {
    // Today's rows sit under their group's label, so only History repeats the scheduled time.
    val meta = if (showTime) dose.time.orEmpty() else ""
    val taken = listOfNotNull(dose.takenAt?.let { "Taken at $it" }, HealthFormat.timing(dose.timing)).joinToString(" · ")
    DoseRow(dose.name, dose.dose, meta, HealthFormat.status(dose.status), dose.needsAction, dose.canTake, dose.canTakeAt,
        dose.canSkip, dose.canUndo, onTake = { onTake(dose.id) }, onTakeAt = onTakeAt, onSkip = { onSkip(dose.id) },
        onUndo = { onUndo(dose.id) }, timing = taken.ifEmpty { null }, tag = "$prefix.dose.${dose.id}")
}
