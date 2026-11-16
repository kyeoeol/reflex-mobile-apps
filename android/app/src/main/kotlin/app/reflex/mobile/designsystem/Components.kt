package app.reflex.mobile.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// All values and callbacks come from the host. No feature, Core, or ViewModel dependencies.
enum class PillButtonKind { Primary, Outline, Destructive }
enum class DoseStatusKind(val label: String, val glyph: String) {
    Upcoming("Upcoming", "◷"), Due("Due", "●"), Taken("Taken", "✓"), Skipped("Skipped", "−"), Missed("Missed", "!")
}

@Composable
fun PillButton(title: String, onClick: () -> Unit, modifier: Modifier = Modifier,
               kind: PillButtonKind = PillButtonKind.Primary, enabled: Boolean = true) {
    val colors = HealthTheme.colors
    val background = when (kind) {
        PillButtonKind.Primary -> colors.primary
        PillButtonKind.Outline -> Color.Transparent
        PillButtonKind.Destructive -> colors.destructiveBackground
    }
    val foreground = when (kind) {
        PillButtonKind.Primary -> colors.background
        PillButtonKind.Outline -> colors.primary
        PillButtonKind.Destructive -> colors.destructive
    }
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = HealthSpacing.controlHeight),
        shape = CircleShape, border = if (kind == PillButtonKind.Outline) BorderStroke(1.dp, colors.strongBorder) else null,
        colors = ButtonDefaults.buttonColors(containerColor = background, contentColor = foreground,
            disabledContainerColor = background.copy(alpha = 0.38f), disabledContentColor = colors.secondary),
        elevation = null, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
        Text(title, style = HealthType.meta)
    }
}

@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, thickness = Dp.Hairline, color = HealthTheme.colors.border)
}

@Composable
fun MetaRow(label: String, detail: String? = null, modifier: Modifier = Modifier) {
    Text(if (detail == null) label else "$label · $detail", modifier = modifier,
        style = HealthType.meta, color = HealthTheme.colors.secondary)
}

@Composable
fun ListRow(title: String, meta: String, detail: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        HairlineDivider()
        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MetaRow(meta)
            Text(title, style = HealthType.row, color = HealthTheme.colors.primary)
            if (detail != null) Text(detail, style = HealthType.caption, color = HealthTheme.colors.secondary)
        }
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().semantics { heading() }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = HealthType.section, color = HealthTheme.colors.primary)
        if (subtitle != null) Text(subtitle, style = HealthType.section, color = HealthTheme.colors.secondary)
    }
}

@Composable
fun StatusChip(kind: DoseStatusKind, modifier: Modifier = Modifier) {
    val colors = HealthTheme.colors
    val foreground = when (kind) {
        DoseStatusKind.Taken -> colors.success
        DoseStatusKind.Missed -> colors.destructive
        DoseStatusKind.Due -> colors.background
        else -> colors.secondary
    }
    val background = when (kind) {
        DoseStatusKind.Taken -> colors.successBackground
        DoseStatusKind.Missed -> colors.destructiveBackground
        DoseStatusKind.Due -> colors.primary
        else -> colors.secondaryBackground
    }
    Text("${kind.glyph} ${kind.label}", style = HealthType.meta, color = foreground,
        modifier = modifier.clip(CircleShape).background(background)
            .padding(horizontal = 10.dp, vertical = 6.dp).clearAndSetSemantics { contentDescription = kind.label })
}

@Composable
fun ProgressLine(fraction: Float, label: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = HealthType.meta, color = HealthTheme.colors.secondary)
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            color = HealthTheme.colors.primary, trackColor = HealthTheme.colors.tertiaryBackground,
            gapSize = 0.dp, drawStopIndicator = {})
    }
}

@Composable
fun EmptyStateView(title: String, message: String, modifier: Modifier = Modifier,
                   glyph: String = "+", actionTitle: String? = null, onAction: () -> Unit = {}) {
    Column(modifier.fillMaxWidth().background(HealthTheme.colors.secondaryBackground).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(glyph, style = HealthType.title, fontWeight = FontWeight.Light,
            color = HealthTheme.colors.secondary, modifier = Modifier.clearAndSetSemantics {})
        Text(title, style = HealthType.section, color = HealthTheme.colors.primary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(message, style = HealthType.smallBody, color = HealthTheme.colors.secondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (actionTitle != null) PillButton(actionTitle, onAction)
    }
}

@Composable
fun FloatingPillButton(title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(HealthTheme.colors.background).padding(6.dp)) {
        PillButton("+ $title", onClick)
    }
}

@Composable
fun Banner(title: String, message: String, actionTitle: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(HealthTheme.colors.secondaryBackground).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = HealthType.row, color = HealthTheme.colors.primary)
        Text(message, style = HealthType.smallBody, color = HealthTheme.colors.secondary)
        PillButton(actionTitle, onAction, kind = PillButtonKind.Outline)
    }
}

@Composable
fun PillSelect(title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PillButton("$title ⌄", onClick, modifier, kind = PillButtonKind.Outline)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PillSegmentedControl(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier.clip(RoundedCornerShape(28.dp)).background(HealthTheme.colors.secondaryBackground).padding(4.dp)
        .selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { index, label ->
            PillButton(label, { onSelect(index) }, modifier = Modifier.semantics { selected = index == selectedIndex },
                kind = if (index == selectedIndex) PillButtonKind.Primary else PillButtonKind.Outline)
        }
    }
}

@Composable
fun HealthTextField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
                    placeholder: String = "", error: String? = null, singleLine: Boolean = true) {
    val colors = HealthTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = HealthType.meta, color = colors.primary)
        OutlinedTextField(value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth()
            .semantics { contentDescription = label }, singleLine = singleLine, isError = error != null,
            placeholder = { Text(placeholder, style = HealthType.input) }, textStyle = HealthType.input,
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.primary, unfocusedTextColor = colors.primary,
                focusedContainerColor = colors.inputBackground, unfocusedContainerColor = colors.inputBackground,
                errorContainerColor = colors.inputBackground,
                focusedBorderColor = colors.primary, unfocusedBorderColor = colors.border,
                errorBorderColor = colors.destructive, cursorColor = colors.primary,
                focusedPlaceholderColor = colors.secondary, unfocusedPlaceholderColor = colors.secondary,
            ))
        if (error != null) Text(error, style = HealthType.caption, color = colors.destructive,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HealthTopBar(title: String, modifier: Modifier = Modifier, actionTitle: String? = null, onAction: () -> Unit = {},
                 backTitle: String? = null, onBack: () -> Unit = {}) {
    FlowRow(modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (backTitle != null) PillButton("‹ $backTitle", onBack, kind = PillButtonKind.Outline)
        Text(title, style = HealthType.meta, color = HealthTheme.colors.primary,
            modifier = Modifier.align(Alignment.CenterVertically).weight(1f))
        if (actionTitle != null) PillButton(actionTitle, onAction, kind = PillButtonKind.Outline)
    }
}

@Composable
fun HealthToast(message: String, modifier: Modifier = Modifier) {
    Text(message, style = HealthType.smallBody, color = Color.White,
        modifier = modifier.clip(CircleShape).background(HealthBrand.toastBackground)
            .padding(horizontal = 20.dp, vertical = 14.dp).semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
fun HealthConfirmationDialog(isPresented: Boolean, title: String, message: String, confirmTitle: String,
                             onConfirm: () -> Unit, onDismiss: () -> Unit) {
    if (isPresented) AlertDialog(onDismissRequest = onDismiss,
        title = { Text(title, style = HealthType.section) }, text = { Text(message, style = HealthType.body) },
        confirmButton = { PillButton(confirmTitle, onConfirm, kind = PillButtonKind.Destructive) },
        dismissButton = { PillButton("Cancel", onDismiss, kind = PillButtonKind.Outline) },
        containerColor = HealthTheme.colors.background, titleContentColor = HealthTheme.colors.primary,
        textContentColor = HealthTheme.colors.body, tonalElevation = 0.dp, shape = RoundedCornerShape(24.dp))
}
