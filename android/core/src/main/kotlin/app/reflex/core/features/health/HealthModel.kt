@file:UseSerializers(LocalDateSerializer::class, LocalTimeSerializer::class, OffsetDateTimeSerializer::class)

package app.reflex.core.features.health

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale

// Health Beta domain (docs/health/CONTRACT.md §1): data types, JSON coding of `health.data`, time resolution and
// formatting, name ordering, and the pure functions every screen is computed from. No state, no I/O.

// Data ---------------------------------------------------------------------------------------------------------

/** Everything Health Beta stores. Persisted as one value through the `HealthRepository` edge. */
@Serializable
data class HealthData(
    val version: Int = 1,
    val medications: List<Medication> = emptyList(),
    val logs: List<DoseLog> = emptyList(),
    val snoozes: List<Snooze> = emptyList(),
)

@Serializable
data class Medication(
    val id: String,
    val name: String,
    val kind: String = KIND_MEDICATION,
    val dose: String = "",
    val notes: String = "",
    val asNeeded: Boolean = false,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    /** Append-only versions; see [versionOn]. As-needed medications have none. The last one is the latest. */
    val schedules: List<ScheduleVersion> = emptyList(),
    val archived: List<ArchivePeriod> = emptyList(),
) {
    init {
        require(kind in KINDS) { "unknown kind '$kind'" }
        require('@' !in id) { "ids never contain '@'" }
    }

    companion object {
        const val KIND_MEDICATION = "medication"
        const val KIND_SUPPLEMENT = "supplement"
        val KINDS = setOf(KIND_MEDICATION, KIND_SUPPLEMENT)
    }
}

/** One schedule version. [createdAt]: when it was saved (null for data written before v2). */
@Serializable
data class ScheduleVersion(
    val from: LocalDate,
    val times: List<LocalTime> = emptyList(),
    val weekdays: List<Int> = emptyList(),
    val createdAt: OffsetDateTime? = null,
) {
    init {
        require(weekdays.all { it in 1..7 }) { "weekdays are 1 (Monday) … 7 (Sunday)" }
    }
}

/** `to == null`: still archived. */
@Serializable
data class ArchivePeriod(val from: LocalDate, val to: LocalDate? = null)

@Serializable
enum class LogStatus(val code: String) {
    @SerialName("taken") Taken("taken"),
    @SerialName("skipped") Skipped("skipped"),
}

/**
 * Scheduled logs have [date] + [time] (the occurrence they belong to); as-needed logs have `time == null` and
 * `date` = local date of [takenAt]. [name]/[dose] are snapshots taken when logged.
 */
@Serializable
data class DoseLog(
    val id: String,
    val medicationId: String,
    val date: LocalDate,
    val time: LocalTime? = null,
    val status: LogStatus,
    val takenAt: OffsetDateTime? = null,
    val name: String = "",
    val dose: String = "",
) {
    init {
        require('@' !in id) { "ids never contain '@'" }
        require(status == LogStatus.Skipped || takenAt != null) { "a taken log needs takenAt" }
        require(time != null || status == LogStatus.Taken) { "as-needed logs are always taken" }
    }
}

@Serializable
data class Snooze(val slot: String, val until: OffsetDateTime) {
    init {
        require(Slot.parse(slot) != null) { "malformed snooze slot '$slot'" }
    }
}

/** A reminder slot: one local date + time. Its key is `"<date>T<time>"`. */
data class Slot(val date: LocalDate, val time: LocalTime) {
    val key: String get() = "${HealthTime.date(date)}T${HealthTime.time(time)}"

    companion object {
        fun parse(key: String): Slot? = HealthTime.parseLocalDateTime(key)
    }
}

/**
 * JSON coding of `storage["health.data"]`: unknown keys ignored, missing optional keys defaulted, and anything
 * semantically invalid (malformed dates/times, weekdays outside 1–7, unknown kind/status, …) rejected.
 */
object HealthCoding {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // An explicit `null` for an optional key means "missing" (its default), like Swift's `decodeIfPresent`.
        coerceInputValues = true
    }

    /** Empty or `null` → empty data. Throws on anything that isn't a valid `health.data` value. */
    fun decode(text: String?): HealthData {
        if (text == null || text.isBlank() || text.trim() == "null") return HealthData()
        return json.decodeFromString(HealthData.serializer(), text)
    }

    fun encode(data: HealthData): String = json.encodeToString(HealthData.serializer(), data)
}

// Time ---------------------------------------------------------------------------------------------------------

object HealthTime {
    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val TIME = Regex("""([01]\d|2[0-3]):[0-5]\d""")
    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)
    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val LOCAL_DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm", Locale.ROOT)

    /** Seconds and a numeric offset, never `Z` (`xxx` prints `+00:00` for UTC). */
    private val INSTANT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx", Locale.ROOT)

    val MISSED_AFTER: Duration = Duration.ofMinutes(120)
    val TIMING_TOLERANCE: Duration = Duration.ofMinutes(60)
    val TAKEN_WINDOW: Duration = Duration.ofHours(12)

    /** `yyyy-MM-dd`, strictly (no other shapes, real calendar dates only). */
    fun parseDate(text: String): LocalDate? =
        if (DATE.matches(text)) runCatching { LocalDate.parse(text) }.getOrNull() else null

    /** `HH:mm`, 24 h. */
    fun parseTime(text: String): LocalTime? =
        if (TIME.matches(text)) LocalTime.of(text.substring(0, 2).toInt(), text.substring(3, 5).toInt()) else null

    /** `yyyy-MM-ddTHH:mm`. */
    fun parseLocalDateTime(text: String): Slot? {
        val separator = text.indexOf('T')
        if (separator < 0) return null
        val date = parseDate(text.substring(0, separator)) ?: return null
        val time = parseTime(text.substring(separator + 1)) ?: return null
        return Slot(date, time)
    }

    fun date(date: LocalDate): String = DATE_FORMAT.format(date)
    fun time(time: LocalTime): String = TIME_FORMAT.format(time)

    /**
     * The only rule for turning a local date + time into an instant: a time in a spring-forward gap is shifted
     * forward by the gap's length; a time in a fall-back overlap takes the earlier instant.
     * (`ZonedDateTime.of` implements exactly this.)
     */
    fun resolve(date: LocalDate, time: LocalTime, zone: ZoneId): Instant = ZonedDateTime.of(date, time, zone).toInstant()

    /** An instant with the offset [zone] has at that instant: `2026-11-16T08:00:00+09:00`. */
    fun formatInstant(instant: Instant, zone: ZoneId): String = INSTANT_FORMAT.format(instant.atZone(zone))

    fun formatOffsetDateTime(value: OffsetDateTime): String = INSTANT_FORMAT.format(value)

    /** The resolved local wall time `yyyy-MM-ddTHH:mm` of [instant] in [zone]. */
    fun localDateTime(instant: Instant, zone: ZoneId): String = LOCAL_DATE_TIME_FORMAT.format(instant.atZone(zone))

    /** `HH:mm` in the offset stored with the value. */
    fun clockTime(value: OffsetDateTime): String = TIME_FORMAT.format(value)

    /** For storing `now` (or a user-entered time): whole seconds, with the offset [zone] has at that instant. */
    fun offsetDateTime(instant: Instant, zone: ZoneId): OffsetDateTime =
        instant.truncatedTo(ChronoUnit.SECONDS).atZone(zone).toOffsetDateTime()

    fun today(now: Instant, zone: ZoneId): LocalDate = LocalDate.ofInstant(now, zone)
}

// Names --------------------------------------------------------------------------------------------------------

/** The sort/compare key of a name: lowercased with the Unicode default case mapping (`Locale.ROOT`). */
fun nameKey(name: String): String = name.lowercase(Locale.ROOT)

/** Compares two strings by Unicode code point sequence (not UTF-16 units, not locale collation). */
fun compareCodePoints(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a.codePointAt(i)
        val cb = b.codePointAt(j)
        if (ca != cb) return ca.compareTo(cb)
        i += Character.charCount(ca)
        j += Character.charCount(cb)
    }
    return (i < a.length).compareTo(j < b.length)
}

/** "By name" everywhere: the name key by code points, then [id]. */
fun <T> byName(name: (T) -> String, id: (T) -> String): Comparator<T> =
    Comparator<T> { x, y -> compareCodePoints(nameKey(name(x)), nameKey(name(y))) }.thenBy(id)

/** The duplicate-name key: trimmed of Unicode whitespace, then lowercased. */
fun duplicateKey(name: String): String = nameKey(name.trim())

// Medications --------------------------------------------------------------------------------------------------

enum class MedicationStatus(val code: String) { Active("active"), Ended("ended"), Archived("archived") }

/** The latest version: the last one in the list. */
val Medication.latestVersion: ScheduleVersion? get() = schedules.lastOrNull()

/** The version in force on [date]: the latest `from ≤ date` (ties: the later one in the list), else the earliest. */
fun Medication.versionOn(date: LocalDate): ScheduleVersion? {
    var best: ScheduleVersion? = null
    for (version in schedules) if (version.from <= date && (best == null || version.from >= best.from)) best = version
    return best ?: schedules.minByOrNull { it.from }
}

fun Medication.isArchivedOn(date: LocalDate): Boolean =
    archived.any { it.from <= date && (it.to == null || date <= it.to) }

fun Medication.status(today: LocalDate): MedicationStatus = when {
    archived.any { it.to == null } -> MedicationStatus.Archived
    endDate != null && endDate < today -> MedicationStatus.Ended
    else -> MedicationStatus.Active
}

/**
 * The times this medication has an occurrence at on [date]: none for as-needed, outside its dates or on archived
 * days; and on the day a version was created, none whose dose would already have been missed at creation.
 */
fun Medication.scheduledTimes(date: LocalDate, zone: ZoneId): List<LocalTime> {
    if (asNeeded || date < startDate || (endDate != null && date > endDate) || isArchivedOn(date)) return emptyList()
    val version = versionOn(date) ?: return emptyList()
    if (date.dayOfWeek.value !in version.weekdays) return emptyList()
    val createdAt = version.createdAt?.toInstant()
    val createdToday = createdAt != null && version.from == date && HealthTime.today(createdAt, zone) == date
    return version.times.distinct().filter { time ->
        !(createdToday && HealthTime.resolve(date, time, zone) + HealthTime.MISSED_AFTER <= createdAt)
    }
}

/** True when the latest version has times (what "has times" means for banners and the permission request). */
fun Medication.hasTimes(): Boolean = !asNeeded && latestVersion?.times.orEmpty().isNotEmpty()

private val WEEKDAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** `"As needed"`, `"Every day at 08:00, 20:00"`, `"Mon, Wed, Fri at 13:00"`, from the latest version. */
fun Medication.scheduleSummary(): String {
    if (asNeeded) return "As needed"
    val version = latestVersion
    val weekdays = version?.weekdays.orEmpty().distinct().sorted()
    val times = version?.times.orEmpty().distinct().sorted().joinToString(", ") { HealthTime.time(it) }
    val days = if (weekdays == (1..7).toList()) "Every day" else weekdays.joinToString(", ") { WEEKDAY_NAMES[it - 1] }
    return "$days at $times"
}

// Occurrences and doses ------------------------------------------------------------------------------------------

enum class DoseStatus(val code: String) {
    Upcoming("upcoming"), Due("due"), Taken("taken"), Skipped("skipped"), Missed("missed"),
}

/** One scheduled dose of one medication (computed, never stored). [medication] is null only for orphan logs. */
data class Occurrence(
    val medicationId: String,
    val date: LocalDate,
    val time: LocalTime,
    val medication: Medication?,
    val log: DoseLog?,
) {
    val slot: Slot get() = Slot(date, time)
    val doseId: String get() = "$medicationId@${slot.key}"
    val name: String get() = log?.name ?: medication?.name.orEmpty()
    val dose: String get() = log?.dose ?: medication?.dose.orEmpty()

    fun scheduledAt(zone: ZoneId): Instant = HealthTime.resolve(date, time, zone)

    fun status(now: Instant, zone: ZoneId): DoseStatus {
        when (log?.status) {
            LogStatus.Taken -> return DoseStatus.Taken
            LogStatus.Skipped -> return DoseStatus.Skipped
            null -> Unit
        }
        val at = scheduledAt(zone)
        return when {
            now < at -> DoseStatus.Upcoming
            now < at + HealthTime.MISSED_AFTER -> DoseStatus.Due
            else -> DoseStatus.Missed
        }
    }

    /** `early` / `late` / `onTime` for taken doses, else null. */
    fun timing(zone: ZoneId): String? {
        val takenAt = log?.takenAt?.toInstant() ?: return null
        if (log.status != LogStatus.Taken) return null
        val at = scheduledAt(zone)
        return when {
            takenAt < at - HealthTime.TIMING_TOLERANCE -> "early"
            takenAt > at + HealthTime.TIMING_TOLERANCE -> "late"
            else -> "onTime"
        }
    }

    companion object {
        /** Doses of one slot / one list "by name": name key, then medication id. */
        val BY_NAME: Comparator<Occurrence> = byName({ it.name }, { it.medicationId })
    }
}

/**
 * Indexed view of one [HealthData] value in one time zone: which doses exist on a day, and their logs.
 * Built once per snapshot (see `HealthSnapshot.days`).
 */
class HealthDays(val data: HealthData, val zone: ZoneId) {
    val medications: Map<String, Medication> = data.medications.associateBy { it.id }
    private val logsByDate: Map<LocalDate, List<DoseLog>> = data.logs.groupBy { it.date }
    private val cache = HashMap<LocalDate, List<Occurrence>>()

    fun scheduledLog(medicationId: String, date: LocalDate, time: LocalTime): DoseLog? =
        logsByDate[date].orEmpty().firstOrNull { it.medicationId == medicationId && it.time == time }

    /** The schedule's occurrences on [date] plus every scheduled log on that day (editing never rewrites history). */
    fun occurrences(date: LocalDate): List<Occurrence> = cache.getOrPut(date) {
        val logs = logsByDate[date].orEmpty().filter { it.time != null }
        val result = LinkedHashMap<Pair<String, LocalTime>, Occurrence>()
        for (medication in data.medications) {
            for (time in medication.scheduledTimes(date, zone)) {
                result[medication.id to time] = Occurrence(medication.id, date, time, medication, null)
            }
        }
        for (log in logs) {
            val key = log.medicationId to log.time!!
            if (key !in result) result[key] = Occurrence(log.medicationId, date, log.time, medications[log.medicationId], null)
        }
        result.values.map { occurrence ->
            occurrence.copy(log = logs.firstOrNull { it.medicationId == occurrence.medicationId && it.time == occurrence.time })
        }
    }

    fun occurrence(medicationId: String, date: LocalDate, time: LocalTime): Occurrence? =
        occurrences(date).firstOrNull { it.medicationId == medicationId && it.time == time }

    /** As-needed logs dated [date]. */
    fun asNeededLogs(date: LocalDate): List<DoseLog> = logsByDate[date].orEmpty().filter { it.time == null }

    /** Any log of [medicationId] dated [date] (scheduled or as-needed). */
    fun hasLog(medicationId: String, date: LocalDate): Boolean = logsByDate[date].orEmpty().any { it.medicationId == medicationId }
}

/** The dose object every list shows (Today, History, MedicationDetail), already formatted. */
data class DoseItem(
    val id: String,
    val medicationId: String,
    val name: String,
    val dose: String,
    val date: String,
    val time: String?,
    val status: String,
    val needsAction: Boolean,
    val takenAt: String?,
    val timing: String?,
    val canTake: Boolean,
    val canTakeAt: Boolean,
    val canSkip: Boolean,
    val canUndo: Boolean,
) {
    companion object {
        fun of(occurrence: Occurrence, now: Instant, zone: ZoneId): DoseItem {
            val status = occurrence.status(now, zone)
            val at = occurrence.scheduledAt(zone)
            val inWindow = at - HealthTime.TAKEN_WINDOW <= now && now <= at + HealthTime.TAKEN_WINDOW
            return DoseItem(
                id = occurrence.doseId,
                medicationId = occurrence.medicationId,
                name = occurrence.name,
                dose = occurrence.dose,
                date = HealthTime.date(occurrence.date),
                time = HealthTime.time(occurrence.time),
                status = status.code,
                needsAction = status == DoseStatus.Due || status == DoseStatus.Missed,
                takenAt = occurrence.log?.takenAt?.let(HealthTime::clockTime),
                timing = occurrence.timing(zone),
                canTake = status != DoseStatus.Taken && inWindow,
                canTakeAt = true,
                canSkip = status != DoseStatus.Skipped,
                canUndo = occurrence.log != null,
            )
        }

        /** An as-needed log entry (`log:<logId>`). */
        fun of(log: DoseLog): DoseItem = DoseItem(
            id = "$AS_NEEDED_PREFIX${log.id}",
            medicationId = log.medicationId,
            name = log.name,
            dose = log.dose,
            date = HealthTime.date(log.date),
            time = null,
            status = DoseStatus.Taken.code,
            needsAction = false,
            takenAt = log.takenAt?.let(HealthTime::clockTime),
            timing = null,
            canTake = false,
            canTakeAt = false,
            canSkip = false,
            canUndo = true,
        )

        const val AS_NEEDED_PREFIX = "log:"
    }
}

/** What a dose id points at: `"<medicationId>@<date>T<time>"` or `"log:<logId>"`. */
sealed interface DoseRef {
    data class Scheduled(val medicationId: String, val slot: Slot) : DoseRef
    data class AsNeeded(val logId: String) : DoseRef

    companion object {
        fun parse(id: String): DoseRef? {
            if (id.startsWith(DoseItem.AS_NEEDED_PREFIX)) return AsNeeded(id.removePrefix(DoseItem.AS_NEEDED_PREFIX))
            val at = id.lastIndexOf('@')
            if (at <= 0) return null
            val slot = Slot.parse(id.substring(at + 1)) ?: return null
            return Scheduled(id.substring(0, at), slot)
        }
    }
}

/** `"<taken> of <scheduled> taken"`, or null when there is nothing scheduled. */
fun progressLabel(taken: Int, scheduled: Int): String? = if (scheduled == 0) null else "$taken of $scheduled taken"

/** Round-half-up percentage in integer arithmetic: (200·taken + d) / (2·d). Null when d is 0. */
fun adherencePercent(taken: Int, resolved: Int): Int? = if (resolved == 0) null else (200 * taken + resolved) / (2 * resolved)

// JSON serializers for the stored formats ---------------------------------------------------------------------

/** `yyyy-MM-dd`. */
object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("health.LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(HealthTime.date(value))
    override fun deserialize(decoder: Decoder): LocalDate = decoder.decodeString().let {
        HealthTime.parseDate(it) ?: throw IllegalArgumentException("malformed date '$it'")
    }
}

/** `HH:mm`. */
object LocalTimeSerializer : KSerializer<LocalTime> {
    override val descriptor = PrimitiveSerialDescriptor("health.LocalTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalTime) = encoder.encodeString(HealthTime.time(value))
    override fun deserialize(decoder: Decoder): LocalTime = decoder.decodeString().let {
        HealthTime.parseTime(it) ?: throw IllegalArgumentException("malformed time '$it'")
    }
}

/** ISO 8601 with a numeric offset; written with seconds and never `Z`. */
object OffsetDateTimeSerializer : KSerializer<OffsetDateTime> {
    override val descriptor = PrimitiveSerialDescriptor("health.OffsetDateTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: OffsetDateTime) = encoder.encodeString(HealthTime.formatOffsetDateTime(value))
    override fun deserialize(decoder: Decoder): OffsetDateTime = decoder.decodeString().let {
        try {
            OffsetDateTime.parse(it)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("malformed instant '$it'")
        }
    }
}
