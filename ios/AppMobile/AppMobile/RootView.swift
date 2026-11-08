import AppCore
import SwiftUI

/// The navigation UI only *reflects* Core's navigator. Swipe-back and the back button shrink the path,
/// which is routed to `goBack()`, so navigation state has a single source of truth (the one the CLI walks).
struct RootView: View {
    let app: AppRoot

    var body: some View {
        let navigator = app.navigator
        NavigationStack(path: Binding(
            get: { Array(navigator.routes.dropFirst()) },
            set: { path in
                while navigator.routes.count - 1 > path.count { navigator.goBack() }
            }
        )) {
            RouteView(route: navigator.routes[0])
                .navigationDestination(for: Route.self) { RouteView(route: $0) }
        }
    }
}

/// One case per screen. A new screen adds a case here.
struct RouteView: View {
    let route: Route

    var body: some View {
        switch route {
        case .sampleList(let viewModel): SampleListView(viewModel: viewModel)
        case .sampleDetail(let viewModel): SampleDetailView(viewModel: viewModel)
        }
    }
}
