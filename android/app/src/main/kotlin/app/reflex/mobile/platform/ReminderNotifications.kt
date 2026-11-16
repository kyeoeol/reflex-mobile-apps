package app.reflex.mobile.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import app.reflex.mobile.MainActivity
import app.reflex.mobile.R

/**
 * The notification side of reminders: the `reminders` channel, posting a fired reminder, and the intents behind
 * it. One notification per slot (tag = slot, so a follow-up or snooze replaces the slot's earlier notification);
 * the refresh reminder has its own tag.
 */
object ReminderNotifications {
    const val CHANNEL_ID = "reminders"
    const val NOTIFICATION_ID = 1
    const val REFRESH_TAG = "refresh"

    const val EXTRA_ID = "reminder.id"
    const val EXTRA_KIND = "reminder.kind"
    const val EXTRA_TITLE = "reminder.title"
    const val EXTRA_BODY = "reminder.body"
    const val EXTRA_SLOT = "reminder.slot"
    const val EXTRA_ACTION = "reminder.action"

    /** Notification actions → `AppRoot.handleReminderResponse` actions. */
    val ACTIONS = listOf("taken" to "Taken", "skipped" to "Skip", "snooze" to "Snooze 10 min")

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Dose reminders"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** True when the user blocked the `reminders` channel (counts as notifications being off). */
    fun isChannelBlocked(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)
            ?.importance == NotificationManager.IMPORTANCE_NONE

    fun tag(slot: String?): String = slot ?: REFRESH_TAG

    /** Posts a fired reminder from the alarm's extras (no database read). */
    fun post(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val slot = intent.getStringExtra(EXTRA_SLOT)
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(intent.getStringExtra(EXTRA_TITLE))
            .setContentText(intent.getStringExtra(EXTRA_BODY))
            .setStyle(Notification.BigTextStyle().bigText(intent.getStringExtra(EXTRA_BODY)))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, slot, id))
        if (slot != null) for ((action, title) in ACTIONS) {
            builder.addAction(Notification.Action.Builder(null, title, actionIntent(context, slot, action)).build())
        }
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(tag(slot), NOTIFICATION_ID, builder.build())
        }
    }

    fun cancel(context: Context, slot: String?) {
        context.getSystemService(NotificationManager::class.java).cancel(tag(slot), NOTIFICATION_ID)
    }

    /** Cancels delivered slot notifications whose slot is no longer open. */
    fun cancelClosed(context: Context, openSlots: Set<String>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        for (notification in manager.activeNotifications) {
            val tag = notification.tag ?: continue
            if (notification.id == NOTIFICATION_ID && tag != REFRESH_TAG && tag !in openSlots) manager.cancel(tag, NOTIFICATION_ID)
        }
    }

    /** Tapping the notification opens the app (an Activity PendingIntent: receiver trampolines are blocked). */
    private fun openIntent(context: Context, slot: String?, id: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.Builder().scheme("healthbeta-open").appendPath(id).build())
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (slot != null) intent.putExtra(EXTRA_SLOT, slot)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun actionIntent(context: Context, slot: String, action: String): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java)
            .setData(Uri.Builder().scheme("healthbeta-action").appendPath(action).appendPath(slot).build())
            .putExtra(EXTRA_SLOT, slot)
            .putExtra(EXTRA_ACTION, action)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
