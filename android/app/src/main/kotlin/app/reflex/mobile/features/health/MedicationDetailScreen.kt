package app.reflex.mobile.features.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.reflex.core.features.health.MedicationDetailViewModel
import app.reflex.core.features.health.MedicationDetailViewModel.Action
import app.reflex.mobile.designsystem.HairlineDivider
import app.reflex.mobile.designsystem.HealthConfirmationDialog
import app.reflex.mobile.designsystem.HealthTheme
import app.reflex.mobile.designsystem.HealthTopBar
import app.reflex.mobile.designsystem.HealthType
import app.reflex.mobile.designsystem.MetaRow
import app.reflex.mobile.designsystem.PillButton
import app.reflex.mobile.designsystem.PillButtonKind
import app.reflex.mobile.designsystem.SectionHeader
import app.reflex.mobile.designsystem.StatusChip

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MedicationDetailScreen(vm: MedicationDetailViewModel, backTitle: String, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = HealthTheme.colors

    HealthScreen {
        HealthTopBar("", actionTitle = "Edit", onAction = { vm.send(Action.Edit) }, backTitle = backTitle, onBack = onBack,
            actionModifier = Modifier.testTag("detail.edit"))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${HealthFormat.kind(state.kind)} · ${HealthFormat.medicationStatus(state.status)}", style = HealthType.meta,
                color = colors.secondary, modifier = Modifier.testTag("detail.status"))
            Text(state.name, style = HealthType.title, color = colors.primary, modifier = Modifier.testTag("detail.name"))
            if (state.dose.isNotEmpty()) Text(state.dose, style = HealthType.body, color = colors.body)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Schedule")
            Text(state.schedule, style = HealthType.body, color = colors.primary, modifier = Modifier.testTag("detail.schedule"))
            MetaRow(HealthFormat.date(state.startDate), state.endDate?.let(HealthFormat::date) ?: "No end date")
            if (state.notes.isNotEmpty()) Text(state.notes, style = HealthType.smallBody, color = colors.body)
        }
        Column(Modifier.testTag("detail.adherence").semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Adherence")
            state.adherenceLabel?.let { Text(it, style = HealthType.display, color = colors.primary) }
            Text(state.adherenceCaption, style = HealthType.caption, color = colors.secondary)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.canArchive) PillButton("Archive", { vm.send(Action.Archive) }, modifier = Modifier.testTag("detail.archive"),
                kind = PillButtonKind.Outline)
            if (state.canRestore) PillButton("Restore", { vm.send(Action.Restore) }, modifier = Modifier.testTag("detail.restore"),
                kind = PillButtonKind.Outline)
            PillButton("Delete", { vm.send(Action.Delete) }, modifier = Modifier.testTag("detail.delete"),
                kind = PillButtonKind.Destructive)
        }
        if (state.history.isNotEmpty()) Column {
            SectionHeader("Recent history", modifier = Modifier.padding(bottom = 8.dp))
            // Read-only here: this screen has no dose actions.
            for (dose in state.history) {
                Column(Modifier.testTag("detail.dose.${dose.id}").padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HairlineDivider(Modifier.padding(bottom = 10.dp))
                    MetaRow(listOfNotNull(HealthFormat.date(dose.date), dose.time, dose.takenAt?.let { "Taken at $it" }).joinToString(" · "))
                    Text(dose.name, style = HealthType.row, color = colors.primary)
                    if (dose.dose.isNotEmpty()) Text(dose.dose, style = HealthType.caption, color = colors.secondary)
                    StatusChip(HealthFormat.status(dose.status), modifier = Modifier.testTag("detail.dose.${dose.id}.status"))
                    HealthFormat.timing(dose.timing)?.let { Text(it, style = HealthType.caption, color = colors.secondary) }
                }
            }
        }
    }

    // Shown while Core says so. Dismissing (back, outside tap) changes nothing; only the two buttons send actions.
    HealthConfirmationDialog(
        isPresented = state.isConfirmingDelete,
        title = "Delete medication?",
        message = "This removes the medication and its dose history. This cannot be undone.",
        confirmTitle = "Delete",
        onConfirm = { vm.send(Action.ConfirmDelete) },
        onDismiss = { vm.send(Action.CancelDelete) },
        onDismissRequest = {},
        confirmModifier = Modifier.testTag("deleteDialog.confirm"),
        dismissModifier = Modifier.testTag("deleteDialog.cancel"),
    )
}
