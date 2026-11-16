import Foundation
import Testing
@testable import IntrospectionKit
@testable import ReflexHost

// Unit tests cover primitives only (JSON, schemas, resolution, fixtures).
// App behavior is covered by the shared Bun behavior tests in tests/behavior.

@Suite struct JSONTests {
    @Test func serializesDeterministically() {
        let value: JSONValue = ["b": 13, "a": .number(1.5), "s": "q\"\n", "n": nil, "arr": [true, false]]
        #expect(value.serialized() == #"{"b":13,"a":1.5,"s":"q\"\n","n":null,"arr":[true,false]}"#)
        #expect(JSONValue.number(13.0).serialized() == "13")
        #expect(JSONValue.number(-0.0).serialized() == "0")
    }

    @Test func parsesPreservingKeyOrder() throws {
        let value = try JSONParser.parse(#"{"z": 1, "a": {"y": [1, 2.5, "xé"], "b": null}}"#)
        #expect(value.objectValue?.keys == ["z", "a"])
        #expect(value["a"]?["y"] == [1, .number(2.5), "xé"])
        #expect(value.serialized() == #"{"z":1,"a":{"y":[1,2.5,"xé"],"b":null}}"#)
    }

    @Test func rejectsInvalidJSON() {
        #expect(throws: JSONParseError.self) { try JSONParser.parse("{\"a\": }") }
        #expect(throws: JSONParseError.self) { try JSONParser.parse("[1,]") }
        #expect(throws: JSONParseError.self) { try JSONParser.parse("1 2") }
    }
}

@Suite struct SchemaTests {
    enum Sort: String, Codable, CaseIterable, SchemaDescribable { case newest, oldest }
    struct Nested: Decodable { let name: String }
    struct Input: IntrospectionInput {
        let count: Int
        let note: String?
        let tags: [String]
        let nested: Nested
        let sort: Sort
    }

    @Test func infersSchemaFromDecodable() {
        #expect(Input.jsonSchema.serialized() == #"{"type":"object","properties":{"count":{"type":"integer"},"note":{"type":["string","null"]},"tags":{"type":"array","items":{"type":"string"}},"nested":{"type":"object","properties":{"name":{"type":"string"}},"required":["name"],"additionalProperties":false},"sort":{"type":"string","enum":["newest","oldest"]}},"required":["count","tags","nested","sort"],"additionalProperties":false}"#)
    }

    @Test func validatesPayloads() throws {
        let schema = Input.jsonSchema
        let ok = try JSONParser.parse(#"{"count": 2, "tags": [], "nested": {"name": "x"}, "sort": "newest"}"#)
        #expect(SchemaValidator.validate(ok, against: schema) == nil)
        func with(_ key: String, _ value: JSONValue) -> JSONValue { var o = ok.objectValue!; o[key] = value; return .object(o) }
        #expect(SchemaValidator.validate(with("count", "x"), against: schema) == "/count must be integer")
        #expect(SchemaValidator.validate(with("count", 2.5), against: schema) == "/count must be integer")
        #expect(SchemaValidator.validate(with("nested", ["name": 1]), against: schema) == "/nested/name must be string")
        #expect(SchemaValidator.validate([:], against: schema) == "/count is required")
        #expect(SchemaValidator.validate("x", against: schema) == "/ must be object")
        var extra = ok.objectValue!
        extra["bogus"] = 1
        #expect(SchemaValidator.validate(.object(extra), against: schema) == "/bogus is not allowed")
        var badEnum = ok.objectValue!
        badEnum["sort"] = "sideways"
        #expect(SchemaValidator.validate(.object(badEnum), against: schema) == #"/sort must be one of "newest", "oldest""#)
    }
}

@MainActor @Suite struct ResolutionTests {
    struct Leaf: IntrospectionNode {
        let name = "Leaf"
        var state: JSONObject { ["x": 1] }
        var actions: [IntrospectionAction] { [IntrospectionAction(title: "Tap") {}, IntrospectionAction(title: "goBack") {}] }
        var child: (any IntrospectionNode)? { nil }
    }
    struct Container: IntrospectionNode {
        let name = "Stack"
        var state: JSONObject { [:] }
        var actions: [IntrospectionAction] {
            [IntrospectionAction(title: "goBack", scope: .lent) {}, IntrospectionAction(title: "Hidden") {}]
        }
        var child: (any IntrospectionNode)? { Leaf() }
    }

    @Test func focusesDeepestNodeAndAddsLentActions() {
        let resolution = Resolution.resolve(Container())
        #expect(resolution.path == ["Stack", "Leaf"])
        #expect(resolution.available.map(\.action.title) == ["Tap", "goBack"])
        #expect(resolution.available.map(\.lentBy) == [nil, nil])     // child wins on collision
        #expect(resolution.action(titled: "Hidden") == nil)           // non-lent ancestor actions are off-limits
    }
}

@Suite struct FixtureMatcherTests {
    @Test func consumesInOrderAndRepeatsTheLast() throws {
        let entries = try [
            #"{"operation": "Op", "response": {"data": 1}}"#,
            #"{"operation": "Op", "response": {"data": 2}}"#,
            #"{"operation": "Other", "variables": {"id": "1"}, "error": {"kind": "network"}}"#,
        ].enumerated().map { try GraphQLFixture(JSONParser.parse($1), fixture: "T.t", index: $0) }
        let matcher = GraphQLFixtureMatcher(entries)
        #expect(matcher.match(operation: "Op", variables: [:]) == .response(["data": 1]))
        #expect(matcher.match(operation: "Op", variables: [:]) == .response(["data": 2]))
        #expect(matcher.match(operation: "Op", variables: [:]) == .response(["data": 2]))
        #expect(matcher.match(operation: "Other", variables: ["id": "1", "extra": true]) == .networkError)
        #expect(matcher.match(operation: "Other", variables: ["id": "2"]) == nil)
    }
}

@MainActor @Suite struct HostDeviceCommandTests {
    final class Root: IntrospectionProviding {
        var introspectionNode: any IntrospectionNode { ResolutionTests.Leaf() }
    }

    /// Records what reaches it; `event` suspends a few times first, so later requests would overtake it if the
    /// host didn't keep them in order.
    final class Device: DeviceControl {
        var log: [String] = []

        func set(_ values: JSONObject) async throws {
            guard values["now"]?.stringValue != nil else { throw HostError("BAD_REQUEST", "bad now") }
            log.append("set \(JSONValue.object(values).serialized())")
        }

        func event(type: String, payload: JSONObject) async throws {
            for _ in 0..<5 { await Task.yield() }
            guard type == "ping" else { throw HostError("BAD_REQUEST", "unknown event") }
            log.append("event \(type) \(JSONValue.object(payload).serialized())")
        }
    }

    /// Sends every line, then collects one reply per non-blank line, in the order they were written.
    func run(_ host: ReflexHost, _ lines: [String]) async -> [String] {
        let (replies, continuation) = AsyncStream<String>.makeStream()
        for line in lines { host.handle(line) { continuation.yield($0) } }
        await host.drain()
        continuation.finish()
        var collected: [String] = []
        for await reply in replies { collected.append(reply) }
        return collected
    }

    @Test func routesDeviceCommandsToTheSessionInOrder() async {
        let device = Device()
        let host = ReflexHost(platform: "test", mode: .fixtures(root: URL(fileURLWithPath: "/nonexistent")) { _ in
            HeadlessSession(root: Root(), device: device)
        })
        let replies = await run(host, [
            #"{"id":1,"cmd":"session.create","fixtures":[]}"#,
            #"{"id":2,"session":"s1","cmd":"device.event","type":"ping","payload":{"n":1}}"#,
            #"{"id":3,"session":"s1","cmd":"device.set","values":{"now":"2026-11-16T10:00:00"}}"#,
            #"{"id":4,"session":"s1","cmd":"device.set","values":{"now":1}}"#,
            #"{"id":5,"session":"s1","cmd":"device.set"}"#,
            #"{"id":6,"session":"s1","cmd":"device.event","type":"pong"}"#,
            #"{"id":7,"session":"s1","cmd":"device.event","payload":{}}"#,
            #"{"id":8,"session":"s9","cmd":"device.set","values":{}}"#,
        ])
        #expect(replies == [
            #"{"id":1,"ok":true,"session":"s1"}"#,
            #"{"id":2,"ok":true}"#,
            #"{"id":3,"ok":true}"#,
            #"{"id":4,"ok":false,"error":{"code":"BAD_REQUEST","message":"bad now"}}"#,
            #"{"id":5,"ok":false,"error":{"code":"BAD_REQUEST","message":"'device.set' needs a 'values' object"}}"#,
            #"{"id":6,"ok":false,"error":{"code":"BAD_REQUEST","message":"unknown event"}}"#,
            #"{"id":7,"ok":false,"error":{"code":"BAD_REQUEST","message":"'device.event' needs a 'type'"}}"#,
            #"{"id":8,"ok":false,"error":{"code":"UNKNOWN_SESSION","message":"No session 's9'"}}"#,
        ])
        #expect(device.log == [#"event ping {"n":1}"#, #"set {"now":"2026-11-16T10:00:00"}"#])
    }

    @Test func liveSessionsDontSupportDeviceCommands() async {
        let host = ReflexHost(platform: "test", mode: .live(root: Root()))
        let replies = await run(host, [
            #"{"id":1,"cmd":"device.set","values":{"now":"2026-11-16T10:00:00"}}"#,
            #"{"id":2,"cmd":"device.event","type":"openURL","payload":{"url":"x://today"}}"#,
            #"{"id":3,"cmd":"ls"}"#,
        ])
        #expect(replies.prefix(2) == [
            #"{"id":1,"ok":false,"error":{"code":"UNSUPPORTED","message":"'device.set' is only supported in headless sessions"}}"#,
            #"{"id":2,"ok":false,"error":{"code":"UNSUPPORTED","message":"'device.event' is only supported in headless sessions"}}"#,
        ])
        #expect(replies[2].hasPrefix(#"{"id":3,"ok":true,"node":"#))
    }
}
