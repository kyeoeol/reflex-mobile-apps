import Foundation

/// The JSON view of one object in the app's tree (a ViewModel, a navigator, the app root).
///
/// Node rules (enforced by `./reflex check`):
/// 1. If a user can see it, it's in `state`. If a user can do it, it's in `actions`.
/// 2. Nodes never repeat UI logic: every action is listed every time, with no conditionals.
///    If you need an `if`, the rule belongs in the ViewModel's `update` and the result in `state`.
/// 3. Actions go through `send(action:)` and nothing else.
@MainActor
public protocol IntrospectionNode {
    /// Stable screen identifier: the ViewModel name without the `ViewModel` suffix, PascalCase.
    var name: String { get }
    /// Every field a user could see, in display order.
    var state: JSONObject { get }
    /// One per Action case (PascalCase titles). Containers add lent actions (camelCase titles).
    var actions: [IntrospectionAction] { get }
    /// The node below this one; followed from the root to find the focused node.
    var child: (any IntrospectionNode)? { get }
}

/// Implemented (in `*+Introspection.swift` extensions) by everything that has a node.
/// The app never calls this; delete the extension and the app still builds and runs.
@MainActor
public protocol IntrospectionProviding: AnyObject {
    var introspectionNode: any IntrospectionNode { get }
}

/// An action input. Conform a plain `Decodable` struct; the schema is inferred (see `SchemaInference`).
///
///     struct SetCountInput: IntrospectionInput { let count: Int }
public protocol IntrospectionInput: Decodable {
    static var jsonSchema: JSONValue { get }
}

public extension IntrospectionInput {
    static var jsonSchema: JSONValue { SchemaInference.schema(for: Self.self) }
}

public struct InvalidPayload: Error, CustomStringConvertible, Sendable {
    public let description: String
}

@MainActor
public struct IntrospectionAction {
    public enum Scope: String, Sendable {
        /// The node's own action.
        case local
        /// Deliberately lent by a container to whatever child is focused (e.g. `goBack`).
        case lent
    }

    public let title: String
    public let scope: Scope
    public let inputSchema: JSONValue?
    private let body: @MainActor (JSONValue?) throws -> Void

    public init(title: String, scope: Scope = .local, _ body: @escaping @MainActor () -> Void) {
        self.title = title
        self.scope = scope
        self.inputSchema = nil
        self.body = { payload in
            if let payload, payload != .null { throw InvalidPayload(description: "\(title) takes no input") }
            body()
        }
    }

    public init<Input: IntrospectionInput>(title: String, input: Input.Type, scope: Scope = .local,
                                           _ body: @escaping @MainActor (Input) -> Void) {
        let schema = Input.jsonSchema
        self.title = title
        self.scope = scope
        self.inputSchema = schema
        self.body = { payload in
            let value = (payload == nil || payload == .null) ? JSONValue.object([:]) : payload!
            if let violation = SchemaValidator.validate(value, against: schema) {
                throw InvalidPayload(description: violation)
            }
            let decoded: Input
            do { decoded = try value.decode(Input.self) } catch {
                throw InvalidPayload(description: "\(error)")
            }
            body(decoded)
        }
    }

    /// Validates the payload (throws `InvalidPayload` before anything reaches the ViewModel), then runs.
    public func perform(_ payload: JSONValue?) throws { try body(payload) }
}
