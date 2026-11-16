package app.reflex.mobile.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable
fun PillToggle(title: String, isOn: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier,
               enabled: Boolean = true, accessibilityTitle: String = title) {
    val colors = HealthTheme.colors
    Surface(modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .toggleable(value = isOn, enabled = enabled, role = Role.Switch, onValueChange = { onToggle() })
        .semantics { contentDescription = accessibilityTitle }, shape = CircleShape,
        color = if (isOn) colors.primary else Color.Transparent,
        contentColor = if (!enabled) colors.secondary else if (isOn) colors.background else colors.primary,
        border = if (isOn) null else BorderStroke(1.dp, colors.controlBorder)) {
        Box(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(title, style = HealthType.meta)
        }
    }
}

@Composable
fun WeekdayPicker(selectedWeekdays: List<Int>, onToggle: (Int) -> Unit, modifier: Modifier = Modifier) {
    val labels = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    val names = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    val scaledMinimum = 48.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(modifier.fillMaxWidth().semantics { contentDescription = "Days" }) {
        val columns = ((maxWidth + 4.dp) / (scaledMinimum + 4.dp)).toInt().coerceIn(1, 7)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.indices.toList().chunked(columns).forEach { indices ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    indices.forEach { index ->
                        PillToggle(labels[index], selectedWeekdays.contains(index + 1), { onToggle(index + 1) },
                            modifier = Modifier.weight(1f), accessibilityTitle = names[index])
                    }
                    repeat(columns - indices.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DoseRow(name: String, dose: String, meta: String, status: DoseStatusKind,
            needsAction: Boolean, canTake: Boolean, canTakeAt: Boolean, canSkip: Boolean, canUndo: Boolean,
            onTake: () -> Unit, onTakeAt: () -> Unit, onSkip: () -> Unit, onUndo: () -> Unit,
            modifier: Modifier = Modifier, timing: String? = null) {
    // Ephemeral menu visibility is UI state. Dose permissions remain entirely supplied by Core.
    var expanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HairlineDivider()
        Box {
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = "Dose actions") { expanded = true }
                .semantics(mergeDescendants = true) {}.padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MetaRow(meta)
                Text(name, style = HealthType.row, color = HealthTheme.colors.primary)
                if (dose.isNotEmpty()) Text(dose, style = HealthType.caption, color = HealthTheme.colors.secondary)
                StatusChip(status)
                if (timing != null) Text(timing, style = HealthType.caption, color = HealthTheme.colors.secondary)
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }, containerColor = HealthTheme.colors.background) {
                if (canTake) DropdownMenuItem(text = { Text("Take now") }, onClick = { expanded = false; onTake() })
                if (canTakeAt) DropdownMenuItem(text = { Text("Change time…") }, onClick = { expanded = false; onTakeAt() })
                if (canSkip) DropdownMenuItem(text = { Text("Skip") }, onClick = { expanded = false; onSkip() })
                if (canUndo) DropdownMenuItem(text = { Text("Undo") }, onClick = { expanded = false; onUndo() })
            }
        }
        if (needsAction) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canTake) PillButton("Taken", onTake, modifier = Modifier.semantics { contentDescription = "Mark $name taken" })
            if (canSkip) PillButton("Skip", onSkip, kind = PillButtonKind.Outline,
                modifier = Modifier.semantics { contentDescription = "Skip $name" })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AsNeededRow(name: String, dose: String, summary: String, canUndo: Boolean,
                onLog: () -> Unit, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ListRow(name, dose, summary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Log taken", onLog, modifier = Modifier.semantics { contentDescription = "Log $name taken" })
            if (canUndo) PillButton("Undo", onUndo, kind = PillButtonKind.Outline,
                modifier = Modifier.semantics { contentDescription = "Undo latest $name dose" })
        }
    }
}

@Composable
fun InlineErrorBanner(message: String, modifier: Modifier = Modifier) {
    Text(message, style = HealthType.smallBody, color = HealthTheme.colors.destructive,
        modifier = modifier.fillMaxWidth().background(HealthTheme.colors.destructiveBackground, RoundedCornerShape(16.dp))
            .padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryDayPager(dateLabel: String, canGoNext: Boolean, onPrevious: () -> Unit, onNext: () -> Unit,
                    onSelectDate: () -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedIconButton(onPrevious, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
            Icon(HealthIcons.Back, contentDescription = "Previous day")
        }
        PillSelect("History date", dateLabel, onSelectDate)
        OutlinedIconButton(onNext, enabled = canGoNext, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
            Icon(HealthIcons.Next, contentDescription = "Next day")
        }
    }
}

@Composable
fun ReminderRow(kind: String, fireAtLabel: String, title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ListRow(title, fireAtLabel, body)
        Text(kind, style = HealthType.caption, color = HealthTheme.colors.secondary)
    }
}
