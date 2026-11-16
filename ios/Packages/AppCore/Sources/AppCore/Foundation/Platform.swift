import Foundation

// Everything Core needs from the outside world is declared here as a protocol and passed to `Core.init`.
// The Mobile app passes real implementations (Keychain, URLSession, ...); the CLI passes fixtures.
// Add a new edge here (never import a platform framework into Core); see the reflex-add-edge skill.

/// Persistence (the Keychain in the app). Async because the real store does blocking I/O, which must not
/// run on the main actor; `update` calls it from a `Task` and gets the result back as a Message.
public protocol Storage: Sendable {
    func read(_ key: String) async -> String?
    func write(_ key: String, _ value: String?) async
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

// MARK: - Health Beta edges (docs/health/CONTRACT.md §2)

/// Time. The app reads the system clock; the CLI a fixed fixture time.
@MainActor
public protocol Clock: AnyObject {
    var now: Date { get }
    /// The device's current time zone (schedule times are floating local wall-clock times).
    var timeZone: TimeZone { get }
    /// Yields on minute ticks, significant time changes, time zone changes and app foreground. Never in fixtures.
    var changes: AsyncStream<Void> { get }
}

public enum NotificationPermission: String, Sendable {
    case notDetermined, denied, authorized
}

/// One local notification Core wants pending. `id` is stable (`dose:<slot>`, `followup:<slot>`, `snooze:<slot>`,
/// `refresh`), so the platform scheduler can reconcile by diffing ids.
public struct Reminder: Sendable, Equatable {
    public let id: String
    /// `dose`, `followUp`, `snooze` or `refresh`.
    public let kind: String
    public let fireAt: Date
    /// The resolved local wall time of `fireAt`, `yyyy-MM-ddTHH:mm` (for floating OS triggers).
    public let localFireTime: String
    public let title: String
    public let body: String
    /// The slot `"<date>T<time>"` its actions apply to; nil for `refresh`.
    public let slot: String?

    public init(id: String, kind: String, fireAt: Date, localFireTime: String, title: String, body: String, slot: String?) {
        self.id = id
        self.kind = kind
        self.fireAt = fireAt
        self.localFireTime = localFireTime
        self.title = title
        self.body = body
        self.slot = slot
    }
}

/// Local notifications. Core computes the complete desired set; the scheduler only reconciles it.
/// Core calls it one operation at a time, in order.
public protocol ReminderScheduler: Sendable {
    func permission() async -> NotificationPermission
    /// Asks the user (only meaningful when `notDetermined`); returns the resulting permission.
    func requestPermission() async -> NotificationPermission
    /// Android without exact alarms; always false on iOS.
    func mayBeLate() async -> Bool
    /// pending := reminders (diff by id).
    func replaceAll(_ reminders: [Reminder]) async
    /// What is pending now, in the order given to `replaceAll`.
    func pending() async -> [Reminder]
    func openSettings() async
}

/// Persistence of the Health data. App: SwiftData (`@ModelActor`, off the main actor).
/// CLI: in memory, seeded from the fixture's `storage["health.data"]`.
public protocol HealthRepository: Sendable {
    /// Nothing stored → empty data. Throws when the store can't be read.
    func load() async throws -> HealthData
    /// A full snapshot; upsert/delete by id in one transaction. Calls are serialized by Core.
    func save(_ data: HealthData) async throws
}
