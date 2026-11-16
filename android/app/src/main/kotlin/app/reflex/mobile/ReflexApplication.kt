package app.reflex.mobile

import android.app.Application
import app.reflex.core.app.Core
import app.reflex.mobile.platform.AlarmManagerReminderScheduler
import app.reflex.mobile.platform.HealthDatabase
import app.reflex.mobile.platform.HttpGraphQLTransport
import app.reflex.mobile.platform.LocalFeatureFlags
import app.reflex.mobile.platform.NotificationPermissionBridge
import app.reflex.mobile.platform.PreferencesStorage
import app.reflex.mobile.platform.ReminderNotifications
import app.reflex.mobile.platform.RoomHealthRepository
import app.reflex.mobile.platform.SystemClock
import app.reflex.mobile.platform.SystemDeviceCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope

/**
 * The app is just a host: it constructs Core with real platform implementations. Every entry point (launcher,
 * notification tap, alarm, notification action, boot/time receivers) goes through here, so Core exists, loads and
 * reconciles the reminders before anything else happens.
 */
class ReflexApplication : Application() {
    lateinit var core: Core
        private set

    /** Main-thread scope for Core and for work that must outlive an Activity (notification taps). */
    val scope: CoroutineScope = MainScope()

    /** The scheduler asks for POST_NOTIFICATIONS through whichever Activity is on screen. */
    val permissionRequests = NotificationPermissionBridge()

    override fun onCreate() {
        super.onCreate()
        ReminderNotifications.createChannel(this)
        // Built lazily, off Main, by the first repository/scheduler call.
        val database = { HealthDatabase.get(this) }
        core = Core(
            storage = PreferencesStorage(this),
            transport = HttpGraphQLTransport(BuildConfig.GRAPHQL_ENDPOINT),
            device = SystemDeviceCapabilities(this),
            flags = LocalFeatureFlags(),
            clock = SystemClock(this),
            reminderScheduler = AlarmManagerReminderScheduler(this, database, permissionRequests),
            healthRepository = RoomHealthRepository(database),
            // Every ViewModel runs on the main thread (see ViewModel.kt).
            scope = scope,
        )
        ReflexDebug.start(core) // no-op in release (src/release)
    }
}
