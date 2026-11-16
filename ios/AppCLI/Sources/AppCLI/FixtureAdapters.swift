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

/// The Health keys of a `device` section, parsed strictly. `errorCode` is what malformed values throw:
/// `INVALID_FIXTURE` at `session.create`, `BAD_REQUEST` for `device.set`.
struct HealthDeviceValues {
    static let defaultNow = "2026-01-01T00:00:00"
    static let defaultTimeZone = "UTC"

    let now: Date
    let timeZone: TimeZone
    let notificationPermission: NotificationPermission
    let notificationRequestResult: NotificationPermission
    let remindersMayBeLate: Bool
    let saveFails: Bool

    init(_ device: JSONObject, errorCode: String) throws {
        let zoneId = try device.string("timeZone", default: Self.defaultTimeZone, errorCode)
        guard let zone = TimeZone(identifier: zoneId) else {
            throw HostError(errorCode, "device.timeZone '\(zoneId)' is not an IANA time zone")
        }
        let local = try device.string("now", default: Self.defaultNow, errorCode)
        guard let now = HealthTime.resolve(localDateTimeWithSeconds: local, in: zone) else {
            throw HostError(errorCode, "device.now '\(local)' is not yyyy-MM-ddTHH:mm:ss")
        }
        self.now = now
        self.timeZone = zone
        notificationPermission = try device.permission("notificationPermission", default: .notDetermined, errorCode)
        notificationRequestResult = try device.permission("notificationRequestResult", default: .denied, errorCode)
        remindersMayBeLate = try device.bool("remindersMayBeLate", default: false, errorCode)
        saveFails = try device.bool("saveFails", default: false, errorCode)
    }
}

/// `device.now` (`yyyy-MM-ddTHH:mm:ss`, local to `device.timeZone`, resolved with Core's DST rule) and
/// `device.timeZone` (IANA id). Defaults: `2026-01-01T00:00:00`, `UTC`. Time moves only with `device.set`, after
/// which `changes` yields once.
@MainActor
final class FixtureClock: Clock {
    private(set) var now: Date
    private(set) var timeZone: TimeZone
    let changes: AsyncStream<Void>
    private let continuation: AsyncStream<Void>.Continuation

    init(_ values: HealthDeviceValues) {
        now = values.now
        timeZone = values.timeZone
        (changes, continuation) = AsyncStream<Void>.makeStream()
    }

    func move(to now: Date, in timeZone: TimeZone) {
        self.now = now
        self.timeZone = timeZone
    }

    /// The world changed (`device.set`).
    func changed() { continuation.yield() }
}

/// `device.notificationPermission` (default `notDetermined`), `device.notificationRequestResult` (what the user
/// answers when asked, default `denied`), `device.remindersMayBeLate` (default false). Pending reminders are kept in
/// memory in the order given.
actor FixtureReminderScheduler: ReminderScheduler {
    private var current: NotificationPermission
    private var requestResult: NotificationPermission
    private var late: Bool
    private var reminders: [Reminder] = []

    init(_ values: HealthDeviceValues) {
        current = values.notificationPermission
        requestResult = values.notificationRequestResult
        late = values.remindersMayBeLate
    }

    func permission() -> NotificationPermission { current }

    func requestPermission() -> NotificationPermission {
        if current == .notDetermined { current = requestResult }
        return current
    }

    func mayBeLate() -> Bool { late }
    func replaceAll(_ reminders: [Reminder], openSlots: Set<String>) { self.reminders = reminders }
    func pending() -> [Reminder] { reminders }
    func openSettings() {}

    /// `device.set`: the user changed a system setting. Nil leaves a value as it is.
    func set(permission: NotificationPermission?, requestResult: NotificationPermission?, mayBeLate: Bool?) {
        if let permission { current = permission }
        if let requestResult { self.requestResult = requestResult }
        if let mayBeLate { late = mayBeLate }
    }
}

/// `storage["health.data"]`: the Health data as JSON. Missing, null or empty → empty data; unparseable or invalid
/// → `load()` throws (the app then shows "Couldn't load your data" and never saves). `device.saveFails`: every
/// `save` throws (default false).
actor FixtureHealthRepository: HealthRepository {
    struct SaveFailure: Error {}

    private let stored: String?
    private var data: HealthData?
    private var saveFails: Bool

    init(_ storage: JSONObject, _ values: HealthDeviceValues) {
        stored = storage["health.data"]?.stringValue
        saveFails = values.saveFails
    }

    func load() throws -> HealthData {
        if let data { return data }
        guard let stored else { return HealthData() }
        return try HealthData(json: stored)
    }

    func save(_ data: HealthData) throws {
        guard !saveFails else { throw SaveFailure() }
        self.data = data
    }

    func set(saveFails: Bool) { self.saveFails = saveFails }
}

/// One headless session's world: `device.set` merges values into its `device` values and moves the fixture edges,
/// `device.event` delivers notification responses and URL opens to the app.
@MainActor
final class FixtureDeviceControl: DeviceControl {
    private var values: JSONObject
    private let app: AppRoot
    private let clock: FixtureClock
    private let scheduler: FixtureReminderScheduler
    private let repository: FixtureHealthRepository

    init(values: JSONObject, app: AppRoot, clock: FixtureClock, scheduler: FixtureReminderScheduler,
         repository: FixtureHealthRepository) {
        self.values = values
        self.app = app
        self.clock = clock
        self.scheduler = scheduler
        self.repository = repository
    }

    func set(_ changes: JSONObject) async throws {
        var merged = values
        for (key, value) in changes { merged[key] = value }
        let parsed = try HealthDeviceValues(merged, errorCode: "BAD_REQUEST")   // all or nothing
        values = merged
        if changes["now"] != nil || changes["timeZone"] != nil { clock.move(to: parsed.now, in: parsed.timeZone) }
        await scheduler.set(permission: changes["notificationPermission"].map { _ in parsed.notificationPermission },
                            requestResult: changes["notificationRequestResult"].map { _ in parsed.notificationRequestResult },
                            mayBeLate: changes["remindersMayBeLate"].map { _ in parsed.remindersMayBeLate })
        if changes["saveFails"] != nil { await repository.set(saveFails: parsed.saveFails) }
        clock.changed()
    }

    func event(type: String, payload: JSONObject) async throws {
        switch type {
        case "notificationResponse":
            guard let slot = payload["slot"]?.stringValue else {
                throw HostError("BAD_REQUEST", "notificationResponse needs a 'slot' string")
            }
            guard let action = payload["action"]?.stringValue.flatMap(ReminderResponse.init(rawValue:)) else {
                throw HostError("BAD_REQUEST", "notificationResponse needs an 'action': open, taken, skipped or snooze")
            }
            await app.handleReminderResponse(slot: slot, action: action)
        case "openURL":
            guard let text = payload["url"]?.stringValue else { throw HostError("BAD_REQUEST", "openURL needs a 'url' string") }
            guard let url = URL(string: text), app.open(url) else {
                throw HostError("BAD_REQUEST", "URL '\(text)' doesn't route anywhere")
            }
        default:
            throw HostError("BAD_REQUEST", "Unknown device event '\(type)' (known: notificationResponse, openURL)")
        }
    }
}

private extension JSONObject {
    func string(_ key: String, default fallback: String, _ code: String) throws -> String {
        guard let value = self[key], !value.isNull else { return fallback }
        guard let string = value.stringValue else { throw HostError(code, "device.\(key) must be a string") }
        return string
    }

    func bool(_ key: String, default fallback: Bool, _ code: String) throws -> Bool {
        guard let value = self[key], !value.isNull else { return fallback }
        guard let bool = value.boolValue else { throw HostError(code, "device.\(key) must be a boolean") }
        return bool
    }

    func permission(_ key: String, default fallback: NotificationPermission, _ code: String) throws -> NotificationPermission {
        let raw = try string(key, default: fallback.rawValue, code)
        guard let permission = NotificationPermission(rawValue: raw) else {
            throw HostError(code, "device.\(key) must be notDetermined, denied or authorized")
        }
        return permission
    }
}
