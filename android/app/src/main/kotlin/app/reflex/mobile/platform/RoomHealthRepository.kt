package app.reflex.mobile.platform

import app.reflex.core.features.health.ArchivePeriod
import app.reflex.core.features.health.DoseLog
import app.reflex.core.features.health.HealthData
import app.reflex.core.features.health.HealthTime
import app.reflex.core.features.health.LogStatus
import app.reflex.core.features.health.Medication
import app.reflex.core.features.health.ScheduleVersion
import app.reflex.core.features.health.Snooze
import app.reflex.core.foundation.HealthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * Core's `HealthRepository` on Room. Everything runs on Dispatchers.IO; the database is built on the first call.
 * Any failure to open or read (or a row that doesn't map back to valid data) throws, and Core shows its load
 * error and never saves over the stored data.
 */
class RoomHealthRepository(private val database: () -> HealthDatabase) : HealthRepository {
    override suspend fun load(): HealthData = withContext(Dispatchers.IO) { HealthRowMapping.data(database().health().load()) }

    override suspend fun save(data: HealthData) = withContext(Dispatchers.IO) {
        database().health().replaceAll(HealthRowMapping.rows(data))
    }
}

/** HealthData ⇄ rows. Lists keep their order through `position`. */
internal object HealthRowMapping {
    fun rows(data: HealthData) = HealthRows(
        medications = data.medications.mapIndexed { index, m ->
            MedicationRecord(m.id, index, m.name, m.kind, m.dose, m.notes, m.asNeeded, HealthTime.date(m.startDate),
                m.endDate?.let(HealthTime::date))
        },
        schedules = data.medications.flatMap { m ->
            m.schedules.mapIndexed { index, s ->
                ScheduleRecord(m.id, index, HealthTime.date(s.from), s.times.joinToString(",", transform = HealthTime::time),
                    s.weekdays.joinToString(","), s.createdAt?.let(HealthTime::formatOffsetDateTime))
            }
        },
        archived = data.medications.flatMap { m ->
            m.archived.mapIndexed { index, a -> ArchivedPeriodRecord(m.id, index, HealthTime.date(a.from), a.to?.let(HealthTime::date)) }
        },
        logs = data.logs.mapIndexed { index, l ->
            DoseLogRecord(l.id, index, l.medicationId, HealthTime.date(l.date), l.time?.let(HealthTime::time), l.status.code,
                l.takenAt?.let(HealthTime::formatOffsetDateTime), l.name, l.dose)
        },
        snoozes = data.snoozes.mapIndexed { index, s -> SnoozeRecord(s.slot, index, HealthTime.formatOffsetDateTime(s.until)) },
    )

    fun data(rows: HealthRows): HealthData {
        val schedules = rows.schedules.groupBy { it.medicationId }
        val archived = rows.archived.groupBy { it.medicationId }
        return HealthData(
            version = 1,
            medications = rows.medications.sortedBy { it.position }.map { m ->
                Medication(
                    id = m.id, name = m.name, kind = m.kind, dose = m.dose, notes = m.notes, asNeeded = m.asNeeded,
                    startDate = date(m.startDate), endDate = m.endDate?.let(::date),
                    schedules = schedules[m.id].orEmpty().sortedBy { it.position }.map { s ->
                        ScheduleVersion(
                            from = date(s.fromDate),
                            times = s.times.split(',').filter { it.isNotEmpty() }.map(::time),
                            weekdays = s.weekdays.split(',').filter { it.isNotEmpty() }.map { it.toInt() },
                            createdAt = s.createdAt?.let(::instant),
                        )
                    },
                    archived = archived[m.id].orEmpty().sortedBy { it.position }.map { a ->
                        ArchivePeriod(date(a.fromDate), a.toDate?.let(::date))
                    },
                )
            },
            logs = rows.logs.sortedBy { it.position }.map { l ->
                DoseLog(
                    id = l.id, medicationId = l.medicationId, date = date(l.date), time = l.time?.let(::time),
                    status = LogStatus.entries.firstOrNull { it.code == l.status } ?: error("unknown status '${l.status}'"),
                    takenAt = l.takenAt?.let(::instant), name = l.name, dose = l.dose,
                )
            },
            snoozes = rows.snoozes.sortedBy { it.position }.map { Snooze(it.slot, instant(it.until)) },
        )
    }

    private fun date(text: String): LocalDate = HealthTime.parseDate(text) ?: error("malformed date '$text'")
    private fun time(text: String): LocalTime = HealthTime.parseTime(text) ?: error("malformed time '$text'")
    private fun instant(text: String): OffsetDateTime = OffsetDateTime.parse(text)
}
