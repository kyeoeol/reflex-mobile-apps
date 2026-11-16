import Foundation

// Pure domain rules over `HealthData` (docs/health/CONTRACT.md §1): occurrences, statuses, dose objects, and the
// mutations behind the dose actions, the editor, archive/restore/delete and the notification responses.
// No state lives here; `HealthStore` applies these to the one in-memory value.

/// What "now" means for the rules: the clock's instant, its current time zone, and today's local date.
struct HealthNow: Sendable {
    let now: Date
    let zone: TimeZone
    var today: LocalDate { HealthTime.localDate(of: now, in: zone) }
}

enum DoseStatus: String, Sendable {
    case upcoming, due, missed, taken, skipped
}

/// A dose of medication M on day D at time t: scheduled, or kept alive by a log for that (D, t).
struct Occurrence: Sendable {
    let medicationId: String
    let medication: Medication?
    let date: LocalDate
    let time: String
    /// T: the date + time resolved in the current time zone.
    let scheduledAt: Date
    let log: DoseLog?

    var id: String { "\(medicationId)@\(slot)" }
    var slot: String { "\(date.text)T\(time)" }

    func status(now: Date) -> DoseStatus {
        switch log?.status {
        case .taken?: return .taken
        case .skipped?: return .skipped
        case nil:
            if now < scheduledAt { return .upcoming }
            return now < scheduledAt.addingTimeInterval(HealthRules.missedAfter) ? .due : .missed
        }
    }

    /// The dose object screens show (`{id, medicationId, name, dose, date, time, status, needsAction, takenAt, …}`).
    func item(now: Date) -> DoseItem {
        let status = status(now: now)
        let canTakeNow = abs(now.timeIntervalSince(scheduledAt)) <= HealthRules.takenWindow
        let taken = log?.takenAt.flatMap(HealthTime.parseInstant)
        let timing: String? = status == .taken ? taken.map { taken in
            if taken.instant < scheduledAt.addingTimeInterval(-HealthRules.timingTolerance) { return "early" }
            if taken.instant > scheduledAt.addingTimeInterval(HealthRules.timingTolerance) { return "late" }
            return "onTime"
        } : nil
        return DoseItem(
            id: id, medicationId: medicationId,
            name: log?.name ?? medication?.name ?? "", dose: log?.dose ?? medication?.dose ?? "",
            date: date.text, time: time, status: status.rawValue, needsAction: status == .due || status == .missed,
            takenAt: log?.takenAt.flatMap(HealthTime.storedTime), timing: timing,
            canTake: status != .taken && canTakeNow, canTakeAt: true, canSkip: status != .skipped, canUndo: log != nil,
            sortTime: time)
    }
}

/// A dose as Today, History and MedicationDetail show it; also used for as-needed log entries (`log:<id>`).
public struct DoseItem: Sendable, Equatable {
    public let id: String
    public let medicationId: String
    public let name: String
    public let dose: String
    public let date: String
    public let time: String?
    public let status: String
    /// Status is `due` or `missed`.
    public let needsAction: Bool
    public let takenAt: String?
    public let timing: String?
    /// Not taken yet and now is within 12 hours of the dose: "take now" is offered.
    public let canTake: Bool
    /// "Taken at another time" is offered (always, for scheduled doses).
    public let canTakeAt: Bool
    public let canSkip: Bool
    public let canUndo: Bool
    /// `time`, or `takenAt` for as-needed entries: what lists sort on.
    let sortTime: String

    /// An as-needed log entry: `log:<logId>`, `time: null`, taken, undoable only.
    init(asNeeded log: DoseLog) {
        let takenAt = log.takenAt.flatMap(HealthTime.storedTime)
        self.init(id: "log:\(log.id)", medicationId: log.medicationId, name: log.name, dose: log.dose, date: log.date,
                  time: nil, status: DoseStatus.taken.rawValue, needsAction: false, takenAt: takenAt, timing: nil,
                  canTake: false, canTakeAt: false, canSkip: false, canUndo: true, sortTime: takenAt ?? "")
    }

    init(id: String, medicationId: String, name: String, dose: String, date: String, time: String?, status: String,
         needsAction: Bool, takenAt: String?, timing: String?, canTake: Bool, canTakeAt: Bool, canSkip: Bool,
         canUndo: Bool, sortTime: String) {
        self.id = id
        self.medicationId = medicationId
        self.name = name
        self.dose = dose
        self.date = date
        self.time = time
        self.status = status
        self.needsAction = needsAction
        self.takenAt = takenAt
        self.timing = timing
        self.canTake = canTake
        self.canTakeAt = canTakeAt
        self.canSkip = canSkip
        self.canUndo = canUndo
        self.sortTime = sortTime
    }
}

/// The result of a dose action: `applied` clears the screen's `doseError`, `rejected` sets it, `ignored` is a no-op.
enum DoseOutcome: Equatable, Sendable {
    case applied
    case ignored
    case rejected(String)
}

/// What the user answered on a notification: a tap (`open`) or one of its buttons. Reaches Core only through
/// `AppRoot.handleReminderResponse(slot:action:)`, never through a URL.
public enum ReminderResponse: String, Sendable {
    case open, taken, skipped, snooze
}

/// The editor's output.
struct MedicationDraft: Sendable {
    let id: String?
    let name: String
    let kind: MedicationKind
    let dose: String
    let notes: String
    let asNeeded: Bool
    let times: [String]
    let weekdays: [Int]
    let startDate: String
    let endDate: String?
}

enum HealthRules {
    static let missedAfter: TimeInterval = 120 * 60
    static let timingTolerance: TimeInterval = 60 * 60
    static let takenWindow: TimeInterval = 12 * 60 * 60
    static let snoozeLength: TimeInterval = 10 * 60
    static let followUpAfter: TimeInterval = 30 * 60

    static func newId() -> String { UUID().uuidString.lowercased() }

    /// `"<date>T<time>"` → (date, time), strict.
    static func parseSlot(_ slot: String) -> (date: LocalDate, time: String)? {
        guard slot.utf8.count == 16, let parsed = HealthTime.parseLocalDateTime(slot, withSeconds: false) else { return nil }
        return (parsed.date, String(slot.suffix(5)))
    }

    /// `"<medicationId>@<date>T<time>"` → parts; nil for `log:` ids and malformed ones.
    static func parseDoseId(_ doseId: String) -> (medicationId: String, date: LocalDate, time: String)? {
        guard !doseId.hasPrefix("log:"), let at = doseId.firstIndex(of: "@"),
              let slot = parseSlot(String(doseId[doseId.index(after: at)...])) else { return nil }
        return (String(doseId[..<at]), slot.date, slot.time)
    }

    /// The name key: lowercased (Unicode default case mapping), as a sequence of code points. Names are ordered by
    /// comparing keys code point by code point (never `String <` or a localized comparison) and are duplicates when
    /// their trimmed keys are equal, exactly like Android.
    static func nameKey(_ name: String) -> [UInt32] { name.lowercased().unicodeScalars.map(\.value) }

    /// Trimmed of Unicode whitespace (the duplicate check, and what the editor saves).
    static func trimmed(_ name: String) -> String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// Code point order of two strings (ids, the name tie-breaker).
    static func precedes(_ lhs: String, _ rhs: String) -> Bool {
        lhs.unicodeScalars.map(\.value).lexicographicallyPrecedes(rhs.unicodeScalars.map(\.value))
    }

    /// By name key, then by id.
    static func byName(_ lhs: String, _ lhsId: String, _ rhs: String, _ rhsId: String) -> Bool {
        let (l, r) = (nameKey(lhs), nameKey(rhs))
        return l != r ? l.lexicographicallyPrecedes(r) : precedes(lhsId, rhsId)
    }

    /// `"<taken> of <scheduled> taken"`, or nil when nothing is scheduled.
    static func progressLabel(_ occurrences: [Occurrence]) -> String? {
        occurrences.isEmpty ? nil : "\(occurrences.filter { $0.log?.status == .taken }.count) of \(occurrences.count) taken"
    }
}

/// What `TurnOnReminders` does (Today and Reminders): notDetermined → ask; denied, or authorized but possibly late →
/// open the system settings; otherwise nothing.
enum ReminderSetupStep: Sendable {
    case requestPermission, openSettings, none

    static func turnOn(permission: NotificationPermission, mayBeLate: Bool) -> ReminderSetupStep {
        switch (permission, mayBeLate) {
        case (.notDetermined, _): .requestPermission
        case (.denied, _), (.authorized, true): .openSettings
        case (.authorized, false): .none
        }
    }
}

// MARK: - Reading

extension HealthData {
    func medication(_ id: String) -> Medication? { medications.first { $0.id == id } }

    /// Scheduled occurrences on `date` plus every scheduled log on that day (editing never rewrites history).
    func occurrences(on date: LocalDate, zone: TimeZone) -> [Occurrence] {
        let day = date.text
        let dayLogs = logs.filter { $0.date == day && $0.time != nil }
        var result: [Occurrence] = []
        var seen = Set<String>()
        func add(_ medicationId: String, _ medication: Medication?, _ time: String) {
            guard let minutes = HealthTime.minutes(ofTime: time), seen.insert("\(medicationId)@\(time)").inserted else { return }
            result.append(Occurrence(
                medicationId: medicationId, medication: medication, date: date, time: time,
                scheduledAt: HealthTime.resolve(date, secondsOfDay: minutes * 60, in: zone),
                log: dayLogs.first { $0.medicationId == medicationId && $0.time == time }))
        }
        for medication in medications where medication.isScheduled(on: date) {
            // A version created during its own first day doesn't produce doses that were already missed then.
            let version = medication.schedule(on: date)
            let createdAt = version?.createdAt.flatMap(HealthTime.parseInstant)?.instant
            let createdToday = version?.from == day && createdAt.map { HealthTime.localDate(of: $0, in: zone) == date } == true
            for time in medication.times(on: date) {
                if createdToday, let createdAt, let minutes = HealthTime.minutes(ofTime: time),
                   HealthTime.resolve(date, secondsOfDay: minutes * 60, in: zone).addingTimeInterval(HealthRules.missedAfter) <= createdAt {
                    continue
                }
                add(medication.id, medication, time)
            }
        }
        for log in dayLogs { add(log.medicationId, medication(log.medicationId), log.time!) }
        return result
    }

    func occurrence(doseId: String, zone: TimeZone) -> Occurrence? {
        guard let parts = HealthRules.parseDoseId(doseId) else { return nil }
        return occurrences(on: parts.date, zone: zone).first { $0.id == doseId }
    }

    /// The unlogged doses of a reminder slot (non-archived scheduled medications), by name then id.
    func unloggedDoses(slot date: LocalDate, time: String, zone: TimeZone) -> [Occurrence] {
        occurrences(on: date, zone: zone)
            .filter { $0.time == time && $0.log == nil && $0.medication != nil }
            .sorted { HealthRules.byName($0.medication!.name, $0.medicationId, $1.medication!.name, $1.medicationId) }
    }

    /// As-needed log entries dated `date`.
    func asNeededLogs(on date: LocalDate) -> [DoseLog] {
        logs.filter { $0.time == nil && $0.date == date.text }
    }

    /// Some active medication has dose times (the condition for the reminder banner).
    func hasActiveScheduledMedication(today: LocalDate) -> Bool {
        medications.contains { $0.status(today: today) == .active && !$0.latestTimes.isEmpty }
    }

    /// Medication `id` has a log dated `date` (an edit then changes the schedule from the next day on).
    func hasLog(medicationId: String, on date: LocalDate) -> Bool {
        logs.contains { $0.medicationId == medicationId && $0.date == date.text }
    }
}

// MARK: - Writing

extension HealthData {
    mutating func take(doseId: String, at clock: HealthNow) -> DoseOutcome {
        guard let occurrence = occurrence(doseId: doseId, zone: clock.zone), occurrence.log?.status != .taken else { return .ignored }
        return logTaken(occurrence, at: clock.now, clock: clock)
    }

    mutating func take(doseId: String, takenAt text: String, clock: HealthNow) -> DoseOutcome {
        guard let occurrence = occurrence(doseId: doseId, zone: clock.zone) else { return .ignored }
        guard let instant = HealthTime.resolve(localDateTime: text, in: clock.zone) else {
            return .rejected("Enter a time as yyyy-MM-ddTHH:mm")
        }
        return logTaken(occurrence, at: instant, clock: clock)
    }

    private mutating func logTaken(_ occurrence: Occurrence, at instant: Date, clock: HealthNow) -> DoseOutcome {
        guard instant <= clock.now else { return .rejected("Time can't be in the future") }
        guard abs(instant.timeIntervalSince(occurrence.scheduledAt)) <= HealthRules.takenWindow else {
            return .rejected("Time must be within 12 hours of the scheduled dose")
        }
        upsertLog(occurrence, status: .taken, takenAt: HealthTime.format(instant, in: clock.zone))
        return .applied
    }

    mutating func skip(doseId: String, clock: HealthNow) -> DoseOutcome {
        guard let occurrence = occurrence(doseId: doseId, zone: clock.zone), occurrence.log?.status != .skipped else { return .ignored }
        upsertLog(occurrence, status: .skipped, takenAt: nil)
        return .applied
    }

    mutating func undo(doseId: String) -> DoseOutcome {
        let index: Int?
        if doseId.hasPrefix("log:") {
            let logId = String(doseId.dropFirst(4))
            index = logs.firstIndex { $0.id == logId && $0.time == nil }
        } else if let parts = HealthRules.parseDoseId(doseId) {
            index = logs.firstIndex { $0.medicationId == parts.medicationId && $0.date == parts.date.text && $0.time == parts.time }
        } else {
            index = nil
        }
        guard let index else { return .ignored }
        logs.remove(at: index)
        return .applied
    }

    /// Existing log: only its status and time change (the name/dose snapshot stays). New log: snapshot now.
    private mutating func upsertLog(_ occurrence: Occurrence, status: LogStatus, takenAt: String?) {
        if let existing = occurrence.log, let index = logs.firstIndex(where: { $0.id == existing.id }) {
            logs[index].status = status
            logs[index].takenAt = takenAt
        } else {
            logs.append(DoseLog(id: HealthRules.newId(), medicationId: occurrence.medicationId, date: occurrence.date.text,
                                time: occurrence.time, status: status, takenAt: takenAt,
                                name: occurrence.medication?.name ?? "", dose: occurrence.medication?.dose ?? ""))
        }
    }

    mutating func logAsNeeded(medicationId: String, clock: HealthNow) -> Bool {
        guard let medication = medication(medicationId), medication.asNeeded else { return false }
        logs.append(DoseLog(id: HealthRules.newId(), medicationId: medicationId, date: clock.today.text, time: nil,
                            status: .taken, takenAt: HealthTime.format(clock.now, in: clock.zone),
                            name: medication.name, dose: medication.dose))
        return true
    }

    /// Removes today's latest as-needed log of the medication (latest `takenAt`; on a tie the one logged last).
    mutating func undoAsNeeded(medicationId: String, today: LocalDate) -> Bool {
        let latest = logs.indices
            .filter { logs[$0].medicationId == medicationId && logs[$0].time == nil && logs[$0].date == today.text }
            .max { a, b in
                let (ta, tb) = (logs[a].takenAt.flatMap(HealthTime.parseInstant)?.instant ?? .distantPast,
                                logs[b].takenAt.flatMap(HealthTime.parseInstant)?.instant ?? .distantPast)
                return ta != tb ? ta < tb : a < b
            }
        guard let latest else { return false }
        logs.remove(at: latest)
        return true
    }

    /// Adds or updates a medication; returns its id, or nil for an edit of a medication that no longer exists.
    /// - A new medication gets one version `{from: startDate, createdAt: now}` (none when as-needed).
    /// - An edit whose times/weekdays differ from the latest version adds a version with `createdAt: now`, from today,
    ///   or from tomorrow when the medication already has a log dated today (so a dose logged today isn't followed by
    ///   a second one); a version that already starts on that day is replaced. Whether it is as-needed never changes.
    mutating func save(_ draft: MedicationDraft, clock: HealthNow) -> String? {
        let (times, weekdays) = (Array(Set(draft.times)).sorted(), Array(Set(draft.weekdays)).sorted())
        let createdAt = HealthTime.format(clock.now, in: clock.zone)
        guard let id = draft.id else {
            let medication = Medication(
                id: HealthRules.newId(), name: draft.name, kind: draft.kind, dose: draft.dose, notes: draft.notes,
                asNeeded: draft.asNeeded, startDate: draft.startDate, endDate: draft.endDate,
                schedules: draft.asNeeded ? []
                    : [MedicationSchedule(from: draft.startDate, times: times, weekdays: weekdays, createdAt: createdAt)],
                archived: [])
            medications.append(medication)
            return medication.id
        }
        guard let index = medications.firstIndex(where: { $0.id == id }) else { return nil }
        var medication = medications[index]
        medication.name = draft.name
        medication.kind = draft.kind
        medication.dose = draft.dose
        medication.notes = draft.notes
        medication.startDate = draft.startDate
        medication.endDate = draft.endDate
        if !medication.asNeeded, times != medication.latestTimes || weekdays != medication.latestWeekdays {
            let today = clock.today
            let from = (hasLog(medicationId: id, on: today) ? today.adding(days: 1) : today).text
            medication.schedules.removeAll { $0.from == from }
            medication.schedules.append(MedicationSchedule(from: from, times: times, weekdays: weekdays, createdAt: createdAt))
        }
        medications[index] = medication
        return id
    }

    mutating func archive(medicationId: String, today: LocalDate) -> Bool {
        guard let index = medications.firstIndex(where: { $0.id == medicationId }), !medications[index].isArchivedNow else { return false }
        medications[index].archived.append(ArchivedPeriod(from: today.text, to: nil))
        return true
    }

    /// Closes the open archive period with `to` = yesterday, dropping it when that is before its `from`, and appends
    /// a version from today with the latest times/weekdays and `createdAt: now` (doses already missed today stay
    /// away). As-needed medications have no schedule to append to.
    mutating func restore(medicationId: String, clock: HealthNow) -> Bool {
        guard let index = medications.firstIndex(where: { $0.id == medicationId }),
              let open = medications[index].archived.firstIndex(where: { $0.to == nil }) else { return false }
        let today = clock.today
        let yesterday = today.adding(days: -1).text
        if yesterday < medications[index].archived[open].from {
            medications[index].archived.remove(at: open)
        } else {
            medications[index].archived[open].to = yesterday
        }
        let medication = medications[index]
        if !medication.asNeeded, medication.latestSchedule != nil {
            medications[index].schedules.append(MedicationSchedule(
                from: today.text, times: medication.latestTimes, weekdays: medication.latestWeekdays,
                createdAt: HealthTime.format(clock.now, in: clock.zone)))
        }
        return true
    }

    /// Removes the medication and all its logs (snoozes belong to slots and simply stop matching).
    mutating func delete(medicationId: String) -> Bool {
        guard medications.contains(where: { $0.id == medicationId }) else { return false }
        medications.removeAll { $0.id == medicationId }
        logs.removeAll { $0.medicationId == medicationId }
        return true
    }

    /// A notification response for a slot. Only acts on the slot's unlogged doses; a slot without any ignores it.
    mutating func respond(_ response: ReminderResponse, slot: String, clock: HealthNow) -> Bool {
        guard let (date, time) = HealthRules.parseSlot(slot) else { return false }
        let doses = unloggedDoses(slot: date, time: time, zone: clock.zone)
        guard let scheduledAt = doses.first?.scheduledAt else { return false }
        switch response {
        case .open:
            return false
        case .taken:
            let loggable = doses.filter { abs(clock.now.timeIntervalSince($0.scheduledAt)) <= HealthRules.takenWindow }
            for dose in loggable { upsertLog(dose, status: .taken, takenAt: HealthTime.format(clock.now, in: clock.zone)) }
            return !loggable.isEmpty
        case .skipped:
            for dose in doses { upsertLog(dose, status: .skipped, takenAt: nil) }
            return true
        case .snooze:
            let until = clock.now.addingTimeInterval(HealthRules.snoozeLength)
            guard until < scheduledAt.addingTimeInterval(HealthRules.missedAfter) else { return false }
            snoozes.removeAll { $0.slot == slot }
            snoozes.append(DoseSnooze(slot: slot, until: HealthTime.format(until, in: clock.zone)))
            return true
        }
    }
}
