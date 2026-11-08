import Foundation
import IntrospectionKit

/// Fixtures live at `fixtures/<Domain>/<scenario>.json` and are referenced as `"<Domain>.<scenario>"`.
/// Several fixtures merge in order; later ones win. Format (identical on Android):
///
///     {
///       "extends": ["Sample.default"],                       // optional, merged first
///       "graphql": [{"operation": "SampleItem", "variables": {"id": "1"}, "response": {"data": {...}}},
///                   {"operation": "SampleSave", "error": {"kind": "http", "status": 500}}],
///       "storage": {"session.token": "fixture-token"},       // string values only
///       "device":  {"biometricsAvailable": false},
///       "flags":   {"sample.bulkEdit": false}
///     }
///
/// Merge rule: if a later fixture has any entry for operation X, it replaces *all* earlier entries for X.
/// `storage`, `device` and `flags` merge key by key.
public struct FixtureSet: Sendable {
    public var graphql: [GraphQLFixture] = []
    public var storage = JSONObject()
    public var device = JSONObject()
    public var flags = JSONObject()

    public init() {}

    public static func load(names: [String], root: URL) throws -> FixtureSet {
        var set = FixtureSet()
        for name in names { set.merge(try loadOne(name, root: root, stack: [])) }
        return set
    }

    public mutating func merge(_ other: FixtureSet) {
        let overridden = Set(other.graphql.map(\.operation))
        graphql = graphql.filter { !overridden.contains($0.operation) } + other.graphql
        for (key, value) in other.storage { storage[key] = value }
        for (key, value) in other.device { device[key] = value }
        for (key, value) in other.flags { flags[key] = value }
    }

    private static let allowedKeys: Set<String> = ["extends", "graphql", "storage", "device", "flags"]

    private static func loadOne(_ name: String, root: URL, stack: [String]) throws -> FixtureSet {
        guard !stack.contains(name) else {
            throw FixtureError.invalid(name, "circular extends: \((stack + [name]).joined(separator: " → "))")
        }
        let parts = name.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        let validPart = { (p: String) in !p.isEmpty && p.allSatisfy { $0.isLetter || $0.isNumber || $0 == "_" || $0 == "-" } }
        guard parts.count >= 2, parts.allSatisfy(validPart) else {
            throw FixtureError.invalid(name, "fixture names look like 'Domain.scenario'")
        }
        let url = parts.dropLast().reduce(root) { $0.appendingPathComponent($1) }
            .appendingPathComponent(parts[parts.count - 1] + ".json")
        guard let data = FileManager.default.contents(atPath: url.path) else { throw FixtureError.notFound(name, url.path) }

        let json: JSONValue
        do { json = try JSONParser.parse(data) } catch { throw FixtureError.invalid(name, "\(error)") }
        guard let object = json.objectValue else { throw FixtureError.invalid(name, "top level must be an object") }
        if let unknown = object.keys.first(where: { !allowedKeys.contains($0) }) {
            throw FixtureError.invalid(name, "unknown key '\(unknown)' (allowed: extends, graphql, storage, device, flags)")
        }

        var set = FixtureSet()
        for parent in object["extends"]?.arrayValue ?? [] {
            guard let parentName = parent.stringValue else { throw FixtureError.invalid(name, "extends must list fixture names") }
            set.merge(try loadOne(parentName, root: root, stack: stack + [name]))
        }

        var own = FixtureSet()
        for (i, entry) in (object["graphql"]?.arrayValue ?? []).enumerated() {
            own.graphql.append(try GraphQLFixture(entry, fixture: name, index: i))
        }
        if let storage = object["storage"]?.objectValue {
            for (key, value) in storage {
                guard value.stringValue != nil || value.isNull else {
                    throw FixtureError.invalid(name, "storage['\(key)'] must be a string or null")
                }
                own.storage[key] = value
            }
        }
        own.device = object["device"]?.objectValue ?? JSONObject()
        own.flags = object["flags"]?.objectValue ?? JSONObject()
        set.merge(own)
        return set
    }
}

public enum FixtureError: Error, CustomStringConvertible, Sendable {
    case notFound(String, String)
    case invalid(String, String)

    public var code: String {
        switch self {
        case .notFound: "FIXTURE_NOT_FOUND"
        case .invalid: "INVALID_FIXTURE"
        }
    }

    public var description: String {
        switch self {
        case .notFound(let name, let path): "Fixture '\(name)' not found (looked for \(path))"
        case .invalid(let name, let message): "Fixture '\(name)' is invalid: \(message)"
        }
    }
}

public struct GraphQLFixture: Sendable {
    public enum Outcome: Sendable, Equatable {
        /// The full response body, e.g. `{"data": {...}}` or `{"errors": [...]}`.
        case response(JSONValue)
        case httpError(status: Int)
        case networkError
    }

    public let operation: String
    /// Partial match: every listed variable must equal the request's. Nil matches any variables.
    public let variables: JSONObject?
    public let outcome: Outcome

    init(_ json: JSONValue, fixture: String, index: Int) throws {
        let at = "graphql[\(index)]"
        guard let object = json.objectValue, let operation = object["operation"]?.stringValue else {
            throw FixtureError.invalid(fixture, "\(at) needs an 'operation' string")
        }
        self.operation = operation
        if let variables = object["variables"] {
            guard let vars = variables.objectValue else { throw FixtureError.invalid(fixture, "\(at).variables must be an object") }
            self.variables = vars
        } else {
            self.variables = nil
        }
        switch (object["response"], object["error"]) {
        case (let response?, nil):
            outcome = .response(response)
        case (nil, let error?):
            switch error["kind"]?.stringValue {
            case "http": outcome = .httpError(status: Int(error["status"]?.numberValue ?? 500))
            case "network": outcome = .networkError
            default: throw FixtureError.invalid(fixture, "\(at).error.kind must be 'http' or 'network'")
            }
        default:
            throw FixtureError.invalid(fixture, "\(at) needs exactly one of 'response' or 'error'")
        }
    }
}

/// Per-session matcher. Entries for an operation are consumed in declaration order; the last one repeats.
public final class GraphQLFixtureMatcher: @unchecked Sendable {
    private let fixtures: [GraphQLFixture]
    private var consumed = Set<Int>()
    private let lock = NSLock()

    public init(_ fixtures: [GraphQLFixture]) { self.fixtures = fixtures }

    public func match(operation: String, variables: JSONObject) -> GraphQLFixture.Outcome? {
        lock.lock(); defer { lock.unlock() }
        let candidates = fixtures.indices.filter { i in
            fixtures[i].operation == operation
                && (fixtures[i].variables ?? JSONObject()).allSatisfy { variables[$0.key] == $0.value }
        }
        guard let pick = candidates.first(where: { !consumed.contains($0) }) ?? candidates.last else { return nil }
        if pick != candidates.last { consumed.insert(pick) }
        return fixtures[pick].outcome
    }
}
