import Foundation

// REFERENCE FEATURE: "Sample" exists only to show the architecture end to end. Delete it (Swift, Kotlin,
// views, fixtures/Sample, tests/behavior/sample.cli-test.ts) once the first real feature lands.

public struct SampleItem: Codable, Sendable, Equatable {
    public let id: String
    public let title: String
    public let count: Int
}

/// Side effects for the Sample screens. A protocol, injected through ViewModel constructors.
public protocol SampleService: Sendable {
    func items() async throws -> [SampleItem]
    func item(id: String) async throws -> SampleItem
    func save(id: String, count: Int) async throws
}

/// The real implementation: real GraphQL client; in the CLI only the transport underneath is a fixture.
struct GraphQLSampleService: SampleService {
    let client: GraphQLClient

    func items() async throws -> [SampleItem] {
        try await client.execute(SampleItemsQuery()).items
    }

    func item(id: String) async throws -> SampleItem {
        try await client.execute(SampleItemQuery(variables: .init(id: id))).item
    }

    func save(id: String, count: Int) async throws {
        let result = try await client.execute(SampleSaveMutation(variables: .init(id: id, count: count)))
        if let error = result.sampleSave.userErrors.first { throw SampleSaveError(message: error.message) }
    }
}

struct SampleSaveError: Error { let message: String }

// MARK: - Operations (operation names must match Android and the fixtures)

struct SampleItemsQuery: GraphQLOperation {
    static let operationName = "SampleItems"
    static let document = "query SampleItems { items { id title count } }"
    struct ResponseData: Decodable, Sendable { let items: [SampleItem] }
}

struct SampleItemQuery: GraphQLOperation {
    static let operationName = "SampleItem"
    static let document = "query SampleItem($id: ID!) { item(id: $id) { id title count } }"
    struct Variables: Encodable, Sendable { let id: String }
    struct ResponseData: Decodable, Sendable { let item: SampleItem }
    let variables: Variables
}

struct SampleSaveMutation: GraphQLOperation {
    static let operationName = "SampleSave"
    static let document = "mutation SampleSave($id: ID!, $count: Int!) { sampleSave(id: $id, count: $count) { userErrors { message } } }"
    struct Variables: Encodable, Sendable { let id: String; let count: Int }
    struct ResponseData: Decodable, Sendable {
        struct Payload: Decodable, Sendable { let userErrors: [UserError] }
        struct UserError: Decodable, Sendable { let message: String }
        let sampleSave: Payload
    }
    let variables: Variables
}
