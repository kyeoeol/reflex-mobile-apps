import Foundation

/// The JSON model shared by nodes, the protocol, and fixtures.
///
/// Serialization rules (identical on Android, so both platforms emit the same bytes):
/// - Integral numbers are written without a decimal point (`13`, never `13.0`).
/// - Object keys keep declaration order (they are never sorted).
/// - Output is compact: no whitespace.
public enum JSONValue: Sendable, Equatable {
    case string(String)
    case number(Double)
    case bool(Bool)
    case array([JSONValue])
    case object(JSONObject)
    case null

    public static func number(_ value: Int) -> JSONValue { .number(Double(value)) }

    /// Converts any `JSONConvertible` value (including optionals and arrays) without a conditional at the call site.
    public init<T: JSONConvertible>(_ value: T) { self = value.jsonValue }
}

/// An ordered JSON object. Dictionary literals keep their written order.
public struct JSONObject: Sendable, Equatable, ExpressibleByDictionaryLiteral, Sequence {
    public private(set) var entries: [(key: String, value: JSONValue)]

    public init() { entries = [] }
    public init(_ entries: [(key: String, value: JSONValue)]) {
        self.entries = []
        for (key, value) in entries { self[key] = value }
    }
    public init(dictionaryLiteral elements: (String, JSONValue)...) {
        self.init(elements.map { (key: $0.0, value: $0.1) })
    }

    public var keys: [String] { entries.map(\.key) }
    public var isEmpty: Bool { entries.isEmpty }
    public var count: Int { entries.count }

    public subscript(key: String) -> JSONValue? {
        get { entries.first(where: { $0.key == key })?.value }
        set {
            let index = entries.firstIndex(where: { $0.key == key })
            switch (index, newValue) {
            case let (index?, value?): entries[index].value = value
            case let (index?, nil): entries.remove(at: index)
            case let (nil, value?): entries.append((key: key, value: value))
            case (nil, nil): break
            }
        }
    }

    public func makeIterator() -> IndexingIterator<[(key: String, value: JSONValue)]> { entries.makeIterator() }

    /// Order-insensitive, like JSON itself.
    public static func == (lhs: JSONObject, rhs: JSONObject) -> Bool {
        lhs.count == rhs.count && lhs.entries.allSatisfy { rhs[$0.key] == $0.value }
    }
}

// MARK: - Literals

extension JSONValue: ExpressibleByStringLiteral, ExpressibleByIntegerLiteral, ExpressibleByFloatLiteral,
    ExpressibleByBooleanLiteral, ExpressibleByArrayLiteral, ExpressibleByDictionaryLiteral, ExpressibleByNilLiteral {
    public init(stringLiteral value: String) { self = .string(value) }
    public init(integerLiteral value: Int) { self = .number(Double(value)) }
    public init(floatLiteral value: Double) { self = .number(value) }
    public init(booleanLiteral value: Bool) { self = .bool(value) }
    public init(arrayLiteral elements: JSONValue...) { self = .array(elements) }
    public init(dictionaryLiteral elements: (String, JSONValue)...) {
        self = .object(JSONObject(elements.map { (key: $0.0, value: $0.1) }))
    }
    public init(nilLiteral: ()) { self = .null }
}

// MARK: - Conversions

public protocol JSONConvertible { var jsonValue: JSONValue { get } }

extension String: JSONConvertible { public var jsonValue: JSONValue { .string(self) } }
extension Bool: JSONConvertible { public var jsonValue: JSONValue { .bool(self) } }
extension Int: JSONConvertible { public var jsonValue: JSONValue { .number(Double(self)) } }
extension Double: JSONConvertible { public var jsonValue: JSONValue { .number(self) } }
extension JSONValue: JSONConvertible { public var jsonValue: JSONValue { self } }
extension JSONObject: JSONConvertible { public var jsonValue: JSONValue { .object(self) } }
extension Optional: JSONConvertible where Wrapped: JSONConvertible {
    public var jsonValue: JSONValue { map(\.jsonValue) ?? .null }
}
extension Array: JSONConvertible where Element: JSONConvertible {
    public var jsonValue: JSONValue { .array(map(\.jsonValue)) }
}

// MARK: - Accessors

public extension JSONValue {
    var stringValue: String? { if case .string(let v) = self { v } else { nil } }
    var numberValue: Double? { if case .number(let v) = self { v } else { nil } }
    var boolValue: Bool? { if case .bool(let v) = self { v } else { nil } }
    var arrayValue: [JSONValue]? { if case .array(let v) = self { v } else { nil } }
    var objectValue: JSONObject? { if case .object(let v) = self { v } else { nil } }
    var isNull: Bool { self == .null }
    subscript(key: String) -> JSONValue? { objectValue?[key] }
}

// MARK: - Serialization

public extension JSONValue {
    /// Compact, deterministic serialization. Android's `JsonValue.serialize()` produces identical output.
    func serialized() -> String {
        var out = ""
        write(to: &out)
        return out
    }

    /// Encodes as UTF-8 data, e.g. to hand to `JSONDecoder`.
    func data() -> Data { Data(serialized().utf8) }

    private func write(to out: inout String) {
        switch self {
        case .string(let s): JSONValue.writeString(s, to: &out)
        case .number(let n): out += JSONValue.formatNumber(n)
        case .bool(let b): out += b ? "true" : "false"
        case .null: out += "null"
        case .array(let items):
            out += "["
            for (i, item) in items.enumerated() {
                if i > 0 { out += "," }
                item.write(to: &out)
            }
            out += "]"
        case .object(let object):
            out += "{"
            for (i, entry) in object.entries.enumerated() {
                if i > 0 { out += "," }
                JSONValue.writeString(entry.key, to: &out)
                out += ":"
                entry.value.write(to: &out)
            }
            out += "}"
        }
    }

    static func formatNumber(_ n: Double) -> String {
        guard n.isFinite else { return "null" }
        if n == n.rounded(), abs(n) < 1e15 { return String(Int64(n)) }
        return "\(n)"
    }

    static func writeString(_ s: String, to out: inout String) {
        out += "\""
        for scalar in s.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            case "\u{08}": out += "\\b"
            case "\u{0C}": out += "\\f"
            case _ where scalar.value < 0x20:
                out += String(format: "\\u%04x", scalar.value)
            default: out.unicodeScalars.append(scalar)
            }
        }
        out += "\""
    }
}

// MARK: - Bridging to Codable

public extension JSONValue {
    /// Encodes any `Encodable` into a `JSONValue` (key order follows the encoder's output).
    static func encode<T: Encodable>(_ value: T) throws -> JSONValue {
        try JSONParser.parse(JSONEncoder().encode(value))
    }

    /// Decodes this value into a `Decodable` type.
    func decode<T: Decodable>(_ type: T.Type) throws -> T {
        try JSONDecoder().decode(T.self, from: data())
    }
}
