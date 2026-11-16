import AppCore
import SwiftUI

/// The navigation UI only *reflects* Core's navigators: the tab bar shows `tabs.selectedTab` and routes taps to
/// `select(_:)`; each tab's `NavigationStack` shows that tab's `StackNavigator`. Swipe-back, the back button and
/// re-tapping a tab shrink the path, which is routed to `goBack()`, so navigation state has a single source of truth
/// (the one the CLI walks).
struct RootView: View {
    let app: AppRoot

    var body: some View {
        let tabs = app.tabs
        TabView(selection: Binding(get: { tabs.selectedTab }, set: { tabs.select($0) })) {
            ForEach(tabs.tabs, id: \.self) { tab in
                Tab(tab.title, systemImage: tab.symbol, value: tab) {
                    TabStackView(stack: tabs.stack(tab))
                }
                .accessibilityIdentifier("tab.\(tab.rawValue)")
            }
        }
        .tint(HealthColors.primary)
    }
}

private extension AppTab {
    var title: String {
        switch self {
        case .today: "Today"
        case .medications: "Medications"
        case .history: "History"
        }
    }

    var symbol: String {
        switch self {
        case .today: "checklist"
        case .medications: "pill"
        case .history: "calendar"
        }
    }
}

/// One tab's stack. The root route is keyed by its ViewModel, so a reset tab starts with fresh view state.
struct TabStackView: View {
    let stack: StackNavigator<Route>

    var body: some View {
        NavigationStack(path: Binding(
            get: { Array(stack.routes.dropFirst()) },
            set: { path in
                while stack.routes.count - 1 > path.count { stack.goBack() }
            }
        )) {
            if let root = stack.routes.first {
                RouteView(route: root, goBack: { stack.goBack() })
                    .id(root)
                    .navigationDestination(for: Route.self) { RouteView(route: $0, goBack: { stack.goBack() }) }
            }
        }
    }
}

/// One case per screen. A new screen adds a case here.
struct RouteView: View {
    let route: Route
    let goBack: () -> Void

    var body: some View {
        switch route {
        case .today(let viewModel): TodayView(viewModel: viewModel)
        case .medications(let viewModel): MedicationsView(viewModel: viewModel)
        case .history(let viewModel): HistoryView(viewModel: viewModel)
        case .reminders(let viewModel): RemindersView(viewModel: viewModel)
        case .medicationEditor(let viewModel): MedicationEditorView(viewModel: viewModel, onCancel: goBack)
        case .medicationDetail(let viewModel): MedicationDetailView(viewModel: viewModel)
        }
    }
}
