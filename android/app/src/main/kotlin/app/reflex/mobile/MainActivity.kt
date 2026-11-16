package app.reflex.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.reflex.mobile.designsystem.HealthTheme
import app.reflex.mobile.features.health.HealthApp
import app.reflex.mobile.platform.ReminderNotifications
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val reflex get() = application as ReflexApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        reflex.permissionRequests.attach(this)
        // A recreated Activity (rotation, dark mode) has already handled its intent.
        if (savedInstanceState == null) handleNotificationTap(intent)
        setContent {
            HealthTheme { HealthApp(reflex.core.app) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationTap(intent)
    }

    /** A tapped reminder notification carries its slot; it is answered once, as `open`. */
    private fun handleNotificationTap(intent: Intent) {
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val slot = intent.getStringExtra(ReminderNotifications.EXTRA_SLOT) ?: return
        intent.removeExtra(ReminderNotifications.EXTRA_SLOT)
        reflex.scope.launch { reflex.core.app.handleReminderResponse(slot, "open") }
    }
}
