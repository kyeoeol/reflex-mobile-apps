package app.reflex.core.app

import app.reflex.core.features.health.HealthNavigating
import app.reflex.core.features.health.HealthStore
import app.reflex.core.features.health.HistoryViewModel
import app.reflex.core.features.health.MedicationDetailViewModel
import app.reflex.core.features.health.MedicationEditorViewModel
import app.reflex.core.features.health.MedicationsViewModel
import app.reflex.core.features.health.ReminderResponse
import app.reflex.core.features.health.RemindersViewModel
import app.reflex.core.features.health.Slot
import app.reflex.core.features.health.TodayViewModel
import app.reflex.core.foundation.Clock
import app.reflex.core.foundation.DeviceCapabilities
import app.reflex.core.foundation.FeatureFlags
import app.reflex.core.foundation.GraphQLClient
import app.reflex.core.foundation.GraphQLTransport
import app.reflex.core.foundation.HealthRepository
import app.reflex.core.foundation.ReminderScheduler
import app.reflex.core.foundation.Routable
import app.reflex.core.foundation.StackNavigator
import app.reflex.core.foundation.Storage
import app.reflex.core.foundation.TabNavigator
import kotlinx.coroutines.CoroutineScope
import java.net.URI

/**
 * The entry point every host constructs. The app and the CLI are just hosts:
 * the app passes real implementations and `Dispatchers.Main`; the CLI passes fixtures and its core thread.
 * Plain dependency inversion: no DI framework.
 */
class Core(
    storage: Storage,
    transport: GraphQLTransport,
    device: DeviceCapabilities,
    flags: FeatureFlags,
    clock: Clock,
    reminderScheduler: ReminderScheduler,
    healthRepository: HealthRepository,
    scope: CoroutineScope,
) {
    val app = AppRoot(Services(GraphQLClient(transport), storage, device, flags, clock, reminderScheduler, healthRepository, scope))
}

/** Shared dependencies handed to feature services and ViewModel factories by [AppRoot]. */
class Services(
    val client: GraphQLClient,
    val storage: Storage,
    val device: DeviceCapabilities,
    val flags: FeatureFlags,
    val clock: Clock,
    val reminderScheduler: ReminderScheduler,
    val healthRepository: HealthRepository,
    val scope: CoroutineScope,
)

/** Every screen the app can show. Each case wraps that screen's ViewModel. */
sealed interface Route : Routable {
    data class Today(override val viewModel: TodayViewModel) : Route
    data class Medications(override val viewModel: MedicationsViewModel) : Route
    data class History(override val viewModel: HistoryViewModel) : Route
    data class MedicationDetail(override val viewModel: MedicationDetailViewModel) : Route
    data class MedicationEditor(override val viewModel: MedicationEditorViewModel) : Route
    data class Reminders(override val viewModel: RemindersViewModel) : Route
}

/**
 * Root of the object tree: owns the Health store and the navigators.
 *
 *     AppRoot ─ TabNavigator(today | medications | history) ─ StackNavigator(routes) ─ <top route's ViewModel>
 *
 * Also the factory for new screens: ViewModels ask for navigation through [HealthNavigating], and AppRoot builds
 * the next ViewModel with its services and pushes it on the current tab's stack.
 */
class AppRoot internal constructor(private val services: Services) : HealthNavigating {
    private val health = HealthStore(services.healthRepository, services.reminderScheduler, services.clock, services.scope)

    // Each tab's root screen lives as long as the app; resetting a stack keeps it.
    private val todayRoot = Route.Today(TodayViewModel(health, this, services.scope))
    private val medicationsRoot = Route.Medications(MedicationsViewModel(health, this, services.scope))
    private val historyRoot = Route.History(HistoryViewModel(health, services.scope))

    val tabs = TabNavigator(
        listOf(
            TabNavigator.Tab(TAB_TODAY, StackNavigator<Route>(listOf(todayRoot))),
            TabNavigator.Tab(TAB_MEDICATIONS, StackNavigator<Route>(listOf(medicationsRoot))),
            TabNavigator.Tab(TAB_HISTORY, StackNavigator<Route>(listOf(historyRoot))),
        ),
        initial = TAB_TODAY,
    )

    /**
     * Deep links change navigator state, so they behave identically in the CLI. Paths are scheme-agnostic:
     * - `<s>://today`: the Today tab, reset to its root;
     * - `<s>://medication/<id>`: the Medications tab, reset to `[Medications, MedicationDetail(id)]`;
     * - `<s>://reminder/<slot>[/taken|/skipped|/snooze]`: a notification tap (Today, reset) and its action button.
     *   Actions apply once the stored data has loaded (links can arrive right after `Core(...)`).
     */
    fun open(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val parts = listOfNotNull(uri.host) + uri.path.orEmpty().split("/").filter { it.isNotEmpty() }
        return when (parts.firstOrNull()) {
            "today" -> parts.size == 1 && showToday()
            "medication" -> parts.size == 2 && showMedicationFromLink(parts[1])
            "reminder" -> openReminder(parts.drop(1))
            else -> false
        }
    }

    private fun showToday(): Boolean {
        tabs.select(TAB_TODAY)
        tabs.selected.stack.reset(listOf(todayRoot))
        return true
    }

    private fun showMedicationFromLink(id: String): Boolean {
        tabs.select(TAB_MEDICATIONS)
        tabs.selected.stack.reset(listOf(medicationsRoot, makeDetail(id)))
        return true
    }

    private fun openReminder(parts: List<String>): Boolean {
        val slot = parts.firstOrNull()?.let(Slot::parse) ?: return false
        val response = when (parts.size) {
            1 -> null
            2 -> REMINDER_RESPONSES[parts[1]] ?: return false
            else -> return false
        }
        showToday()
        if (response != null) health.whenLoaded { health.respond(slot, response) }
        return true
    }

    // Navigation requested by the screens (HealthNavigating)

    override fun showReminders() {
        tabs.selected.stack.push(Route.Reminders(RemindersViewModel(health, services.scope)))
    }

    override fun showMedicationEditor(medicationId: String?) {
        tabs.selected.stack.push(Route.MedicationEditor(MedicationEditorViewModel(medicationId, health, this, services.scope)))
    }

    override fun showMedication(id: String) {
        tabs.selected.stack.push(makeDetail(id))
    }

    override fun close(screen: Any) {
        tabs.tabs.map { it.stack }.firstOrNull { it.top?.viewModel === screen }?.goBack()
    }

    private fun makeDetail(id: String) = Route.MedicationDetail(MedicationDetailViewModel(id, health, this, services.scope))

    companion object {
        const val TAB_TODAY = "today"
        const val TAB_MEDICATIONS = "medications"
        const val TAB_HISTORY = "history"

        private val REMINDER_RESPONSES = mapOf(
            "taken" to ReminderResponse.Taken,
            "skipped" to ReminderResponse.Skipped,
            "snooze" to ReminderResponse.Snooze,
        )
    }
}
