package app.reflex.mobile.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// All values and callbacks come from the host. No feature, Core, or ViewModel dependencies.

/** An icon size that grows with the font scale like text of the same size (no 16 dp icon beside doubled text). */
@Composable
fun scaledIconSize(size: Dp): Dp = with(LocalDensity.current) { size.value.sp.toDp() }

/** Accessibility font scales: layouts stack, floating controls move into the flow. */
@Composable
fun isLargeFontScale(): Boolean = LocalDensity.current.fontScale >= 1.3f

/** Dialog surface: the raised panel colour and a 1 dp outline, so a dialog reads as a surface on the dark canvas too. */
val DialogShape = RoundedCornerShape(24.dp)

@Composable
fun Modifier.dialogOutline(): Modifier = border(1.dp, HealthTheme.colors.strongBorder, DialogShape)
enum class PillButtonKind { Primary, Outline, Destructive }
enum class DoseStatusKind(val label: String, val icon: ImageVector) {
    Upcoming("Upcoming", HealthIcons.Clock), Due("Due", HealthIcons.Dot), Taken("Taken", HealthIcons.Check),
    Skipped("Skipped", HealthIcons.Minus), Missed("Missed", HealthIcons.Warning)
}

@Composable
fun PillButton(title: String, onClick: () -> Unit, modifier: Modifier = Modifier,
               kind: PillButtonKind = PillButtonKind.Primary, enabled: Boolean = true,
               icon: ImageVector? = null) {
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
    Button(onClick = onClick, enabled = enabled, modifier = modifier.sizeIn(minWidth = HealthSpacing.controlHeight, minHeight = HealthSpacing.controlHeight),
        shape = CircleShape, border = if (kind == PillButtonKind.Outline) BorderStroke(1.dp, colors.strongBorder) else null,
        colors = ButtonDefaults.buttonColors(containerColor = background, contentColor = foreground,
            disabledContainerColor = background.copy(alpha = 0.38f), disabledContentColor = colors.secondary),
        elevation = null, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
        if (icon != null) { Icon(icon, contentDescription = null, modifier = Modifier.size(scaledIconSize(20.dp))); Spacer(Modifier.width(8.dp)) }
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
    Row(modifier.clip(CircleShape).background(background).padding(horizontal = 10.dp, vertical = 6.dp)
        .semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(kind.icon, contentDescription = null, tint = foreground, modifier = Modifier.size(scaledIconSize(16.dp)))
        Text(kind.label, style = HealthType.meta, color = foreground)
    }
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
fun EmptyStateView(message: String?, modifier: Modifier = Modifier, title: String? = null,
                   icon: ImageVector? = HealthIcons.Plus, actionTitle: String? = null, onAction: () -> Unit = {},
                   messageModifier: Modifier = Modifier, actionModifier: Modifier = Modifier,
                   messageColor: Color = HealthTheme.colors.secondary) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(HealthTheme.colors.secondaryBackground).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (icon != null) Icon(icon, contentDescription = null, tint = HealthTheme.colors.secondary,
            modifier = Modifier.size(scaledIconSize(28.dp)))
        if (title != null) Text(title, style = HealthType.section, color = HealthTheme.colors.primary,
            textAlign = TextAlign.Center)
        if (message != null) Text(message, style = HealthType.smallBody, color = messageColor,
            textAlign = TextAlign.Center,
            modifier = messageModifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (actionTitle != null) PillButton(actionTitle, onAction, modifier = actionModifier)
    }
}

@Composable
fun FloatingPillButton(title: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                       buttonModifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(HealthTheme.colors.background).padding(6.dp)) {
        PillButton(title, onClick, modifier = buttonModifier, icon = HealthIcons.Plus)
    }
}

@Composable
fun Banner(title: String, modifier: Modifier = Modifier, message: String? = null,
           actionTitle: String? = null, onAction: () -> Unit = {},
           titleModifier: Modifier = Modifier, actionModifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(HealthTheme.colors.secondaryBackground).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = HealthType.row, color = HealthTheme.colors.primary, modifier = titleModifier)
        if (message != null) Text(message, style = HealthType.smallBody, color = HealthTheme.colors.secondary)
        if (actionTitle != null) PillButton(actionTitle, onAction, modifier = actionModifier, kind = PillButtonKind.Outline)
    }
}

@Composable
fun PillSelect(label: String, title: String, onClick: () -> Unit, modifier: Modifier = Modifier, fillWidth: Boolean = false) {
    OutlinedButton(onClick, modifier = modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .semantics { contentDescription = label; stateDescription = title }, shape = CircleShape,
        border = BorderStroke(1.dp, HealthTheme.colors.controlBorder),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = HealthTheme.colors.primary)) {
        // Full width: the value on the leading edge, the chevron on the trailing edge.
        Text(title, style = HealthType.meta, modifier = Modifier.weight(1f, fill = fillWidth))
        Spacer(Modifier.width(8.dp))
        Icon(HealthIcons.Down, contentDescription = null, modifier = Modifier.size(scaledIconSize(20.dp)))
    }
}

/** Equal-width segments across the given width; stacked full-width at accessibility text sizes. */
@Composable
fun PillSegmentedControl(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
                         itemModifier: (Int) -> Modifier = { Modifier }) {
    val container = modifier.clip(RoundedCornerShape(28.dp)).background(HealthTheme.colors.secondaryBackground).padding(4.dp)
        .selectableGroup()
    val item = @Composable { index: Int, fill: Modifier ->
        PillButton(labels[index], { onSelect(index) }, modifier = fill.then(itemModifier(index)).semantics { selected = index == selectedIndex },
            kind = if (index == selectedIndex) PillButtonKind.Primary else PillButtonKind.Outline)
    }
    if (isLargeFontScale()) Column(container, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.indices.forEach { item(it, Modifier.fillMaxWidth()) }
    } else Row(container, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.indices.forEach { item(it, Modifier.weight(1f)) }
    }
}

@Composable
fun HealthTextField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
                    placeholder: String = "", error: String? = null, singleLine: Boolean = true,
                    fieldModifier: Modifier = Modifier, errorModifier: Modifier = Modifier) {
    val colors = HealthTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = HealthType.meta, color = colors.primary)
        OutlinedTextField(value = value, onValueChange = onValueChange, modifier = fieldModifier.fillMaxWidth()
            .heightIn(min = 48.dp).semantics { contentDescription = label; if (error != null) error(error) },
            singleLine = singleLine, isError = error != null,
            placeholder = { Text(placeholder, style = HealthType.input) }, textStyle = HealthType.input,
            shape = RoundedCornerShape(if (singleLine) 28.dp else 16.dp), minLines = if (singleLine) 1 else 3,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.primary, unfocusedTextColor = colors.primary,
                focusedContainerColor = colors.inputBackground, unfocusedContainerColor = colors.inputBackground,
                errorContainerColor = colors.inputBackground,
                focusedBorderColor = colors.primary, unfocusedBorderColor = colors.controlBorder,
                errorBorderColor = colors.destructive, cursorColor = colors.primary,
                focusedPlaceholderColor = colors.secondary, unfocusedPlaceholderColor = colors.secondary,
            ))
        if (error != null) Text(error, style = HealthType.caption, color = colors.destructive,
            modifier = errorModifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HealthTopBar(title: String, modifier: Modifier = Modifier, actionTitle: String? = null, onAction: () -> Unit = {},
                 backTitle: String? = null, onBack: () -> Unit = {}, actionIcon: ImageVector? = null,
                 backIcon: ImageVector? = HealthIcons.Back,
                 actionModifier: Modifier = Modifier, backModifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (backTitle != null) PillButton(backTitle, onBack, modifier = backModifier, kind = PillButtonKind.Outline, icon = backIcon)
        Text(title, style = HealthType.meta, color = HealthTheme.colors.primary,
            modifier = Modifier.align(Alignment.CenterVertically).weight(1f))
        if (actionTitle != null) PillButton(actionTitle, onAction, modifier = actionModifier, kind = PillButtonKind.Outline, icon = actionIcon)
    }
}

@Composable
fun HealthConfirmationDialog(isPresented: Boolean, title: String, message: String, confirmTitle: String,
                             onConfirm: () -> Unit, onDismiss: () -> Unit, onDismissRequest: () -> Unit = onDismiss,
                             confirmModifier: Modifier = Modifier, dismissModifier: Modifier = Modifier) {
    // A dialog is its own window: its test tags need their own resource-id opt-in.
    if (isPresented) AlertDialog(onDismissRequest = onDismissRequest, modifier = Modifier.exposeTestTags().dialogOutline(),
        title = { Text(title, style = HealthType.section) }, text = { Text(message, style = HealthType.body) },
        confirmButton = { PillButton(confirmTitle, onConfirm, modifier = confirmModifier, kind = PillButtonKind.Destructive) },
        dismissButton = { PillButton("Cancel", onDismiss, modifier = dismissModifier, kind = PillButtonKind.Outline) },
        containerColor = HealthTheme.colors.secondaryBackground, titleContentColor = HealthTheme.colors.primary,
        textContentColor = HealthTheme.colors.body, tonalElevation = 0.dp, shape = DialogShape)
}

/** Makes `Modifier.testTag`s visible to UI Automator as resource ids (set at the root and in every dialog/popup). */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.exposeTestTags(): Modifier = semantics { testTagsAsResourceId = true }
