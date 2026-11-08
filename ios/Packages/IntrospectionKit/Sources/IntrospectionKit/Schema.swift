import Foundation
import os

// MARK: - Inference

/// Derives the JSON Schema of an `IntrospectionInput` from its `Decodable` conformance, so nobody
/// maintains schemas by hand (Android derives the same schema from the kotlinx `SerialDescriptor`).
///
/// Supported field types: String, Bool, Int (all widths), Double/Float, nested Decodable structs,
/// arrays of supported types, optionals (`T?` → not required, `["T","null"]`), and `SchemaDescribable`
/// types (e.g. `enum Sort: String, Codable, CaseIterable, SchemaDescribable`).
/// Do not give input fields default values: keep them required (`T`) or optional (`T?`).
public enum SchemaInference {
    private static let cache = OSAllocatedUnfairLock(initialState: [ObjectIdentifier: JSONValue]())

    public static func schema<T: Decodable>(for type: T.Type) -> JSONValue {
        let key = ObjectIdentifier(type)
        if let cached = cache.withLock({ $0[key] }) { return cached }
        // Inferred outside the lock: inference is deterministic (a race only computes the same schema twice),
        // and a `SchemaDescribable.jsonSchema` that calls back into here must not re-enter a held lock.
        let slot = Slot()
        do {
            _ = try Recorder.value(T.self, into: slot)
        } catch {
            fatalError("Cannot infer a JSON schema for \(T.self): \(error). Use supported field types or conform to SchemaDescribable.")
        }
        let schema = slot.build()
        cache.withLock { $0[key] = schema }
        return schema
    }
}

/// Escape hatch for field types the recorder can't sample (enums, custom value types).
public protocol SchemaDescribable: Decodable {
    static var jsonSchema: JSONValue { get }
    static var schemaSample: Self { get }
}

public extension SchemaDescribable where Self: CaseIterable & RawRepresentable, RawValue == String {
    static var jsonSchema: JSONValue { ["type": "string", "enum": .array(allCases.map { .string($0.rawValue) })] }
    static var schemaSample: Self { allCases.first! }
}

private final class Slot {
    enum Kind { case unknown, fixed(JSONValue), object(ObjectShape), array(Slot) }
    var kind: Kind = .unknown

    func build() -> JSONValue {
        switch kind {
        case .unknown: return [:]
        case .fixed(let schema): return schema
        case .array(let items): return ["type": "array", "items": items.build()]
        case .object(let shape):
            var properties = JSONObject()
            for (name, slot, optional) in shape.properties {
                let built = slot.build()
                properties[name] = optional ? Slot.nullable(built) : built
            }
            let required = shape.properties.filter { !$0.optional }.map { JSONValue.string($0.name) }
            return ["type": "object", "properties": .object(properties), "required": .array(required), "additionalProperties": false]
        }
    }

    static func nullable(_ schema: JSONValue) -> JSONValue {
        guard var object = schema.objectValue, let type = object["type"] else { return schema }
        switch type {
        case .string(let t): object["type"] = [.string(t), "null"]
        case .array(let ts): object["type"] = .array(ts + ["null"])
        default: break
        }
        return .object(object)
    }
}

private final class ObjectShape {
    var properties: [(name: String, slot: Slot, optional: Bool)] = []
    func add(_ name: String, optional: Bool) -> Slot {
        if let existing = properties.first(where: { $0.name == name }) { return existing.slot }
        let slot = Slot()
        properties.append((name, slot, optional))
        return slot
    }
}

private struct RecorderError: Error, CustomStringConvertible { let description: String }

private enum Recorder {
    static let integerTypes: [Any.Type] = [Int.self, Int8.self, Int16.self, Int32.self, Int64.self, UInt.self, UInt8.self, UInt16.self, UInt32.self, UInt64.self]

    static func primitive<T>(_ requested: T.Type) -> (schema: JSONValue, sample: T)? {
        if T.self == String.self { return (["type": "string"], "" as! T) }
        if T.self == Bool.self { return (["type": "boolean"], false as! T) }
        if T.self == Double.self { return (["type": "number"], 0.0 as! T) }
        if T.self == Float.self { return (["type": "number"], Float(0) as! T) }
        if integerTypes.contains(where: { $0 == T.self }) {
            let samples: [Any] = [Int(0), Int8(0), Int16(0), Int32(0), Int64(0), UInt(0), UInt8(0), UInt16(0), UInt32(0), UInt64(0)]
            return (["type": "integer"], samples.first(where: { Swift.type(of: $0) == T.self }) as! T)
        }
        return nil
    }

    static func value<T: Decodable>(_ type: T.Type, into slot: Slot) throws -> T {
        if let p = primitive(T.self) { slot.kind = .fixed(p.schema); return p.sample }
        if let describable = T.self as? any SchemaDescribable.Type {
            slot.kind = .fixed(describable.jsonSchema)
            return describable.schemaSample as! T
        }
        return try T(from: RecordingDecoder(slot: slot))
    }

    struct RecordingDecoder: Decoder {
        let slot: Slot
        var codingPath: [CodingKey] = []
        var userInfo: [CodingUserInfoKey: Any] = [:]

        func container<Key: CodingKey>(keyedBy type: Key.Type) throws -> KeyedDecodingContainer<Key> {
            let shape: ObjectShape
            if case .object(let existing) = slot.kind { shape = existing } else {
                shape = ObjectShape()
                slot.kind = .object(shape)
            }
            return KeyedDecodingContainer(Keyed<Key>(shape: shape))
        }

        func unkeyedContainer() throws -> UnkeyedDecodingContainer {
            let items = Slot()
            slot.kind = .array(items)
            return Unkeyed(items: items)
        }

        func singleValueContainer() throws -> SingleValueDecodingContainer { Single(slot: slot) }
    }

    struct Keyed<Key: CodingKey>: KeyedDecodingContainerProtocol {
        let shape: ObjectShape
        var codingPath: [CodingKey] = []
        var allKeys: [Key] { [] }
        func contains(_ key: Key) -> Bool { true }
        func decodeNil(forKey key: Key) throws -> Bool { false }

        func record<T: Decodable>(_ type: T.Type, _ key: Key, optional: Bool) throws -> T {
            try Recorder.value(T.self, into: shape.add(key.stringValue, optional: optional))
        }

        func decode<T: Decodable>(_ type: T.Type, forKey key: Key) throws -> T { try record(T.self, key, optional: false) }
        func decodeIfPresent<T: Decodable>(_ type: T.Type, forKey key: Key) throws -> T? { try record(T.self, key, optional: true) }

        func decode(_ type: Bool.Type, forKey key: Key) throws -> Bool { try record(type, key, optional: false) }
        func decode(_ type: String.Type, forKey key: Key) throws -> String { try record(type, key, optional: false) }
        func decode(_ type: Double.Type, forKey key: Key) throws -> Double { try record(type, key, optional: false) }
        func decode(_ type: Float.Type, forKey key: Key) throws -> Float { try record(type, key, optional: false) }
        func decode(_ type: Int.Type, forKey key: Key) throws -> Int { try record(type, key, optional: false) }
        func decode(_ type: Int8.Type, forKey key: Key) throws -> Int8 { try record(type, key, optional: false) }
        func decode(_ type: Int16.Type, forKey key: Key) throws -> Int16 { try record(type, key, optional: false) }
        func decode(_ type: Int32.Type, forKey key: Key) throws -> Int32 { try record(type, key, optional: false) }
        func decode(_ type: Int64.Type, forKey key: Key) throws -> Int64 { try record(type, key, optional: false) }
        func decode(_ type: UInt.Type, forKey key: Key) throws -> UInt { try record(type, key, optional: false) }
        func decode(_ type: UInt8.Type, forKey key: Key) throws -> UInt8 { try record(type, key, optional: false) }
        func decode(_ type: UInt16.Type, forKey key: Key) throws -> UInt16 { try record(type, key, optional: false) }
        func decode(_ type: UInt32.Type, forKey key: Key) throws -> UInt32 { try record(type, key, optional: false) }
        func decode(_ type: UInt64.Type, forKey key: Key) throws -> UInt64 { try record(type, key, optional: false) }

        func decodeIfPresent(_ type: Bool.Type, forKey key: Key) throws -> Bool? { try record(type, key, optional: true) }
        func decodeIfPresent(_ type: String.Type, forKey key: Key) throws -> String? { try record(type, key, optional: true) }
        func decodeIfPresent(_ type: Double.Type, forKey key: Key) throws -> Double? { try record(type, key, optional: true) }
        func decodeIfPresent(_ type: Float.Type, forKey key: Key) throws -> Float? { try record(type, key, optional: true) }
        func decodeIfPresent(_ type: Int.Type, forKey key: Key) throws -> Int? { try record(type, key, optional: true) }
        func decodeIfPresent(_ type: Int8.Type, forKey key: Key) throws -> Int8? { try record(type, key, optional: true) }
        func decodeIfPresent(_ type: Int16.Type, forKey key: Key) throws -> Int16? { try record(type, key, optional: true) }
        func decodeIfPresent(_ type: Int32.Type, forKey key: Key) throws -> Int32? { try record(type, key, optional: true) }
        func decodeIfPresent(_ type: Int64.Type, forKey key: Key) throws -> Int64? { try record(type, key, optional: true) }
        func decodeIfPresent(_ type: UInt.Type, forKey key: Key) throws -> UInt? { try record(type, key, optional: true) }
        func decodeIfPresent(_ type: UInt8.Type, forKey key: Key) throws -> UInt8? { try record(type, key, optional: true) }
        func decodeIfPresent(_ type: UInt16.Type, forKey key: Key) throws -> UInt16? { try record(type, key, optional: true) }
        func decodeIfPresent(_ type: UInt32.Type, forKey key: Key) throws -> UInt32? { try record(type, key, optional: true) }
        func decodeIfPresent(_ type: UInt64.Type, forKey key: Key) throws -> UInt64? { try record(type, key, optional: true) }

        func nestedContainer<NestedKey: CodingKey>(keyedBy type: NestedKey.Type, forKey key: Key) throws -> KeyedDecodingContainer<NestedKey> {
            try RecordingDecoder(slot: shape.add(key.stringValue, optional: false)).container(keyedBy: type)
        }
        func nestedUnkeyedContainer(forKey key: Key) throws -> UnkeyedDecodingContainer {
            try RecordingDecoder(slot: shape.add(key.stringValue, optional: false)).unkeyedContainer()
        }
        func superDecoder() throws -> Decoder { throw RecorderError(description: "superDecoder is not supported") }
        func superDecoder(forKey key: Key) throws -> Decoder { throw RecorderError(description: "superDecoder is not supported") }
    }

    /// Reports exactly one element, so the element type gets recorded once.
    struct Unkeyed: UnkeyedDecodingContainer {
        let items: Slot
        var codingPath: [CodingKey] = []
        var count: Int? { 1 }
        var currentIndex = 0
        var isAtEnd: Bool { currentIndex > 0 }

        mutating func decodeNil() throws -> Bool { false }
        mutating func decode<T: Decodable>(_ type: T.Type) throws -> T {
            currentIndex += 1
            return try Recorder.value(T.self, into: items)
        }
        mutating func nestedContainer<NestedKey: CodingKey>(keyedBy type: NestedKey.Type) throws -> KeyedDecodingContainer<NestedKey> {
            currentIndex += 1
            return try RecordingDecoder(slot: items).container(keyedBy: type)
        }
        mutating func nestedUnkeyedContainer() throws -> UnkeyedDecodingContainer {
            currentIndex += 1
            return try RecordingDecoder(slot: items).unkeyedContainer()
        }
        mutating func superDecoder() throws -> Decoder { throw RecorderError(description: "superDecoder is not supported") }
    }

    struct Single: SingleValueDecodingContainer {
        let slot: Slot
        var codingPath: [CodingKey] = []
        func decodeNil() -> Bool { false }
        func decode<T: Decodable>(_ type: T.Type) throws -> T { try Recorder.value(T.self, into: slot) }
    }
}

// MARK: - Validation

/// Validates a payload against the schema subset that inference produces.
/// Error messages are identical on Android: `/count must be integer`, `/count is required`, `/extra is not allowed`.
public enum SchemaValidator {
    /// Returns the first violation, or nil when the value conforms.
    public static func validate(_ value: JSONValue, against schema: JSONValue) -> String? {
        validate(value, schema, path: "")
    }

    private static func validate(_ value: JSONValue, _ schema: JSONValue, path: String) -> String? {
        let pointer = path.isEmpty ? "/" : path
        if let types = schema["type"] {
            let allowed = types.arrayValue?.compactMap(\.stringValue) ?? [types.stringValue].compactMap { $0 }
            if !allowed.contains(where: { matches(value, type: $0) }) {
                return "\(pointer) must be \(allowed.joined(separator: " or "))"
            }
        }
        if let options = schema["enum"]?.arrayValue, !options.contains(value) {
            return "\(pointer) must be one of \(options.map { $0.serialized() }.joined(separator: ", "))"
        }
        if case .object(let object) = value {
            let properties = schema["properties"]?.objectValue ?? JSONObject()
            for case .string(let key) in schema["required"]?.arrayValue ?? [] where object[key] == nil {
                return "\(path)/\(key) is required"
            }
            if schema["additionalProperties"] == .bool(false) {
                for key in object.keys where properties[key] == nil {
                    return "\(path)/\(key) is not allowed"
                }
            }
            for (key, propertySchema) in properties {
                if let child = object[key], let error = validate(child, propertySchema, path: "\(path)/\(key)") { return error }
            }
        }
        if case .array(let items) = value, let itemSchema = schema["items"] {
            for (i, item) in items.enumerated() {
                if let error = validate(item, itemSchema, path: "\(path)/\(i)") { return error }
            }
        }
        return nil
    }

    private static func matches(_ value: JSONValue, type: String) -> Bool {
        switch (type, value) {
        case ("string", .string), ("boolean", .bool), ("object", .object), ("array", .array), ("null", .null), ("number", .number):
            return true
        case ("integer", .number(let n)):
            return n == n.rounded() && n.isFinite
        default:
            return false
        }
    }
}
