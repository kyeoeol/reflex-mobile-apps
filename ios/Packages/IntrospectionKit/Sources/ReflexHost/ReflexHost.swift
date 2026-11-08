import Foundation
import IntrospectionKit
import os

/// Speaks the Reflex NDJSON protocol (version 1). Transport-agnostic: stdio for the headless CLI,
/// a socket for DEBUG builds. Android's `ReflexHost` implements the same protocol byte for byte.
///
/// Requests:  {"id":1,"cmd":"session.create","fixtures":["Sample.default"],"deeplink":null}
///            {"id":2,"session":"s1","cmd":"ls" | "tree" | "session.close"}
///            {"id":3,"session":"s1","cmd":"do","action":"SetCount","payload":{"count":40}}
/// Responses: {"id":1,"ok":true,"session":"s1"} · {"id":2,"ok":true,"node":{...}} · {"id":3,"ok":true}
///            {"id":3,"ok":false,"error":{"code":"ACTION_NOT_AVAILABLE","message":"...","available":[...]}}
/// Events:    {"event":"ready","platform":"ios","protocol":1} · network_violation · fixture_miss · log
@MainActor
public final class ReflexHost {
    public typealias SessionFactory = @MainActor (SessionContext) throws -> any IntrospectionProviding

    public enum Mode {
        /// Headless: every `session.create` builds a fresh Core from fixtures.
        case fixtures(root: URL, factory: SessionFactory)
        /// A running app: exactly one session (`"live"`), the app itself. `session.create` is rejected.
        case live(root: any IntrospectionProviding)
    }

    public static let protocolVersion = 1
    public static let liveSessionID = "live"

    public let platform: String
    public let events: EventSink
    private let mode: Mode
    private var sessions: [String: any IntrospectionProviding] = [:]
    private var nextSession = 1

    /// Pass `events` when the root was built before the host (live mode) and already emits into a sink.
    public init(platform: String, mode: Mode, events: EventSink = EventSink()) {
        self.platform = platform
        self.mode = mode
        self.events = events
        if case .live(let root) = mode { sessions[ReflexHost.liveSessionID] = root }
    }

    public var readyEvent: String {
        JSONValue.object(["event": "ready", "platform": .string(platform), "protocol": .number(ReflexHost.protocolVersion)]).serialized()
    }

    public func handle(_ line: String, reply: (String) -> Void) {
        guard !line.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        let request: JSONObject
        do {
            guard let object = try JSONParser.parse(line).objectValue else { throw HostError("BAD_REQUEST", "request must be a JSON object") }
            request = object
        } catch let error as HostError {
            reply(Self.failure(id: .null, error)); return
        } catch {
            reply(Self.failure(id: .null, HostError("BAD_REQUEST", "\(error)"))); return
        }
        let id = request["id"] ?? .null
        do {
            reply(try respond(to: request, id: id).serialized())
        } catch let error as HostError {
            reply(Self.failure(id: id, error))
        } catch let error as FixtureError {
            reply(Self.failure(id: id, HostError(error.code, error.description)))
        } catch {
            reply(Self.failure(id: id, HostError("INTERNAL", "\(error)")))
        }
    }

    private func respond(to request: JSONObject, id: JSONValue) throws -> JSONValue {
        let command = request["cmd"]?.stringValue ?? ""
        if command == "session.create" { return try createSession(request, id: id) }

        let sessionID = try sessionID(of: request)
        guard let root = sessions[sessionID] else { throw HostError("UNKNOWN_SESSION", "No session '\(sessionID)'") }
        events.activeSession = sessionID
        let resolution = Resolution.resolve(root.introspectionNode)

        switch command {
        case "ls":
            return ["id": id, "ok": true, "node": resolution.snapshot()]
        case "tree":
            return ["id": id, "ok": true, "tree": resolution.tree()]
        case "do":
            guard let title = request["action"]?.stringValue else { throw HostError("BAD_REQUEST", "'do' needs an 'action'") }
            guard let available = resolution.action(titled: title) else {
                throw HostError("ACTION_NOT_AVAILABLE", "Action '\(title)' is not available on \(resolution.focused.name)",
                                extra: ["available": .array(resolution.available.map { .string($0.action.title) })])
            }
            do {
                try available.action.perform(request["payload"])
            } catch let error as InvalidPayload {
                var extra: JSONObject = [:]
                if let schema = available.action.inputSchema { extra["schema"] = schema }
                throw HostError("INVALID_PAYLOAD", "Invalid payload for \(title): \(error.description)", extra: extra)
            }
            return ["id": id, "ok": true]
        case "session.close":
            if case .live = mode { throw HostError("UNSUPPORTED", "The live session can't be closed") }
            sessions[sessionID] = nil
            return ["id": id, "ok": true]
        default:
            throw HostError("BAD_REQUEST", "Unknown command '\(command)'")
        }
    }

    private func sessionID(of request: JSONObject) throws -> String {
        if case .live = mode { return ReflexHost.liveSessionID }
        guard let id = request["session"]?.stringValue else { throw HostError("BAD_REQUEST", "Missing 'session'") }
        return id
    }

    private func createSession(_ request: JSONObject, id: JSONValue) throws -> JSONValue {
        guard case .fixtures(let root, let factory) = mode else {
            throw HostError("UNSUPPORTED", "A live app has a single session ('live'); session.create is not supported")
        }
        let names = (request["fixtures"]?.arrayValue ?? []).compactMap(\.stringValue)
        let fixtures = try FixtureSet.load(names: names, root: root)
        let sessionID = "s\(nextSession)"
        nextSession += 1
        events.activeSession = sessionID
        let context = SessionContext(id: sessionID, fixtures: fixtures, deeplink: request["deeplink"]?.stringValue,
                                     events: SessionEvents(sessionID: sessionID, sink: events))
        sessions[sessionID] = try factory(context)
        return ["id": id, "ok": true, "session": .string(sessionID)]
    }

    private static func failure(id: JSONValue, _ error: HostError) -> String {
        var body: JSONObject = ["code": .string(error.code), "message": .string(error.message)]
        for (key, value) in error.extra { body[key] = value }
        return JSONValue.object(["id": id, "ok": false, "error": .object(body)]).serialized()
    }
}

/// A protocol-level error with a stable code. Session factories may throw it (e.g. `BAD_REQUEST` for an unknown deep link).
public struct HostError: Error {
    public let code: String
    public let message: String
    public let extra: JSONObject
    public init(_ code: String, _ message: String, extra: JSONObject = [:]) {
        self.code = code; self.message = message; self.extra = extra
    }
}

/// What a session factory receives: the merged fixtures, an optional deep link, and an event channel.
public struct SessionContext: Sendable {
    public let id: String
    public let fixtures: FixtureSet
    public let deeplink: String?
    public let events: SessionEvents

    public init(id: String, fixtures: FixtureSet, deeplink: String?, events: SessionEvents) {
        self.id = id; self.fixtures = fixtures; self.deeplink = deeplink; self.events = events
    }
}

public struct SessionEvents: Sendable {
    public let sessionID: String
    let sink: EventSink

    public init(sessionID: String, sink: EventSink) { self.sessionID = sessionID; self.sink = sink }

    /// A request reached the fixture edge but no fixture matched. Fails the behavior test.
    public func fixtureMiss(operation: String, variables: JSONObject) {
        sink.emit(["event": "fixture_miss", "session": .string(sessionID), "operation": .string(operation), "variables": .object(variables)])
    }

    public func log(level: String, message: String) {
        sink.emit(["event": "log", "session": .string(sessionID), "level": .string(level), "message": .string(message)])
    }
}

/// Thread-safe event fan-out to every connected client.
public final class EventSink: Sendable {
    private struct Shared {
        var writers: [UUID: @Sendable (String) -> Void] = [:]
        var activeSession: String?
    }

    private let shared = OSAllocatedUnfairLock(initialState: Shared())

    public init() {}

    /// The session that most recently received a command; network violations are attributed to it.
    public var activeSession: String? {
        get { shared.withLock { $0.activeSession } }
        set { shared.withLock { $0.activeSession = newValue } }
    }

    @discardableResult
    public func addWriter(_ writer: @escaping @Sendable (String) -> Void) -> UUID {
        let token = UUID()
        shared.withLock { $0.writers[token] = writer }
        return token
    }

    public func removeWriter(_ token: UUID) {
        shared.withLock { $0.writers[token] = nil }
    }

    public func emit(_ event: JSONObject) {
        let line = JSONValue.object(event).serialized()
        // Writers run outside the lock, so a writer may itself add or remove writers.
        let targets = shared.withLock { Array($0.writers.values) }
        for write in targets { write(line) }
    }

    public func networkViolation(method: String, url: String) {
        emit(["event": "network_violation", "session": JSONValue(activeSession),
              "request": ["method": .string(method), "url": .string(url)]])
    }
}
