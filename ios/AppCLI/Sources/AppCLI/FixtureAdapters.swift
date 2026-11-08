import AppCore
import Foundation
import IntrospectionKit
import ReflexHost

// Fixture-backed implementations of Core's platform protocols (Foundation/Platform.swift).
// When Core gains a new edge, add its fixture adapter here and in android/cli (same fixture keys).

/// The network edge in headless mode. Never touches the network, so it never trips the guard.
struct FixtureTransport: GraphQLTransport {
    let matcher: GraphQLFixtureMatcher
    let events: SessionEvents

    func execute(_ request: GraphQLRequest) async throws -> Data {
        let variables = (try? JSONParser.parse(request.variables))?.objectValue ?? JSONObject()
        switch matcher.match(operation: request.operationName, variables: variables) {
        case .response(let body)?:
            return body.data()
        case .httpError(let status)?:
            throw GraphQLTransportError.http(status: status)
        case .networkError?:
            throw GraphQLTransportError.network("Fixture network error")
        case nil:
            events.fixtureMiss(operation: request.operationName, variables: variables)
            throw GraphQLTransportError.network("No fixture for \(request.operationName)")
        }
    }
}

/// Fixture key `device`. Missing keys default to false.
@MainActor
final class FixtureDevice: DeviceCapabilities {
    private let values: JSONObject
    init(_ values: JSONObject) { self.values = values }
    var biometricsAvailable: Bool { values["biometricsAvailable"]?.boolValue ?? false }
}

/// Fixture key `flags`. Missing flags are disabled.
@MainActor
final class FixtureFlags: FeatureFlags {
    private let values: JSONObject
    init(_ values: JSONObject) { self.values = values }
    func isEnabled(_ flag: String) -> Bool { values[flag]?.boolValue ?? false }
}
