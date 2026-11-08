import Foundation

/// A GraphQL operation. `operationName` is what fixtures match on (`fixtures/**.json` → `"operation"`),
/// and must be identical on Android.
public protocol GraphQLOperation: Sendable {
    associatedtype Variables: Encodable & Sendable = NoVariables
    associatedtype ResponseData: Decodable & Sendable

    static var operationName: String { get }
    static var document: String { get }
    var variables: Variables { get }
}

public extension GraphQLOperation where Variables == NoVariables {
    var variables: NoVariables { NoVariables() }
}

public struct NoVariables: Encodable, Sendable {
    public init() {}
}

public enum GraphQLError: Error, Sendable, Equatable {
    case server([String])
    case missingData
}

/// The real client. Only `GraphQLTransport` differs between the app and the CLI.
public struct GraphQLClient: Sendable {
    private let transport: any GraphQLTransport

    public init(transport: any GraphQLTransport) { self.transport = transport }

    public func execute<Operation: GraphQLOperation>(_ operation: Operation) async throws -> Operation.ResponseData {
        let request = GraphQLRequest(operationName: Operation.operationName, query: Operation.document,
                                     variables: try JSONEncoder().encode(operation.variables))
        let body = try await transport.execute(request)
        let envelope = try JSONDecoder().decode(Envelope<Operation.ResponseData>.self, from: body)
        if let errors = envelope.errors, !errors.isEmpty { throw GraphQLError.server(errors.map(\.message)) }
        guard let data = envelope.data else { throw GraphQLError.missingData }
        return data
    }

    private struct Envelope<T: Decodable>: Decodable {
        let data: T?
        let errors: [ServerError]?
    }

    private struct ServerError: Decodable {
        let message: String
    }
}
