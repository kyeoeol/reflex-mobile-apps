import Foundation
import Observation

/// Navigation the Sample list needs. Implemented by `AppRoot`; held weakly to avoid a cycle.
@MainActor
public protocol SampleNavigating: AnyObject {
    func showSampleDetail(id: String)
}

@MainActor
public final class SampleListViewModel: ViewModel {
    @MainActor @Observable public final class State {
        public var items: [SampleItem] = []
        public var isLoading = false
        public var loadError: String?
    }

    public enum Action {
        case selectItem(id: String)
        case refresh
    }

    public enum Message {
        case viewAction(Action)
        case load
        case loaded([SampleItem])
        case loadFailed
    }

    public let state = State()
    private let service: any SampleService
    private weak var navigation: (any SampleNavigating)?

    init(service: any SampleService, navigation: any SampleNavigating) {
        self.service = service
        self.navigation = navigation
        send(message: .load)
    }

    public func send(action: Action) { send(message: .viewAction(action)) }

    public func update(state: State, message: Message) {
        switch message {
        case .viewAction(.selectItem(let id)):
            navigation?.showSampleDetail(id: id)
        case .viewAction(.refresh), .load:
            state.isLoading = true
            state.loadError = nil
            Task { [service] in
                do { self.send(message: .loaded(try await service.items())) } catch { self.send(message: .loadFailed) }
            }
        case .loaded(let items):
            state.items = items
            state.isLoading = false
        case .loadFailed:
            state.isLoading = false
            state.loadError = "Couldn't load items."
        }
    }
}
