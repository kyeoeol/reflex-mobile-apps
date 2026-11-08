package app.reflex.core.app

import app.reflex.core.features.sample.GraphQLSampleService
import app.reflex.core.features.sample.SampleDetailViewModel
import app.reflex.core.features.sample.SampleListViewModel
import app.reflex.core.features.sample.SampleNavigating
import app.reflex.core.foundation.DeviceCapabilities
import app.reflex.core.foundation.FeatureFlags
import app.reflex.core.foundation.GraphQLClient
import app.reflex.core.foundation.GraphQLTransport
import app.reflex.core.foundation.Routable
import app.reflex.core.foundation.StackNavigator
import app.reflex.core.foundation.Storage
import kotlinx.coroutines.CoroutineScope
import java.net.URI

/**
 * The entry point every host constructs. The app and the CLI are just hosts:
 * the app passes real implementations and `Dispatchers.Main`; the CLI passes fixtures and its core thread.
 * Plain dependency inversion: no DI framework.
 */
class Core(storage: Storage, transport: GraphQLTransport, device: DeviceCapabilities, flags: FeatureFlags, scope: CoroutineScope) {
    val app = AppRoot(Services(GraphQLClient(transport), storage, device, flags, scope))
}

/** Shared dependencies handed to feature services and ViewModel factories by [AppRoot]. */
class Services(
    val client: GraphQLClient,
    val storage: Storage,
    val device: DeviceCapabilities,
    val flags: FeatureFlags,
    val scope: CoroutineScope,
)

/** Every screen the app can show. Each case wraps that screen's ViewModel. */
sealed interface Route : Routable {
    data class SampleList(override val viewModel: SampleListViewModel) : Route
    data class SampleDetail(override val viewModel: SampleDetailViewModel) : Route
}

/**
 * Root of the object tree: owns services, session and persistence, and the navigator.
 *
 *     AppRoot ─ StackNavigator(routes) ─ <top route's ViewModel>
 *
 * Also the factory for new screens: ViewModels ask for navigation through small `*Navigating` interfaces,
 * and AppRoot builds the next ViewModel with its services and pushes it.
 */
class AppRoot internal constructor(private val services: Services) : SampleNavigating {
    val navigator = StackNavigator<Route>()

    init {
        navigator.reset(listOf(Route.SampleList(makeSampleList())))
    }

    /**
     * Deep links change navigator state, so they behave identically in the CLI.
     * Paths are scheme-agnostic: `<any-scheme>://sample/<id>` opens that item's detail.
     */
    fun open(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val parts = listOfNotNull(uri.host) + uri.path.orEmpty().split("/").filter { it.isNotEmpty() }
        return when {
            parts.firstOrNull() == "sample" && parts.size == 2 -> {
                navigator.reset(listOf(Route.SampleList(makeSampleList())))
                showSampleDetail(parts[1])
                true
            }
            else -> false
        }
    }

    // Factories

    private fun makeSampleList() = SampleListViewModel(GraphQLSampleService(services.client), this, services.scope)

    override fun showSampleDetail(id: String) {
        navigator.push(Route.SampleDetail(SampleDetailViewModel(id, GraphQLSampleService(services.client), services.scope)))
    }
}
