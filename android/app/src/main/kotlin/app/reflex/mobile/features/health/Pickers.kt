package app.reflex.mobile.features.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.reflex.mobile.designsystem.DialogShape
import app.reflex.mobile.designsystem.HealthTheme
import app.reflex.mobile.designsystem.HealthType
import app.reflex.mobile.designsystem.PillButton
import app.reflex.mobile.designsystem.PillButtonKind
import app.reflex.mobile.designsystem.PillSelect
import app.reflex.mobile.designsystem.dialogOutline
import app.reflex.mobile.designsystem.exposeTestTags
import java.time.LocalDate
import java.time.LocalTime

// Host-owned pickers. They only collect a value and hand Core a payload on commit (`HH:mm`, `yyyy-MM-dd`,
// `yyyy-MM-ddTHH:mm`); cancelling sends nothing. Core validates every value; views have no clock, so every picker
// starts from a state value (or the editor's fixed 08:00).

/** Material date picker for a calendar date. [initial] is a `yyyy-MM-dd` state value. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthDatePickerDialog(initial: String?, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val initialDate = initial?.let(HealthFormat::calendarDate)
    val state = rememberDatePickerState(initialSelectedDateMillis = initialDate?.let(HealthFormat::pickerMillis))
    val colors = HealthTheme.colors
    DatePickerDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.exposeTestTags().dialogOutline(),
        confirmButton = {
            PillButton("OK", {
                state.selectedDateMillis?.let { onConfirm(HealthFormat.payloadDate(HealthFormat.pickerDate(it))) }
                onDismiss()
            }, modifier = Modifier.testTag("datePicker.confirm"))
        },
        dismissButton = { PillButton("Cancel", onDismiss, modifier = Modifier.testTag("datePicker.cancel"), kind = PillButtonKind.Outline) },
        shape = DialogShape,
        colors = DatePickerDefaults.colors(containerColor = colors.secondaryBackground),
    ) {
        DatePicker(state, colors = DatePickerDefaults.colors(containerColor = colors.secondaryBackground,
            selectedDayContainerColor = colors.primary, selectedDayContentColor = colors.background,
            todayDateBorderColor = colors.primary, todayContentColor = colors.primary))
    }
}

/** Hour and minute fields (24 h, the Material time-input layout), tagged `<tagPrefix>.hour` / `.minute`. */
@Composable
fun TimeFields(hour: String, minute: String, onHour: (String) -> Unit, onMinute: (String) -> Unit, tagPrefix: String,
               modifier: Modifier = Modifier) {
    Row(modifier.testTag(tagPrefix), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TimeField(hour, onHour, "Hour", Modifier.testTag("$tagPrefix.hour"), ImeAction.Next)
        Text(":", style = HealthType.display, color = HealthTheme.colors.primary)
        TimeField(minute, onMinute, "Minute", Modifier.testTag("$tagPrefix.minute"), ImeAction.Done)
    }
}

@Composable
private fun TimeField(value: String, onValue: (String) -> Unit, label: String, modifier: Modifier, imeAction: ImeAction) {
    val colors = HealthTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { text -> onValue(text.filter(Char::isDigit).take(2)) },
            modifier = modifier.width(96.dp).semantics { contentDescription = label },
            textStyle = HealthType.display.copy(textAlign = TextAlign.Center, color = colors.primary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = imeAction),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                // The fields sit on the dialog's raised surface: the canvas colour keeps them distinct from it.
                focusedContainerColor = colors.background, unfocusedContainerColor = colors.background,
                focusedBorderColor = colors.primary, unfocusedBorderColor = colors.controlBorder, cursorColor = colors.primary,
            ),
        )
        Text(label, style = HealthType.caption, color = HealthTheme.colors.secondary)
    }
}

/** `HH:mm` from the two fields; an empty field stays empty, so Core reports the malformed time. */
private fun timePayload(hour: String, minute: String): String {
    fun pad(digits: String) = if (digits.length == 1) "0$digits" else digits
    return "${pad(hour)}:${pad(minute)}"
}

/** The editor's Add time dialog. Starts at a fixed 08:00 (views have no clock). */
@Composable
fun TimeEntryDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var hour by remember { mutableStateOf("08") }
    var minute by remember { mutableStateOf("00") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.exposeTestTags().dialogOutline(),
        title = { Text("Add time", style = HealthType.section) },
        text = { TimeFields(hour, minute, { hour = it }, { minute = it }, tagPrefix = "editor.timePicker") },
        confirmButton = {
            PillButton("Add", { onConfirm(timePayload(hour, minute)); onDismiss() }, modifier = Modifier.testTag("editor.timePicker.confirm"))
        },
        dismissButton = {
            PillButton("Cancel", onDismiss, modifier = Modifier.testTag("editor.timePicker.cancel"), kind = PillButtonKind.Outline)
        },
        containerColor = HealthTheme.colors.secondaryBackground,
        tonalElevation = 0.dp,
        shape = DialogShape,
    )
}

/** "Change time…": the taken date and time, starting at the dose's own date and time. */
@Composable
fun TakeAtDialog(date: String, time: String?, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val start = time?.let(HealthFormat::time) ?: LocalTime.of(8, 0)
    var day by remember { mutableStateOf(HealthFormat.calendarDate(date) ?: LocalDate.of(2000, 1, 1)) }
    var hour by remember { mutableStateOf(HealthFormat.payloadTime(start.hour, start.minute).substring(0, 2)) }
    var minute by remember { mutableStateOf(HealthFormat.payloadTime(start.hour, start.minute).substring(3, 5)) }
    var pickingDate by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.exposeTestTags().dialogOutline(),
        title = { Text("Change time", style = HealthType.section) },
        text = {
            Column(Modifier.testTag("takeAt.picker"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PillSelect("Taken on", HealthFormat.date(HealthFormat.payloadDate(day)), { pickingDate = true },
                    modifier = Modifier.testTag("takeAt.date"), fillWidth = true)
                TimeFields(hour, minute, { hour = it }, { minute = it }, tagPrefix = "takeAt.time")
            }
        },
        confirmButton = {
            PillButton("Save", {
                onConfirm("${HealthFormat.payloadDate(day)}T${timePayload(hour, minute)}")
                onDismiss()
            }, modifier = Modifier.testTag("takeAt.save"))
        },
        dismissButton = { PillButton("Cancel", onDismiss, modifier = Modifier.testTag("takeAt.cancel"), kind = PillButtonKind.Outline) },
        containerColor = HealthTheme.colors.secondaryBackground,
        tonalElevation = 0.dp,
        shape = DialogShape,
    )
    if (pickingDate) HealthDatePickerDialog(HealthFormat.payloadDate(day), onConfirm = { picked ->
        HealthFormat.calendarDate(picked)?.let { day = it }
    }, onDismiss = { pickingDate = false })
}
