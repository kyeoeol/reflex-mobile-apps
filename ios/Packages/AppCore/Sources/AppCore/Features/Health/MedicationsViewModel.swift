import Foundation
import Observation

/// Medications (root of the `medications` tab): every medication, split into active, ended and archived.
@MainActor
public final class MedicationsViewModel: ViewModel {
    public struct Row: Sendable, Equatable {
        public let id: String
        public let name: String
        public let kind: String
        public let dose: String
        public let schedule: String
    }

    @MainActor @Observable public final class State {
        public fileprivate(set) var isLoading = true
        /// Not loading and no medication at all (also after a failed load).
        public fileprivate(set) var isEmpty = false
        public fileprivate(set) var active: [Row] = []
        public fileprivate(set) var ended: [Row] = []
        public fileprivate(set) var archived: [Row] = []
    }

    public enum Action {
        case add
        case open(id: String)
    }

    public enum Message {
        case viewAction(Action)
        case storeChanged(HealthSnapshot)
    }

    public let state = State()
    private weak var navigation: (any HealthNavigating)?
    private var snapshot: HealthSnapshot

    init(store: HealthStore, navigation: any HealthNavigating) {
        self.navigation = navigation
        self.snapshot = store.snapshot
        store.subscribe(self) { [weak self] in self?.send(message: .storeChanged($0)) }
        send(message: .storeChanged(store.snapshot))
    }

    public func send(action: Action) { send(message: .viewAction(action)) }

    public func update(state: State, message: Message) {
        switch message {
        case .viewAction(.add):
            navigation?.showMedicationEditor(medicationId: nil)
        case .viewAction(.open(let id)) where snapshot.data.medication(id) != nil:
            navigation?.showMedicationDetail(id: id)
        case .viewAction(.open):
            break   // unknown medication: no-op
        case .storeChanged(let snapshot):
            self.snapshot = snapshot
            let today = snapshot.today
            let medications = snapshot.data.medications.sorted { HealthRules.byName($0.name, $0.id, $1.name, $1.id) }
            func rows(_ status: MedicationStatus) -> [Row] {
                medications.filter { $0.status(today: today) == status }.map { medication in
                    Row(id: medication.id, name: medication.name, kind: medication.kind.rawValue, dose: medication.dose,
                        schedule: medication.scheduleSummary)
                }
            }
            state.isLoading = snapshot.phase == .loading
            state.isEmpty = snapshot.phase != .loading && medications.isEmpty
            state.active = rows(.active)
            state.ended = rows(.ended)
            state.archived = rows(.archived)
        }
    }
}
