import Foundation

// The Health data (docs/health/CONTRACT.md §1 Data): one value, loaded once through `HealthRepository` and saved
// whole after every change. Field names are the JSON keys of the fixtures' `storage["health.data"]` and the same
// on Android. Decoding ignores unknown keys and defaults missing optional ones, but is strict about values:
// semantically invalid data (a malformed date, time or instant, a weekday outside 1–7, an unknown kind or status,
// a taken log without `takenAt`, a skipped as-needed log, an id containing `@`, a malformed snooze slot) is a load
// error, exactly like unparseable JSON.

/// Thrown by decoding when `health.data` is well-formed JSON but not valid Health data.
public struct InvalidHealthData: Error, CustomStringConvertible, Sendable {
    public let description: String
    init(_ description: String) { self.description = description }
}

/// Decoding checks shared by the model types.
private enum Check {
    static func date(_ text: String, _ field: String) throws {
        guard LocalDate(text) != nil else { throw InvalidHealthData("\(field) '\(text)' is not yyyy-MM-dd") }
    }

    static func time(_ text: String, _ field: String) throws {
        guard HealthTime.isTime(text) else { throw InvalidHealthData("\(field) '\(text)' is not HH:mm") }
    }

    static func instant(_ text: String, _ field: String) throws {
        guard HealthTime.parseInstant(text) != nil else { throw InvalidHealthData("\(field) '\(text)' is not an instant") }
    }

    static func id(_ text: String, _ field: String) throws {
        guard !text.contains("@") else { throw InvalidHealthData("\(field) '\(text)' contains '@'") }
    }
}

public enum MedicationKind: String, Codable, Sendable, CaseIterable {
    case medication, supplement
}

public enum LogStatus: String, Codable, Sendable {
    case taken, skipped
}

/// One schedule version. The version in force on day D is the one with the latest `from ≤ D`; if none, the earliest.
/// Versions are append-only; the latest version is the last one in the list.
public struct MedicationSchedule: Codable, Sendable, Equatable {
    public var from: String
    public var times: [String]
    public var weekdays: [Int]
    /// When this version was saved (an instant), if known. A version created during its own first day doesn't
    /// produce that day's doses that were already missed at creation.
    public var createdAt: String?

    public init(from: String, times: [String], weekdays: [Int], createdAt: String?) {
        self.from = from
        self.times = times
        self.weekdays = weekdays
        self.createdAt = createdAt
    }

    enum CodingKeys: String, CodingKey { case from, times, weekdays, createdAt }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        from = try c.decode(String.self, forKey: .from)
        times = try c.decodeIfPresent([String].self, forKey: .times) ?? []
        weekdays = try c.decodeIfPresent([Int].self, forKey: .weekdays) ?? []
        createdAt = try c.decodeIfPresent(String.self, forKey: .createdAt)
        try Check.date(from, "schedules.from")
        for time in times { try Check.time(time, "schedules.times") }
        for weekday in weekdays where !(1...7).contains(weekday) { throw InvalidHealthData("weekday \(weekday) is outside 1–7") }
        if let createdAt { try Check.instant(createdAt, "schedules.createdAt") }
    }
}

/// `to: nil` = still archived. Day D is archived if `from ≤ D` and (`to` is nil or `D ≤ to`).
public struct ArchivedPeriod: Codable, Sendable, Equatable {
    public var from: String
    public var to: String?

    public init(from: String, to: String?) {
        self.from = from
        self.to = to
    }

    enum CodingKeys: String, CodingKey { case from, to }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        from = try c.decode(String.self, forKey: .from)
        to = try c.decodeIfPresent(String.self, forKey: .to)
        try Check.date(from, "archived.from")
        if let to { try Check.date(to, "archived.to") }
    }
}

public struct Medication: Codable, Sendable, Equatable, Identifiable {
    public var id: String
    public var name: String
    public var kind: MedicationKind
    public var dose: String
    public var notes: String
    public var asNeeded: Bool
    public var startDate: String
    public var endDate: String?
    public var schedules: [MedicationSchedule]
    public var archived: [ArchivedPeriod]

    public init(id: String, name: String, kind: MedicationKind, dose: String, notes: String, asNeeded: Bool,
                startDate: String, endDate: String?, schedules: [MedicationSchedule], archived: [ArchivedPeriod]) {
        self.id = id
        self.name = name
        self.kind = kind
        self.dose = dose
        self.notes = notes
        self.asNeeded = asNeeded
        self.startDate = startDate
        self.endDate = endDate
        self.schedules = schedules
        self.archived = archived
    }

    enum CodingKeys: String, CodingKey { case id, name, kind, dose, notes, asNeeded, startDate, endDate, schedules, archived }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        kind = try c.decodeIfPresent(MedicationKind.self, forKey: .kind) ?? .medication
        dose = try c.decodeIfPresent(String.self, forKey: .dose) ?? ""
        notes = try c.decodeIfPresent(String.self, forKey: .notes) ?? ""
        asNeeded = try c.decodeIfPresent(Bool.self, forKey: .asNeeded) ?? false
        startDate = try c.decode(String.self, forKey: .startDate)
        endDate = try c.decodeIfPresent(String.self, forKey: .endDate)
        schedules = try c.decodeIfPresent([MedicationSchedule].self, forKey: .schedules) ?? []
        archived = try c.decodeIfPresent([ArchivedPeriod].self, forKey: .archived) ?? []
        try Check.id(id, "medications.id")
        try Check.date(startDate, "medications.startDate")
        if let endDate { try Check.date(endDate, "medications.endDate") }
    }
}

/// A logged dose. Scheduled logs have `date` + `time` (the occurrence they belong to); as-needed logs have
/// `time: nil` and `date` = the local date of `takenAt`. `name`/`dose` are snapshots taken when logging.
public struct DoseLog: Codable, Sendable, Equatable, Identifiable {
    public var id: String
    public var medicationId: String
    public var date: String
    public var time: String?
    public var status: LogStatus
    /// Instant with the offset it was logged in (`2026-11-16T08:10:00+09:00`); nil when skipped.
    public var takenAt: String?
    public var name: String
    public var dose: String

    public init(id: String, medicationId: String, date: String, time: String?, status: LogStatus, takenAt: String?,
                name: String, dose: String) {
        self.id = id
        self.medicationId = medicationId
        self.date = date
        self.time = time
        self.status = status
        self.takenAt = takenAt
        self.name = name
        self.dose = dose
    }

    enum CodingKeys: String, CodingKey { case id, medicationId, date, time, status, takenAt, name, dose }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        medicationId = try c.decode(String.self, forKey: .medicationId)
        date = try c.decode(String.self, forKey: .date)
        time = try c.decodeIfPresent(String.self, forKey: .time)
        status = try c.decode(LogStatus.self, forKey: .status)
        takenAt = try c.decodeIfPresent(String.self, forKey: .takenAt)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        dose = try c.decodeIfPresent(String.self, forKey: .dose) ?? ""
        try Check.id(id, "logs.id")
        try Check.id(medicationId, "logs.medicationId")
        try Check.date(date, "logs.date")
        if let time { try Check.time(time, "logs.time") }
        if let takenAt { try Check.instant(takenAt, "logs.takenAt") }
        if status == .taken, takenAt == nil { throw InvalidHealthData("taken log '\(id)' has no takenAt") }
        if status == .skipped, time == nil { throw InvalidHealthData("as-needed log '\(id)' is skipped") }
    }
}

/// A snoozed reminder slot (`"<date>T<time>"`) and the instant it comes back.
public struct DoseSnooze: Codable, Sendable, Equatable {
    public var slot: String
    public var until: String

    public init(slot: String, until: String) {
        self.slot = slot
        self.until = until
    }

    enum CodingKeys: String, CodingKey { case slot, until }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        slot = try c.decode(String.self, forKey: .slot)
        until = try c.decode(String.self, forKey: .until)
        guard HealthRules.parseSlot(slot) != nil else { throw InvalidHealthData("snooze slot '\(slot)' is not yyyy-MM-ddTHH:mm") }
        try Check.instant(until, "snoozes.until")
    }
}

public struct HealthData: Codable, Sendable, Equatable {
    public var version: Int
    public var medications: [Medication]
    public var logs: [DoseLog]
    public var snoozes: [DoseSnooze]

    public init(version: Int = 1, medications: [Medication] = [], logs: [DoseLog] = [], snoozes: [DoseSnooze] = []) {
        self.version = version
        self.medications = medications
        self.logs = logs
        self.snoozes = snoozes
    }

    enum CodingKeys: String, CodingKey { case version, medications, logs, snoozes }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = try c.decodeIfPresent(Int.self, forKey: .version) ?? 1
        medications = try c.decodeIfPresent([Medication].self, forKey: .medications) ?? []
        logs = try c.decodeIfPresent([DoseLog].self, forKey: .logs) ?? []
        snoozes = try c.decodeIfPresent([DoseSnooze].self, forKey: .snoozes) ?? []
    }

    /// Decodes the `health.data` JSON; throws when it isn't valid JSON or valid Health data. Empty (or
    /// whitespace-only) text is empty data, like nothing stored.
    public init(json: String) throws {
        guard !json.allSatisfy(\.isWhitespace) else {
            self.init()
            return
        }
        self = try JSONDecoder().decode(HealthData.self, from: Data(json.utf8))
    }

    /// Encodes as `health.data` JSON (stable key order).
    public func json() throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return String(decoding: try encoder.encode(self), as: UTF8.self)
    }
}

// MARK: - Medication rules

public enum MedicationStatus: String, Sendable {
    case active, ended, archived
}

extension Medication {
    /// The schedule version in force on `date`: latest `from ≤ date`, else the earliest one.
    func schedule(on date: LocalDate) -> MedicationSchedule? {
        let day = date.text
        let inForce = schedules.enumerated().filter { $0.element.from <= day }
            .max { ($0.element.from, $0.offset) < ($1.element.from, $1.offset) }?.element
        return inForce ?? schedules.min { $0.from < $1.from }
    }

    /// The last version in the list: what the editor and the schedule summary show.
    var latestSchedule: MedicationSchedule? { schedules.last }

    /// Times of the latest version (sorted, unique); empty for as-needed medications.
    var latestTimes: [String] { asNeeded ? [] : Array(Set(latestSchedule?.times ?? [])).sorted() }

    var latestWeekdays: [Int] { Array(Set(latestSchedule?.weekdays ?? [])).sorted() }

    func isArchived(on date: LocalDate) -> Bool {
        let day = date.text
        return archived.contains { $0.from <= day && ($0.to.map { day <= $0 } ?? true) }
    }

    var isArchivedNow: Bool { archived.contains { $0.to == nil } }

    func status(today: LocalDate) -> MedicationStatus {
        if isArchivedNow { return .archived }
        if let endDate, endDate < today.text { return .ended }
        return .active
    }

    /// Times in force on `date` (sorted, unique); empty for as-needed medications.
    func times(on date: LocalDate) -> [String] {
        asNeeded ? [] : Array(Set(schedule(on: date)?.times ?? [])).sorted()
    }

    func weekdays(on date: LocalDate) -> [Int] {
        Array(Set(schedule(on: date)?.weekdays ?? [])).sorted()
    }

    /// From the latest version: `"As needed"`, `"Every day at 08:00, 20:00"`, `"Mon, Wed, Fri at 13:00"`.
    var scheduleSummary: String {
        if asNeeded { return "As needed" }
        let times = latestTimes.joined(separator: ", ")
        let weekdays = latestWeekdays
        if weekdays == [1, 2, 3, 4, 5, 6, 7] { return "Every day at \(times)" }
        let names = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
        return "\(weekdays.map { names[$0 - 1] }.joined(separator: ", ")) at \(times)"
    }

    /// Whether the schedule produces an occurrence on `date` (times aside).
    func isScheduled(on date: LocalDate) -> Bool {
        let day = date.text
        return !asNeeded && startDate <= day && (endDate.map { day <= $0 } ?? true) && !isArchived(on: date)
            && (schedule(on: date)?.weekdays.contains(date.isoWeekday) ?? false)
    }
}
