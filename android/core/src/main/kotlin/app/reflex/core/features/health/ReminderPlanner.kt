package app.reflex.core.features.health

import app.reflex.core.foundation.NotificationPermission
import app.reflex.core.foundation.Reminder
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Pure: (data, now, zone, permission) → the complete desired reminder set and the open slots
 * (docs/health/CONTRACT.md §1 Reminders). The platform scheduler only reconciles it (diff by id).
 */
object ReminderPlanner {
    val WINDOW: Duration = Duration.ofDays(7)
    val FOLLOW_UP: Duration = Duration.ofMinutes(30)
    const val LIMIT = 60
    const val REFRESH_ID = "refresh"

    /**
     * [reminders]: what should be pending. [openSlots]: keys of the slots with at least one unlogged dose whose
     * missed time hasn't come (`now < T + 120 min`); delivered notifications of any other slot are stale.
     */
    data class Plan(val reminders: List<Reminder>, val openSlots: Set<String>)

    fun plan(data: HealthData, now: Instant, zone: ZoneId, permission: NotificationPermission): Plan {
        val days = HealthDays(data, zone)
        val end = now + WINDOW
        // Yesterday's slots can still have a follow-up or snooze ahead; slots through today + 8 days cover the
        // window's end (and tell whether anything lies beyond it).
        val today = HealthTime.today(now, zone)
        val first = today.minusDays(1)
        val last = today.plusDays(8)
        val items = mutableListOf<Reminder>()
        val openSlots = linkedSetOf<String>()
        var date = first
        while (date <= last) {
            val unlogged = days.occurrences(date).filter { it.log == null && it.medication != null }
            for ((time, doses) in unlogged.groupBy { it.time }) {
                val slot = Slot(date, time)
                val sorted = doses.sortedWith(Occurrence.BY_NAME)
                val at = HealthTime.resolve(date, time, zone)
                if (now < at + HealthTime.MISSED_AFTER) openSlots += slot.key
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
        if (permission != NotificationPermission.Authorized) return Plan(emptyList(), openSlots)
        val inWindow = items.filter { it.fireAt > now && it.fireAt <= end }.sortedWith(compareBy({ it.fireAt }, { it.id }))
        val reminders = when {
            inWindow.size > LIMIT -> inWindow.take(LIMIT - 1).let { kept -> kept + refresh(kept.last().fireAt + Duration.ofMinutes(1), zone) }
            items.any { it.fireAt > end } -> inWindow + refresh(end, zone)
            else -> inWindow
        }
        return Plan(reminders, openSlots)
    }

    private fun refresh(fireAt: Instant, zone: ZoneId) = Reminder(
        id = REFRESH_ID, kind = "refresh", fireAt = fireAt, localFireTime = HealthTime.localDateTime(fireAt, zone),
        title = "Open Health Beta", body = "Open the app to keep your reminders coming.", slot = null,
    )

    private fun reminder(id: String, kind: String, fireAt: Instant, zone: ZoneId, slot: Slot, doses: List<Occurrence>): Reminder {
        val single = doses.singleOrNull()
        val subject = if (single != null) single.name else "${doses.size} medications"
        val title = if (kind == "followUp") "Still due: $subject" else "Time for $subject"
        val body = doses.joinToString(", ") { if (it.dose.isEmpty()) it.name else "${it.name} (${it.dose})" }
        return Reminder(id, kind, fireAt, HealthTime.localDateTime(fireAt, zone), title, body, slot.key)
    }
}
