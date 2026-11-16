package app.reflex.mobile.features.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.reflex.core.features.health.MedicationEditorViewModel
import app.reflex.core.features.health.MedicationEditorViewModel.Action
import app.reflex.mobile.designsystem.HairlineDivider
import app.reflex.mobile.designsystem.HealthIcons
import app.reflex.mobile.designsystem.HealthSpacing
import app.reflex.mobile.designsystem.HealthTextField
import app.reflex.mobile.designsystem.HealthTheme
import app.reflex.mobile.designsystem.HealthTopBar
import app.reflex.mobile.designsystem.HealthType
import app.reflex.mobile.designsystem.PillButton
import app.reflex.mobile.designsystem.PillButtonKind
import app.reflex.mobile.designsystem.PillSegmentedControl
import app.reflex.mobile.designsystem.PillSelect
import app.reflex.mobile.designsystem.PillToggle
import app.reflex.mobile.designsystem.WeekdayPicker

private val KINDS = listOf("medication" to "Medication", "supplement" to "Supplement")

private enum class EditorPicker { Time, StartDate, EndDate }

/**
 * Full-screen form. Every value and error is state; every edit is an action. `isValid` drives nothing visible;
 * Save is enabled by `canSave` alone, so a pristine form can be saved to reveal the errors.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MedicationEditorScreen(vm: MedicationEditorViewModel, onCancel: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var picker by rememberSaveable { mutableStateOf<EditorPicker?>(null) }
    val colors = HealthTheme.colors

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = HealthSpacing.screen),
            verticalArrangement = Arrangement.spacedBy(HealthSpacing.lg),
        ) {
            HealthTopBar(if (state.mode == "edit") "Edit medication" else "Add medication", backTitle = "Cancel",
                onBack = onCancel, backModifier = Modifier.testTag("editor.cancel"))

            BufferedTextField("Name", state.name, { vm.state.value.name }, { vm.send(Action.SetName(it)) },
                error = state.nameError, tag = "editor.name", errorTag = "editor.error.name")

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Kind", style = HealthType.meta, color = colors.primary)
                PillSegmentedControl(KINDS.map { it.second }, KINDS.indexOfFirst { it.first == state.kind },
                    onSelect = { vm.send(Action.SetKind(KINDS[it].first)) },
                    itemModifier = { Modifier.testTag("editor.kind.${KINDS[it].first}") })
            }

            BufferedTextField("Dose", state.dose, { vm.state.value.dose }, { vm.send(Action.SetDose(it)) },
                placeholder = "e.g. 500 mg", tag = "editor.dose")

            PillToggle("As needed", state.asNeeded, { vm.send(Action.SetAsNeeded(!state.asNeeded)) },
                modifier = Modifier.testTag("editor.asNeeded"), enabled = state.canChangeAsNeeded)

            if (!state.asNeeded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Times", style = HealthType.meta, color = colors.primary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (time in state.times) {
                            Row(Modifier.testTag("editor.time.$time").semantics(mergeDescendants = true) {},
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(time, style = HealthType.row, color = colors.primary, modifier = Modifier.padding(start = 4.dp))
                                OutlinedIconButton({ vm.send(Action.RemoveTime(time)) },
                                    modifier = Modifier.testTag("editor.time.$time.remove").sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                                    Icon(HealthIcons.Close, contentDescription = "Remove $time", modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                    PillButton("Add time", { picker = EditorPicker.Time }, modifier = Modifier.testTag("editor.addTime"),
                        kind = PillButtonKind.Outline, enabled = state.canAddTime, icon = HealthIcons.Plus)
                    state.timesError?.let { ErrorCaption(it, "editor.error.times") }
                    state.timeError?.let { ErrorCaption(it, "editor.error.time") }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Days", style = HealthType.meta, color = colors.primary)
                    WeekdayPicker(state.weekdays, { vm.send(Action.ToggleWeekday(it)) }, tagPrefix = "editor.weekday")
                    state.weekdaysError?.let { ErrorCaption(it, "editor.error.weekdays") }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Start date", style = HealthType.meta, color = colors.primary)
                PillSelect("Start date", HealthFormat.date(state.startDate), { picker = EditorPicker.StartDate },
                    modifier = Modifier.testTag("editor.startDate"))
                Text("End date", style = HealthType.meta, color = colors.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillSelect("End date", state.endDate?.let(HealthFormat::date) ?: "No end date", { picker = EditorPicker.EndDate },
                        modifier = Modifier.testTag("editor.endDate"))
                    if (state.endDate != null) PillButton("Clear", { vm.send(Action.SetEndDate(null)) },
                        modifier = Modifier.testTag("editor.endDate.clear").semantics { contentDescription = "Clear end date" },
                        kind = PillButtonKind.Outline)
                }
                state.datesError?.let { ErrorCaption(it, "editor.error.dates") }
            }

            BufferedTextField("Notes", state.notes, { vm.state.value.notes }, { vm.send(Action.SetNotes(it)) },
                singleLine = false, tag = "editor.notes")
        }

        // Persistent, keyboard-aware Save.
        Column(Modifier.fillMaxWidth().imePadding()) {
            HairlineDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = HealthSpacing.screen, vertical = HealthSpacing.sm),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Save", { vm.send(Action.Save) }, modifier = Modifier.weight(1f).testTag("editor.save"),
                    enabled = state.canSave)
                if (state.isSaving) CircularProgressIndicator(color = colors.primary,
                    modifier = Modifier.size(24.dp).semantics { contentDescription = "Saving" })
            }
        }
    }

    when (picker) {
        EditorPicker.Time -> TimeEntryDialog(onConfirm = { vm.send(Action.AddTime(it)) }, onDismiss = { picker = null })
        EditorPicker.StartDate -> HealthDatePickerDialog(state.startDate, onConfirm = { vm.send(Action.SetStartDate(it)) },
            onDismiss = { picker = null })
        EditorPicker.EndDate -> HealthDatePickerDialog(state.endDate ?: state.startDate, onConfirm = { vm.send(Action.SetEndDate(it)) },
            onDismiss = { picker = null })
        null -> Unit
    }
}

@Composable
private fun ErrorCaption(text: String, tag: String) {
    Text(text, style = HealthType.caption, color = HealthTheme.colors.destructive,
        modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite })
}

/**
 * A text field with a local edit buffer: every edit is sent as it happens, and the buffer re-syncs from state only
 * when Core's current value differs from the last value sent (read live, so a lagging recomposition can't drop
 * characters or break an IME composition such as Hangul).
 */
@Composable
private fun BufferedTextField(label: String, value: String, current: () -> String, onChange: (String) -> Unit,
                              placeholder: String = "", error: String? = null, singleLine: Boolean = true,
                              tag: String, errorTag: String? = null) {
    var buffer by remember { mutableStateOf(value) }
    var lastSent by remember { mutableStateOf(value) }
    if (value != lastSent && current() != lastSent) {
        buffer = value
        lastSent = value
    }
    HealthTextField(label, buffer, { text ->
        buffer = text
        if (text != lastSent) {
            lastSent = text
            onChange(text)
        }
    }, placeholder = placeholder, error = error, singleLine = singleLine,
        fieldModifier = Modifier.testTag(tag), errorModifier = errorTag?.let { Modifier.testTag(it) } ?: Modifier)
}
