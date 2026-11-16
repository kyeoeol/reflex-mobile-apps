package app.reflex.mobile.features.health

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.reflex.core.features.health.MedicationsViewModel
import app.reflex.core.features.health.MedicationsViewModel.Action
import app.reflex.core.features.health.MedicationsViewModel.MedicationItem
import app.reflex.mobile.designsystem.EmptyStateView
import app.reflex.mobile.designsystem.FloatingPillButton
import app.reflex.mobile.designsystem.HealthIcons
import app.reflex.mobile.designsystem.HealthSpacing
import app.reflex.mobile.designsystem.HealthTopBar
import app.reflex.mobile.designsystem.MedicationRow
import app.reflex.mobile.designsystem.PillButton
import app.reflex.mobile.designsystem.SectionHeader

@Composable
fun MedicationsScreen(vm: MedicationsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val largeText = isLargeText()
    val floatingAdd = !state.isLoading && !state.isEmpty && !largeText
    Box(Modifier.fillMaxSize()) {
        HealthScreen(bottomReserve = if (floatingAdd) 80.dp else HealthSpacing.lg) {
            HealthTopBar("Health Beta")
            ScreenTitle("Medications")
            when {
                state.isLoading -> LoadingIndicator()
                // Empty: the treatment carries the Add action and nothing else (no invented copy).
                state.isEmpty -> EmptyStateView(null, modifier = Modifier.testTag("medications.empty"), icon = HealthIcons.Pill,
                    actionTitle = "Add", onAction = { vm.send(Action.Add) }, actionModifier = Modifier.testTag("medications.add"))
                else -> {
                    if (largeText) PillButton("Add", { vm.send(Action.Add) }, modifier = Modifier.testTag("medications.add"),
                        icon = HealthIcons.Plus)
                    MedicationSection("Active", "active", state.active, vm)
                    MedicationSection("Ended", "ended", state.ended, vm)
                    MedicationSection("Archived", "archived", state.archived, vm)
                }
            }
        }
        if (floatingAdd) FloatingPillButton("Add", { vm.send(Action.Add) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(HealthSpacing.screen),
            buttonModifier = Modifier.testTag("medications.add"))
    }
}

@Composable
private fun MedicationSection(title: String, id: String, items: List<MedicationItem>, vm: MedicationsViewModel) {
    if (items.isEmpty()) return
    Column(Modifier.testTag("medications.section.$id")) {
        SectionHeader(title, modifier = Modifier.padding(bottom = 8.dp))
        for (item in items) {
            val meta = listOf(item.dose, HealthFormat.kind(item.kind)).filter { it.isNotEmpty() }.joinToString(" · ")
            MedicationRow(item.name, meta, item.schedule,
                modifier = Modifier.testTag("medications.item.${item.id}").heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = "Open ${item.name}") { vm.send(Action.Open(item.id)) })
        }
    }
}
