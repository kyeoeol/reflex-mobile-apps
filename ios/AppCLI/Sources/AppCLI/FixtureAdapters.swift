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

// MARK: - Health Beta (fixture keys in `device`, defaults identical on Android; docs/health/CONTRACT.md §2)

/// `device.now` (`yyyy-MM-ddTHH:mm:ss`, local to `device.timeZone`, resolved with Core's DST rule) and
/// `device.timeZone` (IANA id). Defaults: `2026-01-01T00:00:00`, `UTC`. Time never moves, so `changes` never yields.
@MainActor
final class FixtureClock: Clock {
    let now: Date
    let timeZone: TimeZone
    let changes: AsyncStream<Void>
    private let continuation: AsyncStream<Void>.Continuation

    init(_ device: JSONObject) throws {
        let zoneId = try device.string("timeZone", default: "UTC")
        guard let zone = TimeZone(identifier: zoneId) else {
            throw HostError("INVALID_FIXTURE", "device.timeZone '\(zoneId)' is not an IANA time zone")
        }
        let local = try device.string("now", default: "2026-01-01T00:00:00")
        guard let now = HealthTime.resolve(localDateTimeWithSeconds: local, in: zone) else {
            throw HostError("INVALID_FIXTURE", "device.now '\(local)' is not yyyy-MM-ddTHH:mm:ss")
        }
        self.now = now
        self.timeZone = zone
        (changes, continuation) = AsyncStream<Void>.makeStream()
    }
}

/// `device.notificationPermission` (default `notDetermined`), `device.notificationRequestResult` (what the user
/// answers when asked, default `denied`), `device.remindersMayBeLate` (default false). Pending reminders are kept in
/// memory in the order given.
actor FixtureReminderScheduler: ReminderScheduler {
    private var current: NotificationPermission
    private let requestResult: NotificationPermission
    private let late: Bool
    private var reminders: [Reminder] = []

    init(_ device: JSONObject) throws {
        current = try device.permission("notificationPermission", default: .notDetermined)
        requestResult = try device.permission("notificationRequestResult", default: .denied)
        late = try device.bool("remindersMayBeLate", default: false)
    }

    func permission() -> NotificationPermission { current }

    func requestPermission() -> NotificationPermission {
        if current == .notDetermined { current = requestResult }
        return current
    }

    func mayBeLate() -> Bool { late }
    func replaceAll(_ reminders: [Reminder]) { self.reminders = reminders }
    func pending() -> [Reminder] { reminders }
    func openSettings() {}
}

/// `storage["health.data"]`: the Health data as JSON. Missing → empty data; unparseable → `load()` throws
/// (the app then shows "Couldn't load your data" and never saves).
actor FixtureHealthRepository: HealthRepository {
    private var stored: String?
    private var data: HealthData?

    init(_ storage: JSONObject) { stored = storage["health.data"]?.stringValue }

    func load() throws -> HealthData {
        if let data { return data }
        guard let stored else { return HealthData() }
        return try HealthData(json: stored)
    }

    func save(_ data: HealthData) { self.data = data }
}

private extension JSONObject {
    func string(_ key: String, default fallback: String) throws -> String {
        guard let value = self[key], !value.isNull else { return fallback }
        guard let string = value.stringValue else { throw HostError("INVALID_FIXTURE", "device.\(key) must be a string") }
        return string
    }

    func bool(_ key: String, default fallback: Bool) throws -> Bool {
        guard let value = self[key], !value.isNull else { return fallback }
        guard let bool = value.boolValue else { throw HostError("INVALID_FIXTURE", "device.\(key) must be a boolean") }
        return bool
    }

    func permission(_ key: String, default fallback: NotificationPermission) throws -> NotificationPermission {
        let raw = try string(key, default: fallback.rawValue)
        guard let permission = NotificationPermission(rawValue: raw) else {
            throw HostError("INVALID_FIXTURE", "device.\(key) must be notDetermined, denied or authorized")
        }
        return permission
    }
}
