import Foundation

/// Every screen the app can show. Each case wraps that screen's ViewModel.
/// Hashable by ViewModel identity so SwiftUI's `NavigationStack(path:)` can reflect the navigator.
public enum Route: Routable, Hashable {
    case today(TodayViewModel)
    case medications(MedicationsViewModel)
    case history(HistoryViewModel)
    case reminders(RemindersViewModel)
    case medicationEditor(MedicationEditorViewModel)
    case medicationDetail(MedicationDetailViewModel)

    public var viewModel: AnyObject {
        switch self {
        case .today(let viewModel): viewModel
        case .medications(let viewModel): viewModel
        case .history(let viewModel): viewModel
        case .reminders(let viewModel): viewModel
        case .medicationEditor(let viewModel): viewModel
        case .medicationDetail(let viewModel): viewModel
        }
    }

    public static func == (lhs: Route, rhs: Route) -> Bool {
        ObjectIdentifier(lhs.viewModel) == ObjectIdentifier(rhs.viewModel)
    }

    public func hash(into hasher: inout Hasher) { hasher.combine(ObjectIdentifier(viewModel)) }
}

/// The tab bar's tabs, in order.
public enum AppTab: String, CaseIterable, Sendable {
    case today, medications, history
}

/// Root of the object tree: owns services, the Health store and the navigators.
///
///     AppRoot ─ TabNavigator(today | medications | history) ─ StackNavigator(routes) ─ <top route's ViewModel>
///
/// It is also the factory for new screens: ViewModels ask for navigation through `HealthNavigating` (held weakly),
/// and AppRoot builds the next ViewModel with its services and pushes it on the current tab's stack.
@MainActor
public final class AppRoot {
    public let tabs = TabNavigator<AppTab, Route>(tabs: AppTab.allCases, selected: .today)
    let services: Services
    let store: HealthStore
    /// Bumped by every link and notification response, so a medication link waiting for the data to load is
    /// dropped when the user has gone somewhere else in the meantime.
    private var navigationRequest = 0

    init(services: Services) {
        self.services = services
        self.store = HealthStore(repository: services.healthRepository, scheduler: services.reminderScheduler,
                                 clock: services.clock)
        for tab in AppTab.allCases { resetTab(tab) }
        store.subscribe(self) { [weak self] in self?.removeStaleRoutes($0) }
        store.start()
    }

    /// Deep links only navigate (any app can open a URL, so a link never changes data). Scheme-agnostic:
    /// - `<s>://today`: select `today`, reset it to `[Today]`
    /// - `<s>://medication/<id>`: select `medications`, reset it to `[Medications]`; once the data has loaded,
    ///   push that medication's detail if it exists
    /// - `<s>://reminder/<slot>[/…]`: select `today`, reset it to `[Today]`; any further path is ignored
    @discardableResult
    public func open(_ url: URL) -> Bool {
        let parts = [url.host].compactMap { $0 } + url.pathComponents.filter { $0 != "/" }
        switch (parts.first, parts.count) {
        case ("today", 1):
            showTab(.today)
            return true
        case ("medication", 2):
            showTab(.medications)
            let (request, id) = (navigationRequest, parts[1])
            store.whenLoaded { [weak self] data in
                guard let self, self.navigationRequest == request, data.medication(id) != nil else { return }
                self.tabs.stack(.medications).push(self.medicationDetail(id: id))
            }
            return true
        case ("reminder", 2...) where HealthRules.parseSlot(parts[1]) != nil:
            showTab(.today)
            return true
        default:
            return false
        }
    }

    /// A notification response (a tap, `open`, or one of its buttons): selects `today`, resets it to `[Today]`
    /// and applies the response to the slot's unlogged doses (once the data has loaded, if it hasn't yet).
    /// Returns when it has been fully handled: data loaded (or failed to), response applied, snapshot saved and
    /// reminders reconciled. Background entry points await it before telling the OS they are done.
    public func handleReminderResponse(slot: String, action: ReminderResponse) async {
        showTab(.today)
        await store.respond(action, slot: slot)
    }

    /// Returns once the initial load and every save and reminder reconciliation requested so far have finished
    /// (immediately after a failed load with nothing pending). Await it before the process may be suspended.
    public func settle() async {
        await store.settle()
    }

    /// Selects `tab` and resets its stack to its root screen.
    private func showTab(_ tab: AppTab) {
        navigationRequest += 1
        tabs.select(tab)
        resetTab(tab)
    }

    private func resetTab(_ tab: AppTab) {
        let root: Route = switch tab {
        case .today: .today(TodayViewModel(store: store, navigation: self))
        case .medications: .medications(MedicationsViewModel(store: store, navigation: self))
        case .history: .history(HistoryViewModel(store: store))
        }
        tabs.stack(tab).reset([root])
    }

    private func medicationDetail(id: String) -> Route {
        .medicationDetail(MedicationDetailViewModel(id: id, store: store, navigation: self))
    }

    /// Whenever the data no longer contains a medication, its detail and edit-mode editor leave every stack.
    private func removeStaleRoutes(_ snapshot: HealthSnapshot) {
        guard snapshot.phase == .loaded else { return }
        let data = snapshot.data
        for tab in tabs.tabs {
            let stack = tabs.stack(tab)
            let kept = stack.routes.filter { route in
                switch route {
                case .medicationDetail(let viewModel): data.medication(viewModel.state.id) != nil
                case .medicationEditor(let viewModel): viewModel.state.medicationId.map { data.medication($0) != nil } ?? true
                case .today, .medications, .history, .reminders: true
                }
            }
            if kept.count != stack.routes.count { stack.reset(kept) }
        }
    }
}

extension AppRoot: HealthNavigating {
    public func showReminders() {
        tabs.selectedStack.push(.reminders(RemindersViewModel(store: store)))
    }

    public func showMedicationEditor(medicationId: String?) {
        tabs.selectedStack.push(.medicationEditor(MedicationEditorViewModel(medicationId: medicationId, store: store, navigation: self)))
    }

    public func showMedicationDetail(id: String) {
        tabs.selectedStack.push(medicationDetail(id: id))
    }

    public func close(_ screen: AnyObject) {
        for tab in tabs.tabs where tabs.stack(tab).top?.viewModel === screen {
            tabs.stack(tab).goBack()
        }
    }
}
