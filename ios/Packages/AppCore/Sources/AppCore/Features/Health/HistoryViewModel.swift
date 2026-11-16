import Foundation
import Observation

/// History (root of the `history` tab): one day's doses and as-needed logs, with the same dose actions as Today.
@MainActor
public final class HistoryViewModel: ViewModel {
    @MainActor @Observable public final class State {
        public fileprivate(set) var isLoading = true
        public fileprivate(set) var date = ""
        public fileprivate(set) var canGoNext = false
        public fileprivate(set) var summaryLabel: String?
        public fileprivate(set) var doses: [DoseItem] = []
        public fileprivate(set) var message: String?
        public fileprivate(set) var doseError: String?
    }

    public enum Action {
        case previousDay
        case nextDay
        case selectDate(String)
        case take(doseId: String)
        case takeAt(doseId: String, takenAt: String)
        case skip(doseId: String)
        case undo(doseId: String)
    }

    public enum Message {
        case viewAction(Action)
        case storeChanged(HealthSnapshot)
    }

    public let state = State()
    private let store: HealthStore
    private var snapshot: HealthSnapshot
    private var day: LocalDate

    init(store: HealthStore) {
        self.store = store
        self.snapshot = store.snapshot
        self.day = store.snapshot.today
        store.subscribe(self) { [weak self] in self?.send(message: .storeChanged($0)) }
        send(message: .storeChanged(store.snapshot))
    }

    public func send(action: Action) { send(message: .viewAction(action)) }

    public func update(state: State, message: Message) {
        func show(_ date: LocalDate) {
            day = date
            let (data, now, today) = (snapshot.data, snapshot.now, snapshot.today)
            let occurrences = data.occurrences(on: date, zone: snapshot.zone)
            let doses = occurrences.map { $0.item(now: now) } + data.asNeededLogs(on: date).map(DoseItem.init(asNeeded:))
            state.isLoading = snapshot.phase == .loading
            state.date = date.text
            state.canGoNext = date < today
            state.summaryLabel = HealthRules.progressLabel(occurrences)
            state.doses = doses.sorted { a, b in
                a.sortTime != b.sortTime ? a.sortTime < b.sortTime : HealthRules.byName(a.name, a.id, b.name, b.id)
            }
            state.message = snapshot.phase != .loading && doses.isEmpty ? "Nothing recorded on this day" : nil
        }
        func apply(_ outcome: DoseOutcome) {
            switch outcome {
            case .applied: state.doseError = nil
            case .rejected(let error): state.doseError = error
            case .ignored: break
            }
        }

        switch message {
        case .viewAction(.previousDay):
            show(day.adding(days: -1))
        case .viewAction(.nextDay) where day < snapshot.today:
            show(day.adding(days: 1))
        case .viewAction(.selectDate(let text)):
            if let date = LocalDate(text), date <= snapshot.today { show(date) }
        case .viewAction(.take(let doseId)):
            apply(store.take(doseId: doseId))
        case .viewAction(.takeAt(let doseId, let takenAt)):
            apply(store.take(doseId: doseId, takenAt: takenAt))
        case .viewAction(.skip(let doseId)):
            apply(store.skip(doseId: doseId))
        case .viewAction(.undo(let doseId)):
            apply(store.undo(doseId: doseId))
        case .viewAction(.nextDay):
            break   // already at today
        case .storeChanged(let snapshot):
            self.snapshot = snapshot
            show(day)
        }
    }
}
