package app.reflex.mobile.platform

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import app.reflex.core.features.health.HealthTime
import app.reflex.core.foundation.NotificationPermission
import app.reflex.core.foundation.Reminder
import app.reflex.core.foundation.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Core's `ReminderScheduler` on AlarmManager + NotificationManager. Core hands over the complete desired set; this
 * only reconciles it:
 * - every desired alarm is set again on every call (alarms vanish on reboot, app update and force-stop while the
 *   stored list still looks unchanged); the stored list is used only to cancel the ones no longer desired;
 * - exact alarms (`setExactAndAllowWhileIdle`) when allowed, otherwise a 10-minute window and [mayBeLate] is true;
 * - delivered notifications of closed slots are cancelled.
 * When an alarm fires, [ReminderAlarmReceiver] posts the notification from the alarm's extras.
 */
class AlarmManagerReminderScheduler(
    private val context: Context,
    private val database: () -> HealthDatabase,
    private val permissionRequests: NotificationPermissionBridge,
) : ReminderScheduler {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val prefs = context.getSharedPreferences("reflex.reminders", Context.MODE_PRIVATE)

    override suspend fun permission(): NotificationPermission = withContext(Dispatchers.IO) { currentPermission() }

    override suspend fun requestPermission(): NotificationPermission {
        if (permission() != NotificationPermission.NotDetermined) return permission()
        withContext(Dispatchers.IO) { prefs.edit().putBoolean(KEY_ASKED, true).commit() }
        permissionRequests.request(Manifest.permission.POST_NOTIFICATIONS)
        return permission()
    }

    override suspend fun mayBeLate(): Boolean = !alarms.canScheduleExactAlarms()

    override suspend fun replaceAll(reminders: List<Reminder>, openSlots: Set<String>) = withContext(Dispatchers.IO) {
        val dao = database().reminders()
        val desiredIds = reminders.mapTo(HashSet()) { it.id }
        for (stored in dao.all()) if (stored.id !in desiredIds) cancelAlarm(stored.id)
        val exact = alarms.canScheduleExactAlarms()
        for (reminder in reminders) setAlarm(reminder, exact)
        dao.replaceAll(reminders.map { it.record() })
        ReminderNotifications.cancelClosed(context, openSlots)
    }

    override suspend fun pending(): List<Reminder> = withContext(Dispatchers.IO) {
        val now = Instant.now()
        database().reminders().all().map { it.reminder() }.filter { it.fireAt > now }
            .sortedWith(compareBy<Reminder> { it.fireAt }.thenBy { it.id })
    }

    /** Denied → the app's notification settings; allowed but without exact alarms → the exact-alarm screen. */
    override suspend fun openSettings() {
        val intent = if (currentPermission() != NotificationPermission.Authorized) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else if (!alarms.canScheduleExactAlarms()) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null))
        } else {
            return
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /**
     * Granted (and neither the app's notifications nor the `reminders` channel blocked) → authorized; not granted and
     * never asked → notDetermined; anything else → denied.
     */
    private fun currentPermission(): NotificationPermission {
        val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val enabled = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
            !ReminderNotifications.isChannelBlocked(context)
        return when {
            granted && enabled -> NotificationPermission.Authorized
            granted -> NotificationPermission.Denied
            prefs.getBoolean(KEY_ASKED, false) -> NotificationPermission.Denied
            else -> NotificationPermission.NotDetermined
        }
    }

    private fun setAlarm(reminder: Reminder, exact: Boolean) {
        val intent = alarmIntent(reminder.id)
            .putExtra(ReminderNotifications.EXTRA_ID, reminder.id)
            .putExtra(ReminderNotifications.EXTRA_KIND, reminder.kind)
            .putExtra(ReminderNotifications.EXTRA_TITLE, reminder.title)
            .putExtra(ReminderNotifications.EXTRA_BODY, reminder.body)
            .putExtra(ReminderNotifications.EXTRA_SLOT, reminder.slot)
        val operation = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val at = reminder.fireAt.toEpochMilli()
        if (exact) {
            runCatching { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation) }
                .onFailure { alarms.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MILLIS, operation) }
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MILLIS, operation)
        }
    }

    private fun cancelAlarm(id: String) {
        val operation = PendingIntent.getBroadcast(context, 0, alarmIntent(id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)
            ?: return
        alarms.cancel(operation)
        operation.cancel()
    }

    /** The PendingIntent identity of a reminder: an explicit component plus a data URI carrying its id. */
    private fun alarmIntent(id: String) = Intent(context, ReminderAlarmReceiver::class.java)
        .setData(Uri.Builder().scheme("healthbeta-alarm").appendPath(id).build())

    private fun Reminder.record() = ScheduledReminderRecord(
        id, kind, HealthTime.formatInstant(fireAt, ZoneId.systemDefault()), localFireTime, title, body, slot,
    )

    private fun ScheduledReminderRecord.reminder() = Reminder(
        id, kind, OffsetDateTime.parse(fireAt).toInstant(), localFireTime, title, body, slot,
    )

    private companion object {
        const val KEY_ASKED = "notificationPermissionAsked"
        const val WINDOW_MILLIS = 10 * 60 * 1000L
    }
}
