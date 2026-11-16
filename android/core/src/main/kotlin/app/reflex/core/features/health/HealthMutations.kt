package app.reflex.core.features.health

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

// Every change to HealthData (docs/health/CONTRACT.md §1 dose actions, reminder responses, §3 editor/detail),
// as pure functions: (data, inputs) → outcome. HealthStore applies the outcome; nothing here has state.

/** The result of one change. [Rejected.message] is the literal the screen shows as `doseError`. */
sealed interface Change {
    data class Applied(val data: HealthData) : Change
    data object NoOp : Change
    data class Rejected(val message: String) : Change
}

/** The clock reading and id source a change runs with. */
class ChangeContext(val now: Instant, val zone: ZoneId, val newId: () -> String) {
    val today: LocalDate get() = HealthTime.today(now, zone)
}

/** A medication as the editor hands it over (already validated). [id] is null when adding. */
data class MedicationDraft(
    val id: String?,
    val name: String,
    val kind: String,
    val dose: String,
    val notes: String,
    val asNeeded: Boolean,
    val times: List<LocalTime>,
    val weekdays: List<Int>,
    val startDate: LocalDate,
    val endDate: LocalDate?,
)

/** A notification action button (`AppRoot.handleReminderResponse`); a plain tap only navigates. */
enum class ReminderResponse { Taken, Skipped, Snooze }

object HealthMutations {
    const val ERROR_FUTURE = "Time can't be in the future"
    const val ERROR_WINDOW = "Time must be within 12 hours of the scheduled dose"
    const val ERROR_FORMAT = "Enter a time as yyyy-MM-ddTHH:mm"
    val SNOOZE: Duration = Duration.ofMinutes(10)

    // Dose actions ------------------------------------------------------------------------------------------

    /** `Take`: taken at now. No-op if already taken, unknown, or a `log:` id. */
    fun take(data: HealthData, doseId: String, context: ChangeContext): Change {
        val occurrence = occurrence(data, doseId, context) ?: return Change.NoOp
        if (occurrence.log?.status == LogStatus.Taken) return Change.NoOp
        return logTaken(data, occurrence, context.now, context)
    }

    /** `TakeAt`: taken at a local date-time (resolved in the current zone); also corrects an existing time. */
    fun takeAt(data: HealthData, doseId: String, takenAt: String, context: ChangeContext): Change {
        val occurrence = occurrence(data, doseId, context) ?: return Change.NoOp
        val local = HealthTime.parseLocalDateTime(takenAt) ?: return Change.Rejected(ERROR_FORMAT)
        return logTaken(data, occurrence, HealthTime.resolve(local.date, local.time, context.zone), context)
    }

    /** `Skip`. No-op if already skipped. */
    fun skip(data: HealthData, doseId: String, context: ChangeContext): Change {
        val occurrence = occurrence(data, doseId, context) ?: return Change.NoOp
        if (occurrence.log?.status == LogStatus.Skipped) return Change.NoOp
        return Change.Applied(upsert(data, occurrence, LogStatus.Skipped, null, context))
    }

    /** `Undo`: removes the dose's log; for `log:<logId>` removes that as-needed log. No-op without a log. */
    fun undo(data: HealthData, doseId: String, context: ChangeContext): Change {
        val log = when (val ref = DoseRef.parse(doseId)) {
            is DoseRef.Scheduled -> HealthDays(data, context.zone).scheduledLog(ref.medicationId, ref.slot.date, ref.slot.time)
            is DoseRef.AsNeeded -> data.logs.firstOrNull { it.id == ref.logId && it.time == null }
            null -> null
        } ?: return Change.NoOp
        return Change.Applied(data.copy(logs = data.logs.filter { it !== log }))
    }

    /** `LogAsNeeded`: an as-needed log at now. No-op for unknown or scheduled medications. */
    fun logAsNeeded(data: HealthData, medicationId: String, context: ChangeContext): Change {
        val medication = data.medications.firstOrNull { it.id == medicationId && it.asNeeded } ?: return Change.NoOp
        val takenAt = HealthTime.offsetDateTime(context.now, context.zone)
        val log = DoseLog(
            id = context.newId(), medicationId = medication.id, date = takenAt.toLocalDate(), time = null,
            status = LogStatus.Taken, takenAt = takenAt, name = medication.name, dose = medication.dose,
        )
        return Change.Applied(data.copy(logs = data.logs + log))
    }

    /** `UndoAsNeeded`: removes today's latest as-needed log of the medication. No-op if there is none. */
    fun undoAsNeeded(data: HealthData, medicationId: String, context: ChangeContext): Change {
        val latest = data.logs.withIndex()
            .filter { (_, log) -> log.medicationId == medicationId && log.time == null && log.date == context.today }
            .maxWithOrNull(compareBy({ it.value.takenAt?.toInstant() }, { it.index }))
            ?: return Change.NoOp
        return Change.Applied(data.copy(logs = data.logs.filterIndexed { i, _ -> i != latest.index }))
    }

    // Notification responses -----------------------------------------------------------------------------------

    /**
     * A reminder's action button: taken / skipped log every unlogged dose of the slot (taken leaves doses that fail
     * the 12 h rule alone); snooze moves the slot's reminder to now + 10 min unless that is ≥ T + 120 min.
     * A slot without an unlogged dose ignores all of them.
     */
    fun respond(data: HealthData, slot: Slot, response: ReminderResponse, context: ChangeContext): Change {
        val unlogged = HealthDays(data, context.zone).occurrences(slot.date).filter { it.time == slot.time && it.log == null && it.medication != null }
        if (unlogged.isEmpty()) return Change.NoOp
        return when (response) {
            ReminderResponse.Taken -> {
                val eligible = unlogged.filter { validateTaken(it, context.now, context) == null }
                if (eligible.isEmpty()) Change.NoOp
                else Change.Applied(eligible.fold(data) { acc, occurrence -> upsert(acc, occurrence, LogStatus.Taken, context.now, context) })
            }
            ReminderResponse.Skipped ->
                Change.Applied(unlogged.fold(data) { acc, occurrence -> upsert(acc, occurrence, LogStatus.Skipped, null, context) })
            ReminderResponse.Snooze -> {
                val until = context.now + SNOOZE
                if (until >= HealthTime.resolve(slot.date, slot.time, context.zone) + HealthTime.MISSED_AFTER) {
                    Change.NoOp
                } else {
                    val kept = data.snoozes.filter { it.slot != slot.key && it.until.toInstant() > context.now }
                    Change.Applied(data.copy(snoozes = kept + Snooze(slot.key, HealthTime.offsetDateTime(until, context.zone))))
                }
            }
        }
    }

    // Medications --------------------------------------------------------------------------------------------

    /**
     * Adds or edits a medication (the editor validated it). A new medication gets one version from its start date.
     * Editing the times/weekdays adds a version from today, or from tomorrow when the medication already has a log
     * dated today (replacing a version that starts on that day); history is never rewritten. As-needed: no
     * schedules. Returns the data and the medication's id.
     */
    fun saveMedication(data: HealthData, draft: MedicationDraft, context: ChangeContext): Pair<HealthData, String>? {
        val today = context.today
        val times = draft.times.distinct().sorted()
        val weekdays = draft.weekdays.distinct().sorted()
        val existing = draft.id?.let { id -> data.medications.firstOrNull { it.id == id } }
        if (draft.id != null && existing == null) return null
        val createdAt = HealthTime.offsetDateTime(context.now, context.zone)
        val schedules = when {
            existing != null && existing.asNeeded -> emptyList()
            draft.asNeeded -> emptyList()
            existing == null -> listOf(ScheduleVersion(draft.startDate, times, weekdays, createdAt))
            else -> {
                val latest = existing.latestVersion
                val unchanged = latest != null &&
                    latest.times.distinct().sorted() == times && latest.weekdays.distinct().sorted() == weekdays
                if (unchanged) {
                    existing.schedules
                } else {
                    val loggedToday = HealthDays(data, context.zone).hasLog(existing.id, today)
                    val from = if (loggedToday) today.plusDays(1) else today
                    existing.schedules.filter { it.from != from } + ScheduleVersion(from, times, weekdays, createdAt)
                }
            }
        }
        val medication = Medication(
            id = existing?.id ?: context.newId(),
            name = draft.name.trim(),
            kind = draft.kind,
            dose = draft.dose,
            notes = draft.notes,
            asNeeded = existing?.asNeeded ?: draft.asNeeded,
            startDate = draft.startDate,
            endDate = draft.endDate,
            schedules = schedules,
            archived = existing?.archived.orEmpty(),
        )
        val medications = if (existing == null) data.medications + medication
        else data.medications.map { if (it.id == medication.id) medication else it }
        return data.copy(medications = medications) to medication.id
    }

    /** Adds `{from: today, to: null}`. No-op if already archived. */
    fun archive(data: HealthData, id: String, context: ChangeContext): Change {
        val medication = data.medications.firstOrNull { it.id == id } ?: return Change.NoOp
        if (medication.status(context.today) == MedicationStatus.Archived) return Change.NoOp
        return replace(data, medication.copy(archived = medication.archived + ArchivePeriod(context.today, null)))
    }

    /**
     * Closes the open period with `to` = yesterday (dropping it if that is before its `from`) and appends a version
     * from today with the latest times/weekdays, so doses resume from today. No-op if not archived.
     */
    fun restore(data: HealthData, id: String, context: ChangeContext): Change {
        val medication = data.medications.firstOrNull { it.id == id } ?: return Change.NoOp
        if (medication.archived.none { it.to == null }) return Change.NoOp
        val yesterday = context.today.minusDays(1)
        val periods = medication.archived.mapNotNull { period ->
            when {
                period.to != null -> period
                yesterday < period.from -> null
                else -> period.copy(to = yesterday)
            }
        }
        val latest = medication.latestVersion
        val schedules = if (medication.asNeeded || latest == null) medication.schedules
        else medication.schedules + ScheduleVersion(
            context.today, latest.times, latest.weekdays, HealthTime.offsetDateTime(context.now, context.zone),
        )
        return replace(data, medication.copy(archived = periods, schedules = schedules))
    }

    /** Removes the medication and all its logs. */
    fun delete(data: HealthData, id: String): Change {
        if (data.medications.none { it.id == id }) return Change.NoOp
        return Change.Applied(data.copy(medications = data.medications.filter { it.id != id }, logs = data.logs.filter { it.medicationId != id }))
    }

    // Helpers ------------------------------------------------------------------------------------------------

    /** The occurrence a scheduled dose id points at, or null (malformed, `log:` id, not an occurrence). */
    private fun occurrence(data: HealthData, doseId: String, context: ChangeContext): Occurrence? {
        val ref = DoseRef.parse(doseId) as? DoseRef.Scheduled ?: return null
        return HealthDays(data, context.zone).occurrence(ref.medicationId, ref.slot.date, ref.slot.time)
    }

    /** null when [takenAt] is allowed for [occurrence], else the literal error. */
    private fun validateTaken(occurrence: Occurrence, takenAt: Instant, context: ChangeContext): String? {
        val at = occurrence.scheduledAt(context.zone)
        return when {
            takenAt > context.now -> ERROR_FUTURE
            takenAt < at - HealthTime.TAKEN_WINDOW || takenAt > at + HealthTime.TAKEN_WINDOW -> ERROR_WINDOW
            else -> null
        }
    }

    private fun logTaken(data: HealthData, occurrence: Occurrence, takenAt: Instant, context: ChangeContext): Change {
        validateTaken(occurrence, takenAt, context)?.let { return Change.Rejected(it) }
        return Change.Applied(upsert(data, occurrence, LogStatus.Taken, takenAt, context))
    }

    /** At most one log per (medicationId, date, time): an existing log keeps its id and its name/dose snapshot. */
    private fun upsert(data: HealthData, occurrence: Occurrence, status: LogStatus, takenAt: Instant?, context: ChangeContext): HealthData {
        val stamp = takenAt?.let { HealthTime.offsetDateTime(it, context.zone) }
        val existing = occurrence.log
        if (existing != null) {
            return data.copy(logs = data.logs.map { if (it === existing) it.copy(status = status, takenAt = stamp) else it })
        }
        val log = DoseLog(
            id = context.newId(), medicationId = occurrence.medicationId, date = occurrence.date, time = occurrence.time,
            status = status, takenAt = stamp, name = occurrence.name, dose = occurrence.dose,
        )
        return data.copy(logs = data.logs + log)
    }

    private fun replace(data: HealthData, medication: Medication): Change =
        Change.Applied(data.copy(medications = data.medications.map { if (it.id == medication.id) medication else it }))
}
