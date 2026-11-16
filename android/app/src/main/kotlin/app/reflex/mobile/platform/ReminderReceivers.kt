package app.reflex.mobile.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.reflex.core.app.AppRoot
import app.reflex.mobile.ReflexApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Background entry points. Each one awaits Core (`handleReminderResponse` / `settle`) before telling the OS it is
// done (goAsync + finish, bounded by 9 s of the 10 s a receiver gets), so the process is never killed mid-save.

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
private const val RECEIVER_TIMEOUT_MILLIS = 9_000L

private fun BroadcastReceiver.awaitCore(context: Context, block: suspend (AppRoot) -> Unit) {
    val app = (context.applicationContext as ReflexApplication).core.app
    val result = goAsync()
    receiverScope.launch {
        try {
            withTimeoutOrNull(RECEIVER_TIMEOUT_MILLIS) { block(app) }
        } finally {
            result.finish()
        }
    }
}

/** An alarm fired: post its notification from the extras (no database read). */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderNotifications.post(context, intent)
        // Building the Application started Core (load + reconcile); let that finish before the process may go.
        awaitCore(context) { it.settle() }
    }
}

/** Taken / Skip / Snooze from the notification, without opening the app. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val slot = intent.getStringExtra(ReminderNotifications.EXTRA_SLOT) ?: return
        val action = intent.getStringExtra(ReminderNotifications.EXTRA_ACTION) ?: return
        // After Snooze the slot stays open (Core won't cancel it), so the tapped notification goes away here.
        ReminderNotifications.cancel(context, slot)
        awaitCore(context) { it.handleReminderResponse(slot, action) }
    }
}

/**
 * Boot, app update, time set, time zone change, exact-alarm permission change: touching the Application builds
 * Core, which loads and reconciles every reminder; a running process also hears time changes through the clock.
 */
class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        awaitCore(context) { it.settle() }
    }
}
