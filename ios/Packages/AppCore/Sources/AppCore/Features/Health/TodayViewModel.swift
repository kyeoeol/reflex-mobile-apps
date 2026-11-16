import Foundation
import Observation

/// Today (root of the `today` tab): today's doses grouped by time, as-needed medications, the reminder banner.
@MainActor
public final class TodayViewModel: ViewModel {
    public struct DoseGroup: Sendable, Equatable {
        public let key: String
        public let date: String
        public let time: String
        /// `time` for today, `"Yesterday, <time>"` for the day before, else `"<date>, <time>"`.
        public let label: String
        public let doses: [DoseItem]
    }

    public struct AsNeededItem: Sendable, Equatable {
        public let medicationId: String
        public let name: String
        public let dose: String
        public let takenToday: Int
        public let lastTakenAt: String?
        public let summary: String
        public let canUndo: Bool
    }

    /// Why Today shows its message (the view picks an illustration by it).
    public enum MessageKind: String, Sendable {
        case loadError, saveError, noMedications, nothingToday, allDone
    }

    @MainActor @Observable public final class State {
        public fileprivate(set) var isLoading = true
        public fileprivate(set) var date = ""
        public fileprivate(set) var progressLabel: String?
        public fileprivate(set) var takenCount = 0
        public fileprivate(set) var scheduledCount = 0
        public fileprivate(set) var message: String?
        /// `loadError`, `saveError`, `noMedications`, `nothingToday`, `allDone` (see `MessageKind`), or nil.
        public fileprivate(set) var messageKind: String?
        public fileprivate(set) var reminderBanner: String?
        /// The banner button's title.
        public fileprivate(set) var reminderBannerAction: String?
        public fileprivate(set) var groups: [DoseGroup] = []
        public fileprivate(set) var asNeeded: [AsNeededItem] = []
        public fileprivate(set) var doseError: String?
    }

    public enum Action {
        case take(doseId: String)
        case takeAt(doseId: String, takenAt: String)
        case skip(doseId: String)
        case undo(doseId: String)
        case logAsNeeded(medicationId: String)
        case undoAsNeeded(medicationId: String)
        case turnOnReminders
        case openReminders
        case addMedication
        case openMedication(id: String)
        case retry
    }

    public enum Message {
        case viewAction(Action)
        case storeChanged(HealthSnapshot)
    }

    public let state = State()
    private let store: HealthStore
    private weak var navigation: (any HealthNavigating)?
    private var snapshot: HealthSnapshot

    init(store: HealthStore, navigation: any HealthNavigating) {
        self.store = store
        self.navigation = navigation
        self.snapshot = store.snapshot
        store.subscribe(self) { [weak self] in self?.send(message: .storeChanged($0)) }
        send(message: .storeChanged(store.snapshot))
    }

    public func send(action: Action) { send(message: .viewAction(action)) }

    public func update(state: State, message: Message) {
        func apply(_ outcome: DoseOutcome) {
            switch outcome {
            case .applied: state.doseError = nil
            case .rejected(let error): state.doseError = error
            case .ignored: break
            }
        }

        switch message {
        case .viewAction(.take(let doseId)):
            apply(store.take(doseId: doseId))
        case .viewAction(.takeAt(let doseId, let takenAt)):
            apply(store.take(doseId: doseId, takenAt: takenAt))
        case .viewAction(.skip(let doseId)):
            apply(store.skip(doseId: doseId))
        case .viewAction(.undo(let doseId)):
            apply(store.undo(doseId: doseId))
        case .viewAction(.logAsNeeded(let medicationId)):
            store.logAsNeeded(medicationId: medicationId)
        case .viewAction(.undoAsNeeded(let medicationId)):
            store.undoAsNeeded(medicationId: medicationId)
        case .viewAction(.turnOnReminders):
            switch ReminderSetupStep.turnOn(permission: snapshot.permission, mayBeLate: snapshot.mayBeLate) {
            case .requestPermission: store.requestPermission()
            case .openSettings: store.openSettings()
            case .none: break
            }
        case .viewAction(.openReminders):
            navigation?.showReminders()
        case .viewAction(.addMedication):
            navigation?.showMedicationEditor(medicationId: nil)
        case .viewAction(.openMedication(let id)) where snapshot.data.medication(id) != nil:
            navigation?.showMedicationDetail(id: id)
        case .viewAction(.openMedication):
            break   // unknown medication: no-op
        case .viewAction(.retry):
            store.retry()
        case .storeChanged(let snapshot):
            self.snapshot = snapshot
            let (data, now, zone, today) = (snapshot.data, snapshot.now, snapshot.zone, snapshot.today)
            let todays = data.occurrences(on: today, zone: zone)
            // Earlier days' doses stay until they turn missed (a 23:30 dose is still there after midnight).
            let earlier = (1...2).reversed().flatMap { back in
                data.occurrences(on: today.adding(days: -back), zone: zone)
                    .filter { $0.scheduledAt.addingTimeInterval(HealthRules.missedAfter) > now }
            }
            let shown = earlier + todays
            let slots = Dictionary(grouping: shown, by: \.slot)
            let taken = todays.filter { $0.log?.status == .taken }.count
            let hasActive = data.medications.contains { $0.status(today: today) == .active }

            state.isLoading = snapshot.phase == .loading
            state.date = today.text
            state.progressLabel = HealthRules.progressLabel(todays)
            state.takenCount = taken
            state.scheduledCount = todays.count
            let groups = slots.keys.sorted().map { slot in
                let occurrences = slots[slot]!
                let (date, time) = (occurrences[0].date, occurrences[0].time)
                let doses = occurrences.map { $0.item(now: now) }
                    .sorted { HealthRules.byName($0.name, $0.medicationId, $1.name, $1.medicationId) }
                let label = date == today ? time
                    : date == today.adding(days: -1) ? "Yesterday, \(time)" : "\(date.text), \(time)"
                return DoseGroup(key: slot, date: date.text, time: time, label: label, doses: doses)
            }
            let kind: MessageKind? = switch snapshot.phase {
            case .loading: nil
            case .failed: .loadError
            case .loaded where snapshot.saveError: .saveError
            case .loaded where !hasActive: .noMedications
            case .loaded where groups.isEmpty: .nothingToday
            case .loaded where shown.allSatisfy({ $0.log != nil }): .allDone
            case .loaded: nil
            }
            state.messageKind = kind?.rawValue
            state.message = switch kind {
            case .loadError?: "Couldn't load your data"
            case .saveError?: "Couldn't save your changes"
            case .noMedications?: "No medications yet"
            case .nothingToday?: "Nothing scheduled today"
            case .allDone?: "All done for today"
            case nil: nil
            }
            let banner: (String, String)? = switch (data.hasActiveScheduledMedication(today: today), snapshot.permission, snapshot.mayBeLate) {
            case (false, _, _): nil
            case (true, .denied, _): ("Reminders are off", "Open Settings")
            case (true, .notDetermined, _): ("Turn on reminders to get notified", "Turn on reminders")
            case (true, .authorized, true): ("Reminders may arrive a few minutes late", "Open Settings")
            case (true, .authorized, false): nil
            }
            state.reminderBanner = banner?.0
            state.reminderBannerAction = banner?.1
            state.groups = groups
            let asNeededLogs = data.asNeededLogs(on: today)
            state.asNeeded = data.medications
                .filter { $0.asNeeded && $0.status(today: today) == .active }
                .sorted { HealthRules.byName($0.name, $0.id, $1.name, $1.id) }
                .map { medication in
                    let logs = asNeededLogs.filter { $0.medicationId == medication.id }
                    let latest = logs.enumerated().max { a, b in
                        let (ta, tb) = (a.element.takenAt.flatMap(HealthTime.parseInstant)?.instant ?? .distantPast,
                                        b.element.takenAt.flatMap(HealthTime.parseInstant)?.instant ?? .distantPast)
                        return ta != tb ? ta < tb : a.offset < b.offset
                    }?.element
                    let last = latest?.takenAt.flatMap(HealthTime.storedTime)
                    let summary = switch logs.count {
                    case 0: "Not taken today"
                    case 1: "Taken once today, last at \(last ?? "")"
                    default: "Taken \(logs.count) times today, last at \(last ?? "")"
                    }
                    return AsNeededItem(medicationId: medication.id, name: medication.name, dose: medication.dose,
                                        takenToday: logs.count, lastTakenAt: last, summary: summary,
                                        canUndo: !logs.isEmpty)
                }
        }
    }
}
