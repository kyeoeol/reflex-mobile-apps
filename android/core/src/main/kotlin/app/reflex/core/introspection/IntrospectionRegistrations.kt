package app.reflex.core.introspection

import app.reflex.core.features.health.HistoryViewModel
import app.reflex.core.features.health.HistoryViewModelIntrospectionNode
import app.reflex.core.features.health.MedicationDetailViewModel
import app.reflex.core.features.health.MedicationDetailViewModelIntrospectionNode
import app.reflex.core.features.health.MedicationEditorViewModel
import app.reflex.core.features.health.MedicationEditorViewModelIntrospectionNode
import app.reflex.core.features.health.MedicationsViewModel
import app.reflex.core.features.health.MedicationsViewModelIntrospectionNode
import app.reflex.core.features.health.RemindersViewModel
import app.reflex.core.features.health.RemindersViewModelIntrospectionNode
import app.reflex.core.features.health.TodayViewModel
import app.reflex.core.features.health.TodayViewModelIntrospectionNode
import app.reflex.introspection.IntrospectionRegistry

/**
 * Registers every ViewModel's node. Called at startup by the CLI and the debug socket server only;
 * app code never references this file. Every new screen adds one line here.
 */
fun registerIntrospection() {
    IntrospectionRegistry.register(TodayViewModel::class) { TodayViewModelIntrospectionNode(it) }
    IntrospectionRegistry.register(MedicationsViewModel::class) { MedicationsViewModelIntrospectionNode(it) }
    IntrospectionRegistry.register(MedicationEditorViewModel::class) { MedicationEditorViewModelIntrospectionNode(it) }
    IntrospectionRegistry.register(MedicationDetailViewModel::class) { MedicationDetailViewModelIntrospectionNode(it) }
    IntrospectionRegistry.register(HistoryViewModel::class) { HistoryViewModelIntrospectionNode(it) }
    IntrospectionRegistry.register(RemindersViewModel::class) { RemindersViewModelIntrospectionNode(it) }
}
