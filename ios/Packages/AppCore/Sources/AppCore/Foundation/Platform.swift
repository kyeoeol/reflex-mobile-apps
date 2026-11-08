import Foundation

// Everything Core needs from the outside world is declared here as a protocol and passed to `Core.init`.
// The Mobile app passes real implementations (Keychain, URLSession, ...); the CLI passes fixtures.
// Add a new edge here (never import a platform framework into Core); see the reflex-add-edge skill.

@MainActor
public protocol Storage: AnyObject {
    func read(_ key: String) -> String?
    func write(_ key: String, _ value: String?)
}

@MainActor
public protocol DeviceCapabilities: AnyObject {
    var biometricsAvailable: Bool { get }
}

@MainActor
public protocol FeatureFlags: AnyObject {
    func isEnabled(_ flag: String) -> Bool
}

/// The network edge. Core's `GraphQLClient` is real; only this transport is swapped.
public protocol GraphQLTransport: Sendable {
    /// Returns the raw response body (`{"data": ..., "errors": [...]}`).
    func execute(_ request: GraphQLRequest) async throws -> Data
}

public struct GraphQLRequest: Sendable {
    public let operationName: String
    public let query: String
    /// JSON-encoded variables object.
    public let variables: Data

    public init(operationName: String, query: String, variables: Data) {
        self.operationName = operationName
        self.query = query
        self.variables = variables
    }
}

public enum GraphQLTransportError: Error, Sendable, Equatable {
    case http(status: Int)
    case network(String)
}
