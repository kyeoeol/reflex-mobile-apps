package app.reflex.core.features.health

import app.reflex.core.foundation.NotificationPermission
import app.reflex.core.foundation.Reminder
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Pure: (data, now, zone, permission) → the complete desired reminder set (docs/health/CONTRACT.md §1 Reminders).
 * The platform scheduler only reconciles it (diff by id).
 */
object ReminderPlanner {
    val WINDOW: Duration = Duration.ofDays(7)
    val FOLLOW_UP: Duration = Duration.ofMinutes(30)
    const val LIMIT = 60

    fun plan(data: HealthData, now: Instant, zone: ZoneId, permission: NotificationPermission): List<Reminder> {
        if (permission != NotificationPermission.Authorized) return emptyList()
        val days = HealthDays(data)
        val end = now + WINDOW
        // Yesterday's slots can still have a follow-up or snooze ahead; one day past the window's end covers zone edges.
        val first = HealthTime.today(now, zone).minusDays(1)
        val last = HealthTime.today(end, zone).plusDays(1)
        val items = mutableListOf<Reminder>()
        var date = first
        while (date <= last) {
            val unlogged = days.occurrences(date).filter { it.log == null && it.medication != null }
            for ((time, doses) in unlogged.groupBy { it.time }) {
                val slot = Slot(date, time)
                val sorted = doses.sortedWith(compareBy({ nameKey(it.name) }, { it.doseId }))
                val at = HealthTime.resolve(date, time, zone)
                val snooze = data.snoozes.filter { it.slot == slot.key && it.until.toInstant() > now }
                    .maxByOrNull { it.until.toInstant() }
                if (snooze != null) {
                    items += reminder("snooze:${slot.key}", "snooze", snooze.until.toInstant(), zone, slot, sorted)
                } else {
                    items += reminder("dose:${slot.key}", "dose", at, zone, slot, sorted)
                    items += reminder("followup:${slot.key}", "followUp", at + FOLLOW_UP, zone, slot, sorted)
                }
            }
            date = date.plusDays(1)
        }
        val inWindow = items.filter { it.fireAt > now && it.fireAt <= end }.sortedWith(compareBy({ it.fireAt }, { it.id }))
        if (inWindow.size <= LIMIT) return inWindow
        val kept = inWindow.take(LIMIT - 1)
        val refreshAt = kept.last().fireAt + Duration.ofMinutes(1)
        return kept + Reminder(
            id = "refresh", kind = "refresh", fireAt = refreshAt, localFireTime = HealthTime.localDateTime(refreshAt, zone),
            title = "Open Health Beta", body = "Open the app to keep your reminders coming.", slot = null,
        )
    }

    private fun reminder(id: String, kind: String, fireAt: Instant, zone: ZoneId, slot: Slot, doses: List<Occurrence>): Reminder {
        val single = doses.singleOrNull()
        val subject = if (single != null) single.name else "${doses.size} medications"
        val title = if (kind == "followUp") "Still due: $subject" else "Time for $subject"
        val body = doses.joinToString(", ") { if (it.dose.isEmpty()) it.name else "${it.name} (${it.dose})" }
        return Reminder(id, kind, fireAt, HealthTime.localDateTime(fireAt, zone), title, body, slot.key)
    }
}
