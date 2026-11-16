import Foundation
import Observation

/// One medication: its schedule, 30-day adherence and history; archive, restore, delete.
@MainActor
public final class MedicationDetailViewModel: ViewModel {
    @MainActor @Observable public final class State {
        public let id: String
        public fileprivate(set) var name = ""
        public fileprivate(set) var kind = ""
        public fileprivate(set) var dose = ""
        public fileprivate(set) var notes = ""
        public fileprivate(set) var schedule = ""
        public fileprivate(set) var startDate = ""
        public fileprivate(set) var endDate: String?
        public fileprivate(set) var status = MedicationStatus.active.rawValue
        public fileprivate(set) var adherenceLabel = "No data yet"
        /// `"<taken> of <n> doses taken in the last 30 days"`; nil with "No data yet".
        public fileprivate(set) var adherenceCaption: String?
        public fileprivate(set) var takenCount = 0
        public fileprivate(set) var skippedCount = 0
        public fileprivate(set) var missedCount = 0
        public fileprivate(set) var history: [DoseItem] = []
        public fileprivate(set) var canArchive = false
        public fileprivate(set) var canRestore = false
        public fileprivate(set) var isConfirmingDelete = false

        init(id: String) { self.id = id }
    }

    public enum Action {
        case edit
        case archive
        case restore
        case delete
        case cancelDelete
        case confirmDelete
    }

    public enum Message {
        case viewAction(Action)
        case storeChanged(HealthSnapshot)
    }

    /// Adherence and history cover the 30 days ending today.
    static let windowDays = 30

    public let state: State
    private let store: HealthStore
    private weak var navigation: (any HealthNavigating)?
    private var snapshot: HealthSnapshot

    init(id: String, store: HealthStore, navigation: any HealthNavigating) {
        self.state = State(id: id)
        self.store = store
        self.navigation = navigation
        self.snapshot = store.snapshot
        store.subscribe(self) { [weak self] in self?.send(message: .storeChanged($0)) }
        send(message: .storeChanged(store.snapshot))
    }

    public func send(action: Action) { send(message: .viewAction(action)) }

    public func update(state: State, message: Message) {
        switch message {
        case .viewAction(.edit) where snapshot.data.medication(state.id) != nil:
            navigation?.showMedicationEditor(medicationId: state.id)
        case .viewAction(.edit):
            break   // unknown medication: no-op
        case .viewAction(.archive):
            store.archive(medicationId: state.id)
        case .viewAction(.restore):
            store.restore(medicationId: state.id)
        case .viewAction(.delete):
            state.isConfirmingDelete = true
        case .viewAction(.cancelDelete):
            state.isConfirmingDelete = false
        case .viewAction(.confirmDelete) where state.isConfirmingDelete:
            // The screen then disappears with the medication (AppRoot removes screens of deleted medications).
            state.isConfirmingDelete = false
            store.delete(medicationId: state.id)
        case .viewAction(.confirmDelete):
            break   // nothing happens without asking first
        case .storeChanged(let snapshot):
            self.snapshot = snapshot
            let (data, now, today) = (snapshot.data, snapshot.now, snapshot.today)
            let medication = data.medication(state.id)
            let window = (0..<Self.windowDays).map { today.adding(days: -$0) }
            let doses = window.flatMap { date in
                data.occurrences(on: date, zone: snapshot.zone).filter { $0.medicationId == state.id }.map { $0.item(now: now) }
            }
            let firstDay = window[window.count - 1].text
            let asNeeded = data.logs
                .filter { $0.medicationId == state.id && $0.time == nil && $0.date >= firstDay && $0.date <= today.text }
                .map(DoseItem.init(asNeeded:))
            let counts = (taken: doses.filter { $0.status == DoseStatus.taken.rawValue }.count,
                          skipped: doses.filter { $0.status == DoseStatus.skipped.rawValue }.count,
                          missed: doses.filter { $0.status == DoseStatus.missed.rawValue }.count)
            let resolved = counts.taken + counts.skipped + counts.missed
            let status = medication?.status(today: today) ?? .active

            state.name = medication?.name ?? ""
            state.kind = medication?.kind.rawValue ?? ""
            state.dose = medication?.dose ?? ""
            state.notes = medication?.notes ?? ""
            state.schedule = medication?.scheduleSummary ?? ""
            state.startDate = medication?.startDate ?? ""
            state.endDate = medication?.endDate
            state.status = status.rawValue
            // round-half-up(100 × taken / resolved), in integers.
            state.adherenceLabel = resolved == 0 ? "No data yet" : "\((200 * counts.taken + resolved) / (2 * resolved))%"
            state.adherenceCaption = resolved == 0 ? nil : "\(counts.taken) of \(resolved) doses taken in the last 30 days"
            state.takenCount = counts.taken
            state.skippedCount = counts.skipped
            state.missedCount = counts.missed
            state.history = (doses.filter { $0.status != DoseStatus.upcoming.rawValue } + asNeeded).sorted { a, b in
                if a.date != b.date { return a.date > b.date }
                if a.sortTime != b.sortTime { return a.sortTime > b.sortTime }
                return HealthRules.precedes(a.id, b.id)
            }
            state.canArchive = medication != nil && status != .archived
            state.canRestore = medication != nil && status == .archived
        }
    }
}
