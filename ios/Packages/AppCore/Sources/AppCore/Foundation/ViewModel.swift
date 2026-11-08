import Observation

/// The strict, Elm-style contract every screen follows: actions in, state out, one place where state changes.
///
/// - `State`: everything the screen shows. The View reads it and nothing else.
/// - `Action`: the public surface: everything a View (or the CLI) can ask for. Only via `send(action:)`.
/// - `Message`: the internal surface. Actions arrive as `.viewAction(Action)`; service results come back as messages.
/// - `update(state:message:)`: the reducer, and **the only place state ever changes**.
///
/// (A protocol rather than the base class from the original pseudo-code: in Swift, a class can't use its
/// own nested types as generic arguments to its superclass.)
@MainActor
public protocol ViewModel: AnyObject {
    associatedtype State: AnyObject & Observable
    associatedtype Action
    associatedtype Message

    var state: State { get }
    func send(action: Action)
    func update(state: State, message: Message)
}

public extension ViewModel {
    func send(message: Message) { update(state: state, message: message) }
}
