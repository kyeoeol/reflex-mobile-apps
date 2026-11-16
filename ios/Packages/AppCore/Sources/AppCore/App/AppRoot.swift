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

    init(services: Services) {
        self.services = services
        self.store = HealthStore(repository: services.healthRepository, scheduler: services.reminderScheduler,
                                 clock: services.clock)
        for tab in AppTab.allCases { resetTab(tab) }
        store.start()
    }

    /// Deep links change navigator state (and, for notification actions, the data), so they behave identically in
    /// the CLI. Scheme-agnostic:
    /// - `<s>://today`
    /// - `<s>://medication/<id>`
    /// - `<s>://reminder/<slot>[/taken|/skipped|/snooze]` (notification taps and actions; applied once loaded)
    @discardableResult
    public func open(_ url: URL) -> Bool {
        let parts = [url.host].compactMap { $0 } + url.pathComponents.filter { $0 != "/" }
        switch (parts.first, parts.count) {
        case ("today", 1):
            showTab(.today)
            return true
        case ("medication", 2):
            showTab(.medications)
            showMedicationDetail(id: parts[1])
            return true
        case ("reminder", 2), ("reminder", 3):
            let response = parts.count == 3 ? ReminderResponse(rawValue: parts[2]) : .open
            guard let response, response != .open || parts.count == 2, HealthRules.parseSlot(parts[1]) != nil else { return false }
            showTab(.today)
            store.respond(response, slot: parts[1])
            return true
        default:
            return false
        }
    }

    /// Selects `tab` and resets its stack to its root screen.
    private func showTab(_ tab: AppTab) {
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
}

extension AppRoot: HealthNavigating {
    public func showReminders() {
        tabs.selectedStack.push(.reminders(RemindersViewModel(store: store)))
    }

    public func showMedicationEditor(medicationId: String?) {
        tabs.selectedStack.push(.medicationEditor(MedicationEditorViewModel(medicationId: medicationId, store: store, navigation: self)))
    }

    public func showMedicationDetail(id: String) {
        tabs.selectedStack.push(.medicationDetail(MedicationDetailViewModel(id: id, store: store, navigation: self)))
    }

    public func close(_ screen: AnyObject) {
        for tab in tabs.tabs where tabs.stack(tab).top?.viewModel === screen {
            tabs.stack(tab).goBack()
        }
    }
}
