import Foundation

/// Every screen the app can show. Each case wraps that screen's ViewModel.
/// Hashable by ViewModel identity so SwiftUI's `NavigationStack(path:)` can reflect the navigator.
public enum Route: Routable, Hashable {
    case sampleList(SampleListViewModel)
    case sampleDetail(SampleDetailViewModel)

    public var viewModel: AnyObject {
        switch self {
        case .sampleList(let viewModel): viewModel
        case .sampleDetail(let viewModel): viewModel
        }
    }

    public static func == (lhs: Route, rhs: Route) -> Bool {
        ObjectIdentifier(lhs.viewModel) == ObjectIdentifier(rhs.viewModel)
    }

    public func hash(into hasher: inout Hasher) { hasher.combine(ObjectIdentifier(viewModel)) }
}

/// Root of the object tree: owns services, session and persistence, and the navigator.
///
///     AppRoot ─ StackNavigator(routes) ─ <top route's ViewModel>
///
/// It is also the factory for new screens: ViewModels ask for navigation through small `*Navigating`
/// protocols (held weakly), and AppRoot builds the next ViewModel with its services and pushes it.
@MainActor
public final class AppRoot {
    public let navigator = StackNavigator<Route>()
    let services: Services

    init(services: Services) {
        self.services = services
        navigator.reset([.sampleList(makeSampleList())])
    }

    /// Deep links change navigator state, so they behave identically in the CLI.
    /// Paths are scheme-agnostic: `<any-scheme>://sample/<id>` opens that item's detail.
    @discardableResult
    public func open(_ url: URL) -> Bool {
        let parts = [url.host].compactMap { $0 } + url.pathComponents.filter { $0 != "/" }
        switch parts.first {
        case "sample" where parts.count == 2:
            navigator.reset([.sampleList(makeSampleList())])
            showSampleDetail(id: parts[1])
            return true
        default:
            return false
        }
    }

    // MARK: - Factories

    private func makeSampleList() -> SampleListViewModel {
        SampleListViewModel(service: GraphQLSampleService(client: services.client), navigation: self)
    }
}

extension AppRoot: SampleNavigating {
    public func showSampleDetail(id: String) {
        navigator.push(.sampleDetail(SampleDetailViewModel(itemId: id, service: GraphQLSampleService(client: services.client))))
    }
}
