package app.reflex.mobile

import android.app.Application
import app.reflex.core.app.Core
import app.reflex.mobile.platform.HttpGraphQLTransport
import app.reflex.mobile.platform.LocalFeatureFlags
import app.reflex.mobile.platform.PreferencesStorage
import app.reflex.mobile.platform.SystemDeviceCapabilities
import kotlinx.coroutines.MainScope

/** The app is just a host: it constructs Core with real platform implementations. */
class ReflexApplication : Application() {
    lateinit var core: Core
        private set

    override fun onCreate() {
        super.onCreate()
        core = Core(
            storage = PreferencesStorage(this),
            transport = HttpGraphQLTransport(BuildConfig.GRAPHQL_ENDPOINT),
            device = SystemDeviceCapabilities(this),
            flags = LocalFeatureFlags(),
            // Every ViewModel runs on the main thread (see ViewModel.kt).
            scope = MainScope(),
        )
        ReflexDebug.start(core) // no-op in release (src/release)
    }
}
