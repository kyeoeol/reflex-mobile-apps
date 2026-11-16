package app.reflex.mobile.features.health

import android.text.format.DateFormat
import app.reflex.mobile.designsystem.DoseStatusKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Presentation only: dates are formatted with the device locale from Core's `yyyy-MM-dd` strings (as calendar
 * dates, never instants); times stay Core's verbatim 24-hour `HH:mm`. Payloads sent back to Core are built with
 * the ISO calendar and `Locale.ROOT`. Status, kind and timing values map to fixed labels; nothing is computed.
 */
object HealthFormat {
    private val PAYLOAD_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)

    fun calendarDate(text: String): LocalDate? = runCatching { LocalDate.parse(text, PAYLOAD_DATE) }.getOrNull()

    /** `2026-11-17` → "Nov 17, 2026" (device locale). */
    fun date(text: String): String =
        calendarDate(text)?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())) ?: text

    /** `2026-11-17` → "Tuesday, November 17": weekday, month and day, no year (the device locale's own order). */
    fun dayDate(text: String): String {
        val locale = Locale.getDefault()
        val pattern = DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd")
        val date = calendarDate(text) ?: return text
        return runCatching { date.format(DateTimeFormatter.ofPattern(pattern, locale)) }
            .getOrElse { date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)) }
    }

    fun payloadDate(date: LocalDate): String = PAYLOAD_DATE.format(date)

    fun payloadTime(hour: Int, minute: Int): String = String.format(Locale.ROOT, "%02d:%02d", hour, minute)

    /** `TakeAt`'s local date-time `yyyy-MM-ddTHH:mm`. */
    fun payloadDateTime(date: LocalDate, time: LocalTime): String = "${payloadDate(date)}T${payloadTime(time.hour, time.minute)}"

    fun time(text: String?): LocalTime? = text?.let {
        runCatching { LocalTime.of(it.substring(0, 2).toInt(), it.substring(3, 5).toInt()) }.getOrNull()
    }

    /** Material date pickers speak UTC midnight millis of the calendar date. */
    fun pickerMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun pickerDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate()

    /** A pending reminder's wall time from the `fireAt` string's own fields (no device-zone conversion). */
    fun reminderTime(fireAt: String): String =
        if (fireAt.length >= 16) "${date(fireAt.substring(0, 10))} · ${fireAt.substring(11, 16)}" else fireAt

    fun status(code: String): DoseStatusKind = when (code) {
        "due" -> DoseStatusKind.Due
        "taken" -> DoseStatusKind.Taken
        "skipped" -> DoseStatusKind.Skipped
        "missed" -> DoseStatusKind.Missed
        else -> DoseStatusKind.Upcoming
    }

    fun timing(code: String?): String? = when (code) {
        "early" -> "Early"
        "late" -> "Late"
        "onTime" -> "On time"
        else -> null
    }

    fun kind(code: String): String = when (code) {
        "supplement" -> "Supplement"
        else -> "Medication"
    }

    fun medicationStatus(code: String): String = when (code) {
        "ended" -> "Ended"
        "archived" -> "Archived"
        else -> "Active"
    }

}
