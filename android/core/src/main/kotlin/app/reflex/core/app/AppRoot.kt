package app.reflex.core.app

import app.reflex.core.features.health.HealthData
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
 * the next ViewModel with its services and pushes it on the current tab's stack. Every method runs on the core
 * thread (Main in the app).
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

    init {
        health.onDataChange(::removeStaleScreens)
    }

    /**
     * Deep links change navigator state, so they behave identically in the CLI. Paths are scheme-agnostic:
     * - `<s>://today`: the Today tab, reset to its root;
     * - `<s>://medication/<id>`: the Medications tab, reset to its root; once the data has loaded, the medication's
     *   detail is pushed if it exists;
     * - `<s>://reminder/<slot>[/…]`: the Today tab, reset (a notification tap). Never logs anything: any app can
     *   open a URL, so notification actions come only through [handleReminderResponse].
     * Returns false (and changes nothing) for links that don't route anywhere.
     */
    fun open(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val parts = listOfNotNull(uri.host) + uri.path.orEmpty().split("/").filter { it.isNotEmpty() }
        return when (parts.firstOrNull()) {
            "today" -> parts.size == 1 && showToday()
            "medication" -> parts.size == 2 && showMedicationFromLink(parts[1])
            "reminder" -> parts.size >= 2 && Slot.parse(parts[1]) != null && showToday()
            else -> false
        }
    }

    /**
     * A notification response: [slot] is the reminder's slot (`yyyy-MM-ddTHH:mm`), [action] one of `open`, `taken`,
     * `skipped`, `snooze`. Selects Today (reset to its root); then, once the data has loaded, applies the action.
     * Returns once the response has been fully handled: data loaded (or failed to load), applied, saved and the
     * reminders reconciled. Returns false (and changes nothing) for a malformed slot or an unknown action.
     */
    suspend fun handleReminderResponse(slot: String, action: String): Boolean {
        val parsed = Slot.parse(slot) ?: return false
        if (action !in REMINDER_ACTIONS) return false
        val response = REMINDER_RESPONSES[action]
        showToday()
        if (health.awaitLoad() && response != null) health.respond(parsed, response)
        health.settle()
        return true
    }

    /** Returns once the initial load and every save/reconciliation requested so far have finished. */
    suspend fun settle() = health.settle()

    private fun showToday(): Boolean {
        tabs.select(TAB_TODAY)
        tabs.selected.stack.reset(listOf(todayRoot))
        return true
    }

    private fun showMedicationFromLink(id: String): Boolean {
        tabs.select(TAB_MEDICATIONS)
        val stack = tabs.selected.stack
        stack.reset(listOf(medicationsRoot))
        health.whenLoaded { loaded ->
            if (loaded && id in health.current.days.medications) stack.push(makeDetail(id))
        }
        return true
    }

    /** Whenever a medication is gone, every detail and edit-mode editor of it leaves every stack. */
    private fun removeStaleScreens(data: HealthData) {
        val ids = data.medications.mapTo(HashSet()) { it.id }
        for (tab in tabs.tabs) {
            val routes = tab.stack.routes.value
            val kept = routes.filter { route ->
                when (route) {
                    is Route.MedicationDetail -> route.viewModel.medicationId in ids
                    is Route.MedicationEditor -> route.viewModel.editedMedicationId.let { it == null || it in ids }
                    else -> true
                }
            }
            if (kept.size != routes.size) tab.stack.reset(kept)
        }
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

        private val REMINDER_ACTIONS = setOf("open", "taken", "skipped", "snooze")
        private val REMINDER_RESPONSES = mapOf(
            "taken" to ReminderResponse.Taken,
            "skipped" to ReminderResponse.Skipped,
            "snooze" to ReminderResponse.Snooze,
        )
    }
}
