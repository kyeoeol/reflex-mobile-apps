import Foundation
import Observation

@MainActor
public final class SampleDetailViewModel: ViewModel {
    @MainActor @Observable public final class State {
        public let itemId: String
        public var title = ""
        public var count = 0
        public var isLoading = true
        public var loadError: String?
        public var isSaving = false
        public var isSaved = false
        public var saveError: String?

        /// Derived in the ViewModel, so neither the View nor the node needs an `if`.
        public var canDecrement: Bool { count > 0 }

        init(itemId: String) { self.itemId = itemId }
    }

    public enum Action {
        case increment
        case decrement
        case setCount(Int)
        case save
    }

    public enum Message {
        case viewAction(Action)
        case load
        case loaded(SampleItem)
        case loadFailed
        case saved
        case saveFailed
    }

    public let state: State
    private let service: any SampleService

    init(itemId: String, service: any SampleService) {
        self.state = State(itemId: itemId)
        self.service = service
        send(message: .load)
    }

    public func send(action: Action) { send(message: .viewAction(action)) }

    public func update(state: State, message: Message) {
        func edit(count: Int) {
            state.count = count
            state.isSaved = false
            state.saveError = nil
        }

        switch message {
        case .viewAction(.increment):
            edit(count: state.count + 1)
        case .viewAction(.decrement) where state.count > 0:
            edit(count: state.count - 1)
        case .viewAction(.setCount(let count)) where count >= 0:
            edit(count: count)
        case .viewAction(.save) where !state.isSaving:
            state.isSaving = true
            state.saveError = nil
            let (id, count) = (state.itemId, state.count)
            Task { [service] in
                do { try await service.save(id: id, count: count); self.send(message: .saved) } catch { self.send(message: .saveFailed) }
            }
        case .load:
            let id = state.itemId
            Task { [service] in
                do { self.send(message: .loaded(try await service.item(id: id))) } catch { self.send(message: .loadFailed) }
            }
        case .loaded(let item):
            state.title = item.title
            state.count = item.count
            state.isLoading = false
        case .loadFailed:
            state.isLoading = false
            state.loadError = "Couldn't load this item."
        case .saved:
            state.isSaving = false
            state.isSaved = true
        case .saveFailed:
            state.isSaving = false
            state.saveError = "Couldn't save. Try again."
        default:
            break   // decrement at zero, negative counts, save while saving: no-ops by rule
        }
    }
}
