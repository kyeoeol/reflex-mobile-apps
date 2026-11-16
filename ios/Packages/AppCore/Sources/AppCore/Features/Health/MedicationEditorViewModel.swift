import Foundation
import Observation

/// Adding or editing a medication. Errors stay hidden until the first Save attempt, then follow every edit.
@MainActor
public final class MedicationEditorViewModel: ViewModel {
    static let maxTimes = 12

    @MainActor @Observable public final class State {
        /// `"add"` or `"edit"`.
        public let mode: String
        public let medicationId: String?
        public fileprivate(set) var name = ""
        public fileprivate(set) var kind = MedicationKind.medication.rawValue
        public fileprivate(set) var dose = ""
        public fileprivate(set) var notes = ""
        public fileprivate(set) var asNeeded = false
        public fileprivate(set) var times: [String] = []
        public fileprivate(set) var weekdays = [1, 2, 3, 4, 5, 6, 7]
        public fileprivate(set) var startDate = ""
        public fileprivate(set) var endDate: String?
        public fileprivate(set) var nameError: String?
        public fileprivate(set) var timesError: String?
        public fileprivate(set) var weekdaysError: String?
        public fileprivate(set) var datesError: String?
        public fileprivate(set) var timeError: String?
        public fileprivate(set) var canAddTime = true
        /// Only a new medication can be made as-needed (or not).
        public fileprivate(set) var canChangeAsNeeded: Bool
        /// No validation error (shown or not yet).
        public fileprivate(set) var isValid = false
        /// The Save button is enabled: not saving and the stored data loaded without error. It stays enabled
        /// while the form is invalid, so the first attempt can reveal the errors.
        public fileprivate(set) var canSave = false
        public fileprivate(set) var isSaving = false

        init(medicationId: String?) {
            self.mode = medicationId == nil ? "add" : "edit"
            self.medicationId = medicationId
            self.canChangeAsNeeded = medicationId == nil
        }
    }

    public enum Action {
        case setName(String)
        case setKind(String)
        case setDose(String)
        case setNotes(String)
        case setAsNeeded(Bool)
        case addTime(String)
        case removeTime(String)
        case toggleWeekday(Int)
        case setStartDate(String)
        case setEndDate(String?)
        case save
    }

    public enum Message {
        case viewAction(Action)
        case storeChanged(HealthSnapshot)
    }

    public let state: State
    private let store: HealthStore
    private weak var navigation: (any HealthNavigating)?
    private var snapshot: HealthSnapshot
    private var hasAttemptedSave = false
    private var hasDraft = false

    /// `medicationId` nil: add mode.
    init(medicationId: String?, store: HealthStore, navigation: any HealthNavigating) {
        self.state = State(medicationId: medicationId)
        self.store = store
        self.navigation = navigation
        self.snapshot = store.snapshot
        store.subscribe(self) { [weak self] in self?.send(message: .storeChanged($0)) }
        send(message: .storeChanged(store.snapshot))
    }

    public func send(action: Action) { send(message: .viewAction(action)) }

    public func update(state: State, message: Message) {
        /// Fills the form once: add-mode defaults, or the medication with the latest version's times/weekdays.
        func fillDraft() {
            let today = snapshot.today
            guard !hasDraft else { return }
            guard let id = state.medicationId else {
                state.startDate = today.text
                hasDraft = true
                return
            }
            guard snapshot.phase == .loaded, let medication = snapshot.data.medication(id) else { return }
            state.name = medication.name
            state.kind = medication.kind.rawValue
            state.dose = medication.dose
            state.notes = medication.notes
            state.asNeeded = medication.asNeeded
            state.times = medication.latestTimes
            state.weekdays = medication.latestSchedule == nil ? [1, 2, 3, 4, 5, 6, 7] : medication.latestWeekdays
            state.startDate = medication.startDate
            state.endDate = medication.endDate
            hasDraft = true
        }

        func validate() {
            let name = HealthRules.trimmed(state.name)
            let key = HealthRules.nameKey(name)
            let today = snapshot.today
            let isDuplicate = snapshot.data.medications.contains { other in
                other.id != state.medicationId && other.status(today: today) == .active
                    && HealthRules.nameKey(HealthRules.trimmed(other.name)) == key
            }
            let nameError: String? = name.isEmpty ? "Name is required"
                : isDuplicate ? "A medication with this name already exists" : nil
            let timesError: String? = !state.asNeeded && state.times.isEmpty ? "Add at least one time" : nil
            let weekdaysError: String? = !state.asNeeded && state.weekdays.isEmpty ? "Pick at least one day" : nil
            let datesError: String? = state.endDate.map { $0 < state.startDate } == true ? "End date can't be before start date" : nil
            if hasAttemptedSave {
                state.nameError = nameError
                state.timesError = timesError
                state.weekdaysError = weekdaysError
                state.datesError = datesError
            }
            state.isValid = nameError == nil && timesError == nil && weekdaysError == nil && datesError == nil
            state.canAddTime = state.times.count < Self.maxTimes
            state.canSave = !state.isSaving && snapshot.phase == .loaded
        }

        switch message {
        case .viewAction(.setName(let name)):
            state.name = name
        case .viewAction(.setKind(let kind)):
            if let kind = MedicationKind(rawValue: kind) { state.kind = kind.rawValue }
        case .viewAction(.setDose(let dose)):
            state.dose = dose
        case .viewAction(.setNotes(let notes)):
            state.notes = notes
        case .viewAction(.setAsNeeded(let asNeeded)) where state.canChangeAsNeeded:
            state.asNeeded = asNeeded
        case .viewAction(.setAsNeeded):
            break   // edit mode: whether it is as-needed never changes
        case .viewAction(.addTime(let time)) where state.canAddTime:
            if !HealthTime.isTime(time) {
                state.timeError = "Enter a time as HH:mm"
            } else if state.times.contains(time) {
                state.timeError = "Each time can only be added once"
            } else {
                state.times = (state.times + [time]).sorted()
                state.timeError = nil
            }
        case .viewAction(.addTime):
            break   // twelve times already
        case .viewAction(.removeTime(let time)):
            if state.times.contains(time) {
                state.times.removeAll { $0 == time }
                state.timeError = nil
            }
        case .viewAction(.toggleWeekday(let weekday)) where (1...7).contains(weekday):
            state.weekdays = state.weekdays.contains(weekday) ? state.weekdays.filter { $0 != weekday } : (state.weekdays + [weekday]).sorted()
        case .viewAction(.toggleWeekday):
            break   // not a weekday
        case .viewAction(.setStartDate(let text)):
            if let date = LocalDate(text) { state.startDate = date.text }
        case .viewAction(.setEndDate(nil)):
            state.endDate = nil
        case .viewAction(.setEndDate(let text?)):
            if let date = LocalDate(text) { state.endDate = date.text }
        case .viewAction(.save) where state.canSave:
            hasAttemptedSave = true
            validate()
            guard state.isValid, let kind = MedicationKind(rawValue: state.kind) else { break }
            let draft = MedicationDraft(
                id: state.medicationId, name: HealthRules.trimmed(state.name), kind: kind,
                dose: state.dose, notes: state.notes, asNeeded: state.asNeeded,
                times: state.asNeeded ? [] : state.times, weekdays: state.asNeeded ? [] : state.weekdays,
                startDate: state.startDate, endDate: state.endDate)
            let permission = snapshot.permission
            state.isSaving = true
            guard store.save(draft) != nil else {
                state.isSaving = false
                break
            }
            navigation?.close(self)
            // The first scheduled medication is when Health Beta asks for notification permission.
            if !draft.times.isEmpty, permission == .notDetermined { store.requestPermission() }
        case .viewAction(.save):
            break   // the stored data didn't load (or a save is under way): nothing can be saved
        case .storeChanged(let snapshot):
            self.snapshot = snapshot
            fillDraft()
        }
        validate()
    }
}
