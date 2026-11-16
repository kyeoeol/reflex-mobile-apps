package app.reflex.mobile.platform

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

// Room storage of Health Beta (docs/health/CONTRACT.md §1). Records mirror Core's HealthData field by field.
// Dates/times are the contract's `yyyy-MM-dd` / `HH:mm` strings and instants its ISO strings with an offset
// (never epoch values), so a stored log keeps the offset it was taken in. Every list record has an explicit
// `position` and is sorted by it on load ("latest version = last in list" depends on order).

@Entity(tableName = "medication")
data class MedicationRecord(
    @PrimaryKey val id: String,
    val position: Int,
    val name: String,
    val kind: String,
    val dose: String,
    val notes: String,
    val asNeeded: Boolean,
    val startDate: String,
    val endDate: String?,
)

/** One schedule version. [times] and [weekdays] are comma-separated (`08:00,20:00`, `1,3,5`). */
@Entity(
    tableName = "schedule",
    primaryKeys = ["medicationId", "position"],
    foreignKeys = [ForeignKey(entity = MedicationRecord::class, parentColumns = ["id"], childColumns = ["medicationId"],
        onDelete = ForeignKey.CASCADE)],
)
data class ScheduleRecord(
    val medicationId: String,
    val position: Int,
    val fromDate: String,
    val times: String,
    val weekdays: String,
    val createdAt: String?,
)

@Entity(
    tableName = "archived_period",
    primaryKeys = ["medicationId", "position"],
    foreignKeys = [ForeignKey(entity = MedicationRecord::class, parentColumns = ["id"], childColumns = ["medicationId"],
        onDelete = ForeignKey.CASCADE)],
)
data class ArchivedPeriodRecord(
    val medicationId: String,
    val position: Int,
    val fromDate: String,
    val toDate: String?,
)

/** Logs keep their own name/dose snapshot, so they are not tied to a medication row by a foreign key. */
@Entity(tableName = "dose_log", indices = [Index("medicationId")])
data class DoseLogRecord(
    @PrimaryKey val id: String,
    val position: Int,
    val medicationId: String,
    val date: String,
    val time: String?,
    val status: String,
    val takenAt: String?,
    val name: String,
    val dose: String,
)

@Entity(tableName = "snooze")
data class SnoozeRecord(
    @PrimaryKey val slot: String,
    val position: Int,
    val until: String,
)

/** What the reminder scheduler has handed to AlarmManager (the OS has no API to list a package's alarms). */
@Entity(tableName = "scheduled_reminder")
data class ScheduledReminderRecord(
    @PrimaryKey val id: String,
    val kind: String,
    val fireAt: String,
    val localFireTime: String,
    val title: String,
    val body: String,
    val slot: String?,
)

/** The whole Health data as rows, in list order. */
data class HealthRows(
    val medications: List<MedicationRecord>,
    val schedules: List<ScheduleRecord>,
    val archived: List<ArchivedPeriodRecord>,
    val logs: List<DoseLogRecord>,
    val snoozes: List<SnoozeRecord>,
)

@Dao
abstract class HealthDao {
    @Query("SELECT * FROM medication ORDER BY position") abstract suspend fun medications(): List<MedicationRecord>
    @Query("SELECT * FROM schedule ORDER BY medicationId, position") abstract suspend fun schedules(): List<ScheduleRecord>
    @Query("SELECT * FROM archived_period ORDER BY medicationId, position") abstract suspend fun archived(): List<ArchivedPeriodRecord>
    @Query("SELECT * FROM dose_log ORDER BY position") abstract suspend fun logs(): List<DoseLogRecord>
    @Query("SELECT * FROM snooze ORDER BY position") abstract suspend fun snoozes(): List<SnoozeRecord>

    @Transaction
    open suspend fun load(): HealthRows = HealthRows(medications(), schedules(), archived(), logs(), snoozes())

    @Query("DELETE FROM medication") protected abstract suspend fun deleteMedications()
    @Query("DELETE FROM schedule") protected abstract suspend fun deleteSchedules()
    @Query("DELETE FROM archived_period") protected abstract suspend fun deleteArchived()
    @Query("DELETE FROM dose_log") protected abstract suspend fun deleteLogs()
    @Query("DELETE FROM snooze") protected abstract suspend fun deleteSnoozes()

    @Insert protected abstract suspend fun insertMedications(rows: List<MedicationRecord>)
    @Insert protected abstract suspend fun insertSchedules(rows: List<ScheduleRecord>)
    @Insert protected abstract suspend fun insertArchived(rows: List<ArchivedPeriodRecord>)
    @Insert protected abstract suspend fun insertLogs(rows: List<DoseLogRecord>)
    @Insert protected abstract suspend fun insertSnoozes(rows: List<SnoozeRecord>)

    /** Replaces everything with [rows] in one transaction: a failed save leaves the previous snapshot intact. */
    @Transaction
    open suspend fun replaceAll(rows: HealthRows) {
        deleteSchedules()
        deleteArchived()
        deleteMedications()
        deleteLogs()
        deleteSnoozes()
        insertMedications(rows.medications)
        insertSchedules(rows.schedules)
        insertArchived(rows.archived)
        insertLogs(rows.logs)
        insertSnoozes(rows.snoozes)
    }
}

@Dao
abstract class ScheduledReminderDao {
    @Query("SELECT * FROM scheduled_reminder") abstract suspend fun all(): List<ScheduledReminderRecord>
    @Query("DELETE FROM scheduled_reminder") protected abstract suspend fun deleteAll()
    @Insert protected abstract suspend fun insert(rows: List<ScheduledReminderRecord>)

    @Transaction
    open suspend fun replaceAll(rows: List<ScheduledReminderRecord>) {
        deleteAll()
        insert(rows)
    }
}

/**
 * Schema changes need a real migration (`autoMigrations` or a `Migration`); there is deliberately no destructive
 * fallback, so a schema mismatch surfaces as a load error instead of silently erasing the user's history.
 */
@Database(
    entities = [MedicationRecord::class, ScheduleRecord::class, ArchivedPeriodRecord::class, DoseLogRecord::class,
        SnoozeRecord::class, ScheduledReminderRecord::class],
    version = 1,
    exportSchema = true,
)
abstract class HealthDatabase : RoomDatabase() {
    abstract fun health(): HealthDao
    abstract fun reminders(): ScheduledReminderDao

    companion object {
        const val FILE_NAME = "health-beta.db"

        @Volatile private var instance: HealthDatabase? = null

        /** Built on first use (off Main, from a repository/scheduler call); opening the file happens lazily too. */
        fun get(context: Context): HealthDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, HealthDatabase::class.java, FILE_NAME)
                .build().also { instance = it }
        }
    }
}
