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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/**
 * A capsule toggle. On: filled, with a leading check where there is room (the 7-across weekday circles have none),
 * so it reads as a toggle, not a button. Disabled: no fill and a faint outline whatever its value, with the check
 * kept when on, so a locked "on" never looks like an active control and a locked "off" stays clearly off.
 */
@Composable
fun PillToggle(title: String, isOn: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier,
               enabled: Boolean = true, accessibilityTitle: String = title) {
    val colors = HealthTheme.colors
    val iconSize = scaledIconSize(18.dp)
    Surface(modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .toggleable(value = isOn, enabled = enabled, role = Role.Switch, onValueChange = { onToggle() })
        .semantics { contentDescription = accessibilityTitle }, shape = CircleShape,
        color = if (isOn && enabled) colors.primary else Color.Transparent,
        contentColor = if (!enabled) colors.secondary else if (isOn) colors.background else colors.primary,
        border = when {
            !enabled -> BorderStroke(1.dp, colors.strongBorder)
            isOn -> null
            else -> BorderStroke(1.dp, colors.controlBorder)
        }) {
        BoxWithConstraints(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            // Room for the check: the title plus an icon and gap, judged roughly from the label length.
            val roomForCheck = maxWidth >= iconSize + 8.dp + scaledIconSize(10.dp) * title.length
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isOn && roomForCheck) Icon(HealthIcons.Check, contentDescription = null, modifier = Modifier.size(iconSize))
                Text(title, style = HealthType.meta)
            }
        }
    }
}

@Composable
fun WeekdayPicker(selectedWeekdays: List<Int>, onToggle: (Int) -> Unit, modifier: Modifier = Modifier,
                  tagPrefix: String? = null) {
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
                            modifier = Modifier.weight(1f).tagged(tagPrefix?.let { "$it.${index + 1}" }),
                            accessibilityTitle = names[index])
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
            modifier: Modifier = Modifier, timing: String? = null, tag: String? = null) {
    // Ephemeral menu visibility is UI state. Dose permissions remain entirely supplied by Core.
    var expanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HairlineDivider()
        Box {
            // The whole label opens the menu; the trailing ellipsis says so (upcoming rows included).
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).tagged(tag)
                .clickable(role = Role.Button, onClickLabel = "Dose actions") { expanded = true }
                .semantics(mergeDescendants = true) {}.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (meta.isNotEmpty()) MetaRow(meta)
                    Text(name, style = HealthType.row, color = HealthTheme.colors.primary)
                    if (dose.isNotEmpty()) Text(dose, style = HealthType.caption, color = HealthTheme.colors.secondary)
                    StatusChip(status, modifier = Modifier.tagged(tag?.let { "$it.status" }))
                    if (timing != null) Text(timing, style = HealthType.caption, color = HealthTheme.colors.secondary)
                }
                Icon(HealthIcons.More, contentDescription = null, tint = HealthTheme.colors.secondary,
                    modifier = Modifier.size(scaledIconSize(24.dp)))
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.exposeTestTags(),
                containerColor = HealthTheme.colors.secondaryBackground,
                border = BorderStroke(1.dp, HealthTheme.colors.strongBorder)) {
                if (canTake) DropdownMenuItem(text = { Text("Take now") }, onClick = { expanded = false; onTake() },
                    modifier = Modifier.testTag("doseMenu.take"))
                if (canTakeAt) DropdownMenuItem(text = { Text("Change time…") }, onClick = { expanded = false; onTakeAt() },
                    modifier = Modifier.testTag("doseMenu.takeAt"))
                if (canSkip) DropdownMenuItem(text = { Text("Skip") }, onClick = { expanded = false; onSkip() },
                    modifier = Modifier.testTag("doseMenu.skip"))
                if (canUndo) DropdownMenuItem(text = { Text("Undo") }, onClick = { expanded = false; onUndo() },
                    modifier = Modifier.testTag("doseMenu.undo"))
            }
        }
        if (needsAction) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canTake) PillButton("Taken", onTake, modifier = Modifier.tagged(tag?.let { "$it.take" })
                .semantics { contentDescription = "Mark $name taken" })
            if (canSkip) PillButton("Skip", onSkip, kind = PillButtonKind.Outline,
                modifier = Modifier.tagged(tag?.let { "$it.skip" }).semantics { contentDescription = "Skip $name" })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AsNeededRow(name: String, dose: String, summary: String, canUndo: Boolean,
                onLog: () -> Unit, onUndo: () -> Unit, modifier: Modifier = Modifier, tag: String? = null) {
    Column(modifier.tagged(tag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HairlineDivider()
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (dose.isNotEmpty()) MetaRow(dose)
            Text(name, style = HealthType.row, color = HealthTheme.colors.primary)
            Text(summary, style = HealthType.caption, color = HealthTheme.colors.secondary,
                modifier = Modifier.tagged(tag?.let { "$it.summary" }))
        }
        FlowRow(Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Log taken", onLog, modifier = Modifier.tagged(tag?.let { "$it.log" })
                .semantics { contentDescription = "Log $name taken" })
            if (canUndo) PillButton("Undo", onUndo, kind = PillButtonKind.Outline,
                modifier = Modifier.tagged(tag?.let { "$it.undo" }).semantics { contentDescription = "Undo latest $name dose" })
        }
    }
}

@Composable
fun InlineErrorBanner(message: String, modifier: Modifier = Modifier) {
    Text(message, style = HealthType.smallBody, color = HealthTheme.colors.destructive,
        modifier = modifier.fillMaxWidth().background(HealthTheme.colors.destructiveBackground, RoundedCornerShape(16.dp))
            .padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite })
}

/** Previous · date (fills the width) · next. The circles use `strongBorder` like every labeled outline. */
@Composable
fun HistoryDayPager(dateLabel: String, canGoNext: Boolean, onPrevious: () -> Unit, onNext: () -> Unit,
                    onSelectDate: () -> Unit, modifier: Modifier = Modifier, tagPrefix: String? = null) {
    val colors = HealthTheme.colors
    val circle = Modifier.sizeIn(minWidth = scaledIconSize(48.dp), minHeight = scaledIconSize(48.dp))
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedIconButton(onPrevious, modifier = Modifier.tagged(tagPrefix?.let { "$it.previous" }).then(circle),
            border = BorderStroke(1.dp, colors.strongBorder)) {
            Icon(HealthIcons.Back, contentDescription = "Previous day", modifier = Modifier.size(scaledIconSize(24.dp)))
        }
        PillSelect("History date", dateLabel, onSelectDate, modifier = Modifier.weight(1f).tagged(tagPrefix?.let { "$it.date" }),
            fillWidth = true)
        OutlinedIconButton(onNext, enabled = canGoNext, modifier = Modifier.tagged(tagPrefix?.let { "$it.next" }).then(circle),
            border = BorderStroke(1.dp, if (canGoNext) colors.strongBorder else colors.border)) {
            Icon(HealthIcons.Next, contentDescription = "Next day", modifier = Modifier.size(scaledIconSize(24.dp)))
        }
    }
}

/** A pending reminder. Its title already says what kind it is ("Time for…" / "Still due:"). */
@Composable
fun ReminderRow(fireAtLabel: String, title: String, body: String, modifier: Modifier = Modifier) {
    ListRow(title, fireAtLabel, body, modifier = modifier)
}

/** A medication in the list: name, then "dose · kind", then the schedule. */
@Composable
fun MedicationRow(name: String, detail: String, schedule: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        HairlineDivider()
        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name, style = HealthType.row, color = HealthTheme.colors.primary)
            if (detail.isNotEmpty()) Text(detail, style = HealthType.caption, color = HealthTheme.colors.secondary)
            Text(schedule, style = HealthType.caption, color = HealthTheme.colors.body)
        }
    }
}

/** `testTag` when there is one (identifiers for UI tests; see docs/health/E2E.md). */
fun Modifier.tagged(tag: String?): Modifier = if (tag == null) this else testTag(tag)
