import AppCore
import Foundation
import SwiftData

// The Health data on the device (docs/health/CONTRACT.md §1 Data): SwiftData records mirroring Core's `HealthData`.
// Every list keeps an explicit `position` (SwiftData relationships are unordered, and "the latest schedule version
// is the last one" depends on order). Instants stay the contract's ISO strings with their offset, dates and times
// their `yyyy-MM-dd` / `HH:mm` strings: the store never reinterprets them.

/// Version 1 of the on-disk schema. A later change adds `HealthSchemaV2` and a migration stage.
enum HealthSchemaV1: VersionedSchema {
    static let versionIdentifier = Schema.Version(1, 0, 0)

    static var models: [any PersistentModel.Type] {
        [MedicationRecord.self, ScheduleRecord.self, ArchivedPeriodRecord.self, DoseLogRecord.self, SnoozeRecord.self]
    }

    @Model
    final class MedicationRecord {
        @Attribute(.unique) var id: String
        var position: Int
        var name: String
        var kind: String
        var dose: String
        var notes: String
        var asNeeded: Bool
        var startDate: String
        var endDate: String?
        @Relationship(deleteRule: .cascade, inverse: \ScheduleRecord.medication) var schedules: [ScheduleRecord] = []
        @Relationship(deleteRule: .cascade, inverse: \ArchivedPeriodRecord.medication) var archived: [ArchivedPeriodRecord] = []

        init(id: String, position: Int, name: String, kind: String, dose: String, notes: String, asNeeded: Bool,
             startDate: String, endDate: String?) {
            self.id = id
            self.position = position
            self.name = name
            self.kind = kind
            self.dose = dose
            self.notes = notes
            self.asNeeded = asNeeded
            self.startDate = startDate
            self.endDate = endDate
        }
    }

    @Model
    final class ScheduleRecord {
        var position: Int
        var from: String
        var times: [String]
        var weekdays: [Int]
        var createdAt: String?
        var medication: MedicationRecord?

        init(position: Int, from: String, times: [String], weekdays: [Int], createdAt: String?) {
            self.position = position
            self.from = from
            self.times = times
            self.weekdays = weekdays
            self.createdAt = createdAt
        }
    }

    @Model
    final class ArchivedPeriodRecord {
        var position: Int
        var from: String
        var to: String?
        var medication: MedicationRecord?

        init(position: Int, from: String, to: String?) {
            self.position = position
            self.from = from
            self.to = to
        }
    }

    @Model
    final class DoseLogRecord {
        @Attribute(.unique) var id: String
        var position: Int
        var medicationId: String
        var date: String
        var time: String?
        var status: String
        var takenAt: String?
        var name: String
        var dose: String

        init(id: String, position: Int, medicationId: String, date: String, time: String?, status: String,
             takenAt: String?, name: String, dose: String) {
            self.id = id
            self.position = position
            self.medicationId = medicationId
            self.date = date
            self.time = time
            self.status = status
            self.takenAt = takenAt
            self.name = name
            self.dose = dose
        }
    }

    @Model
    final class SnoozeRecord {
        @Attribute(.unique) var slot: String
        var position: Int
        var until: String

        init(slot: String, position: Int, until: String) {
            self.slot = slot
            self.position = position
            self.until = until
        }
    }
}

enum HealthMigrationPlan: SchemaMigrationPlan {
    static var schemas: [any VersionedSchema.Type] { [HealthSchemaV1.self] }
    static var stages: [MigrationStage] { [] }
}

typealias MedicationRecord = HealthSchemaV1.MedicationRecord
typealias ScheduleRecord = HealthSchemaV1.ScheduleRecord
typealias ArchivedPeriodRecord = HealthSchemaV1.ArchivedPeriodRecord
typealias DoseLogRecord = HealthSchemaV1.DoseLogRecord
typealias SnoozeRecord = HealthSchemaV1.SnoozeRecord

/// A stored record that doesn't map back to Core's model (an unknown kind or status).
struct CorruptHealthRecord: Error {
    let description: String
}

/// `HealthRepository` over SwiftData. The container is created lazily inside the first `load()`/`save(_:)`, off the
/// main actor; if that fails the call throws (Core shows the load error, and `Retry` tries again).
actor SwiftDataHealthRepository: HealthRepository {
    private var database: HealthDatabase?

    func load() async throws -> HealthData {
        try await open().load()
    }

    func save(_ data: HealthData) async throws {
        try await open().save(data)
    }

    private func open() throws -> HealthDatabase {
        if let database { return database }
        let database = HealthDatabase(modelContainer: try Self.makeContainer())
        self.database = database
        return database
    }

    /// `Application Support/HealthData/health.store`, in its own directory, excluded from backup. Default file
    /// protection on purpose: `.complete` would lock the store while the device is locked, and answering a
    /// notification with Taken from the lock screen must still save.
    private static func makeContainer() throws -> ModelContainer {
        let support = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                  appropriateFor: nil, create: true)
        var directory = support.appending(path: "HealthData", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try directory.setResourceValues(values)
        let schema = Schema(versionedSchema: HealthSchemaV1.self)
        let configuration = ModelConfiguration(schema: schema, url: directory.appending(path: "health.store"),
                                               cloudKitDatabase: .none)
        return try ModelContainer(for: schema, migrationPlan: HealthMigrationPlan.self, configurations: [configuration])
    }
}

/// The records' own actor: every SwiftData object stays on it; only `HealthData` (Sendable) crosses.
@ModelActor
actor HealthDatabase {
    func load() throws -> HealthData {
        let medications = try modelContext.fetch(FetchDescriptor<MedicationRecord>(sortBy: [SortDescriptor(\.position)]))
        let logs = try modelContext.fetch(FetchDescriptor<DoseLogRecord>(sortBy: [SortDescriptor(\.position)]))
        let snoozes = try modelContext.fetch(FetchDescriptor<SnoozeRecord>(sortBy: [SortDescriptor(\.position)]))
        return HealthData(
            version: 1,
            medications: try medications.map(Self.medication),
            logs: try logs.map(Self.log),
            snoozes: snoozes.map { DoseSnooze(slot: $0.slot, until: $0.until) }
        )
    }

    /// Upserts and deletes by id so the store equals `data`, in one `save()`. A failed save is rolled back, so the
    /// next attempt (Core saves the whole snapshot again) starts from what is on disk.
    func save(_ data: HealthData) throws {
        modelContext.autosaveEnabled = false
        do {
            try saveMedications(data.medications)
            try saveLogs(data.logs)
            try saveSnoozes(data.snoozes)
            try modelContext.save()
        } catch {
            modelContext.rollback()
            throw error
        }
    }

    private func saveMedications(_ medications: [Medication]) throws {
        var existing = Dictionary(try modelContext.fetch(FetchDescriptor<MedicationRecord>()).map { ($0.id, $0) },
                                  uniquingKeysWith: { first, _ in first })
        for (position, medication) in medications.enumerated() {
            let record: MedicationRecord
            if let found = existing.removeValue(forKey: medication.id) {
                record = found
                record.position = position
                record.name = medication.name
                record.kind = medication.kind.rawValue
                record.dose = medication.dose
                record.notes = medication.notes
                record.asNeeded = medication.asNeeded
                record.startDate = medication.startDate
                record.endDate = medication.endDate
            } else {
                record = MedicationRecord(id: medication.id, position: position, name: medication.name,
                                          kind: medication.kind.rawValue, dose: medication.dose, notes: medication.notes,
                                          asNeeded: medication.asNeeded, startDate: medication.startDate,
                                          endDate: medication.endDate)
                modelContext.insert(record)
            }
            syncSchedules(of: record, to: medication.schedules)
            syncArchived(of: record, to: medication.archived)
        }
        for record in existing.values { modelContext.delete(record) }
    }

    /// Schedule versions and archive periods have no id: they are matched by position.
    private func syncSchedules(of medication: MedicationRecord, to schedules: [MedicationSchedule]) {
        var records = medication.schedules.sorted { $0.position < $1.position }
        while records.count > schedules.count { modelContext.delete(records.removeLast()) }
        for (position, schedule) in schedules.enumerated() {
            if position < records.count {
                let record = records[position]
                record.from = schedule.from
                record.times = schedule.times
                record.weekdays = schedule.weekdays
                record.createdAt = schedule.createdAt
            } else {
                let record = ScheduleRecord(position: position, from: schedule.from, times: schedule.times,
                                            weekdays: schedule.weekdays, createdAt: schedule.createdAt)
                modelContext.insert(record)
                record.medication = medication
            }
        }
    }

    private func syncArchived(of medication: MedicationRecord, to periods: [ArchivedPeriod]) {
        var records = medication.archived.sorted { $0.position < $1.position }
        while records.count > periods.count { modelContext.delete(records.removeLast()) }
        for (position, period) in periods.enumerated() {
            if position < records.count {
                records[position].from = period.from
                records[position].to = period.to
            } else {
                let record = ArchivedPeriodRecord(position: position, from: period.from, to: period.to)
                modelContext.insert(record)
                record.medication = medication
            }
        }
    }

    private func saveLogs(_ logs: [DoseLog]) throws {
        var existing = Dictionary(try modelContext.fetch(FetchDescriptor<DoseLogRecord>()).map { ($0.id, $0) },
                                  uniquingKeysWith: { first, _ in first })
        for (position, log) in logs.enumerated() {
            if let record = existing.removeValue(forKey: log.id) {
                record.position = position
                record.medicationId = log.medicationId
                record.date = log.date
                record.time = log.time
                record.status = log.status.rawValue
                record.takenAt = log.takenAt
                record.name = log.name
                record.dose = log.dose
            } else {
                modelContext.insert(DoseLogRecord(id: log.id, position: position, medicationId: log.medicationId,
                                                  date: log.date, time: log.time, status: log.status.rawValue,
                                                  takenAt: log.takenAt, name: log.name, dose: log.dose))
            }
        }
        for record in existing.values { modelContext.delete(record) }
    }

    private func saveSnoozes(_ snoozes: [DoseSnooze]) throws {
        var existing = Dictionary(try modelContext.fetch(FetchDescriptor<SnoozeRecord>()).map { ($0.slot, $0) },
                                  uniquingKeysWith: { first, _ in first })
        for (position, snooze) in snoozes.enumerated() {
            if let record = existing.removeValue(forKey: snooze.slot) {
                record.position = position
                record.until = snooze.until
            } else {
                modelContext.insert(SnoozeRecord(slot: snooze.slot, position: position, until: snooze.until))
            }
        }
        for record in existing.values { modelContext.delete(record) }
    }

    private static func medication(_ record: MedicationRecord) throws -> Medication {
        guard let kind = MedicationKind(rawValue: record.kind) else {
            throw CorruptHealthRecord(description: "medication \(record.id) has kind '\(record.kind)'")
        }
        return Medication(
            id: record.id, name: record.name, kind: kind, dose: record.dose, notes: record.notes,
            asNeeded: record.asNeeded, startDate: record.startDate, endDate: record.endDate,
            schedules: record.schedules.sorted { $0.position < $1.position }.map {
                MedicationSchedule(from: $0.from, times: $0.times, weekdays: $0.weekdays, createdAt: $0.createdAt)
            },
            archived: record.archived.sorted { $0.position < $1.position }.map { ArchivedPeriod(from: $0.from, to: $0.to) }
        )
    }

    private static func log(_ record: DoseLogRecord) throws -> DoseLog {
        guard let status = LogStatus(rawValue: record.status) else {
            throw CorruptHealthRecord(description: "log \(record.id) has status '\(record.status)'")
        }
        return DoseLog(id: record.id, medicationId: record.medicationId, date: record.date, time: record.time,
                       status: status, takenAt: record.takenAt, name: record.name, dose: record.dose)
    }
}
