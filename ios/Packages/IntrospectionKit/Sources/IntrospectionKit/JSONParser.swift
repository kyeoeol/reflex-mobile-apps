import Foundation

public struct JSONParseError: Error, CustomStringConvertible, Sendable {
    public let message: String
    public let offset: Int
    public var description: String { "Invalid JSON at offset \(offset): \(message)" }
}

/// A small, strict, order-preserving JSON parser (RFC 8259). Foundation's parser loses key order.
public enum JSONParser {
    public static func parse(_ text: String) throws -> JSONValue {
        try parse(Data(text.utf8))
    }

    public static func parse(_ data: Data) throws -> JSONValue {
        var parser = Parser(bytes: Array(data))
        parser.skipWhitespace()
        let value = try parser.parseValue(depth: 0)
        parser.skipWhitespace()
        guard parser.index == parser.bytes.count else { throw parser.error("Unexpected trailing characters") }
        return value
    }

    private struct Parser {
        let bytes: [UInt8]
        var index = 0

        init(bytes: [UInt8]) { self.bytes = bytes }

        func error(_ message: String) -> JSONParseError { JSONParseError(message: message, offset: index) }

        mutating func skipWhitespace() {
            while index < bytes.count, [0x20, 0x0A, 0x0D, 0x09].contains(bytes[index]) { index += 1 }
        }

        mutating func parseValue(depth: Int) throws -> JSONValue {
            guard depth < 512 else { throw error("Nesting too deep") }
            guard index < bytes.count else { throw error("Unexpected end of input") }
            switch bytes[index] {
            case UInt8(ascii: "{"): return try parseObject(depth: depth)
            case UInt8(ascii: "["): return try parseArray(depth: depth)
            case UInt8(ascii: "\""): return .string(try parseString())
            case UInt8(ascii: "t"): try expect("true"); return .bool(true)
            case UInt8(ascii: "f"): try expect("false"); return .bool(false)
            case UInt8(ascii: "n"): try expect("null"); return .null
            default: return .number(try parseNumber())
            }
        }

        mutating func expect(_ literal: String) throws {
            let lit = Array(literal.utf8)
            guard index + lit.count <= bytes.count, Array(bytes[index..<index + lit.count]) == lit else {
                throw error("Expected '\(literal)'")
            }
            index += lit.count
        }

        mutating func parseObject(depth: Int) throws -> JSONValue {
            index += 1
            var object = JSONObject()
            skipWhitespace()
            if index < bytes.count, bytes[index] == UInt8(ascii: "}") { index += 1; return .object(object) }
            while true {
                skipWhitespace()
                guard index < bytes.count, bytes[index] == UInt8(ascii: "\"") else { throw error("Expected object key") }
                let key = try parseString()
                skipWhitespace()
                guard index < bytes.count, bytes[index] == UInt8(ascii: ":") else { throw error("Expected ':'") }
                index += 1
                skipWhitespace()
                object[key] = try parseValue(depth: depth + 1)
                skipWhitespace()
                guard index < bytes.count else { throw error("Unterminated object") }
                if bytes[index] == UInt8(ascii: ",") { index += 1; continue }
                if bytes[index] == UInt8(ascii: "}") { index += 1; return .object(object) }
                throw error("Expected ',' or '}'")
            }
        }

        mutating func parseArray(depth: Int) throws -> JSONValue {
            index += 1
            var items: [JSONValue] = []
            skipWhitespace()
            if index < bytes.count, bytes[index] == UInt8(ascii: "]") { index += 1; return .array(items) }
            while true {
                skipWhitespace()
                items.append(try parseValue(depth: depth + 1))
                skipWhitespace()
                guard index < bytes.count else { throw error("Unterminated array") }
                if bytes[index] == UInt8(ascii: ",") { index += 1; continue }
                if bytes[index] == UInt8(ascii: "]") { index += 1; return .array(items) }
                throw error("Expected ',' or ']'")
            }
        }

        mutating func parseString() throws -> String {
            index += 1
            var scalars = String.UnicodeScalarView()
            var raw: [UInt8] = []
            func flushRaw() throws {
                guard !raw.isEmpty else { return }
                guard let s = String(bytes: raw, encoding: .utf8) else { throw error("Invalid UTF-8") }
                scalars.append(contentsOf: s.unicodeScalars)
                raw.removeAll(keepingCapacity: true)
            }
            while true {
                guard index < bytes.count else { throw error("Unterminated string") }
                let byte = bytes[index]
                if byte == UInt8(ascii: "\"") {
                    index += 1
                    try flushRaw()
                    return String(scalars)
                }
                if byte < 0x20 { throw error("Control character in string") }
                if byte == UInt8(ascii: "\\") {
                    try flushRaw()
                    index += 1
                    guard index < bytes.count else { throw error("Unterminated escape") }
                    let esc = bytes[index]
                    index += 1
                    switch esc {
                    case UInt8(ascii: "\""): scalars.append("\"")
                    case UInt8(ascii: "\\"): scalars.append("\\")
                    case UInt8(ascii: "/"): scalars.append("/")
                    case UInt8(ascii: "b"): scalars.append("\u{08}")
                    case UInt8(ascii: "f"): scalars.append("\u{0C}")
                    case UInt8(ascii: "n"): scalars.append("\n")
                    case UInt8(ascii: "r"): scalars.append("\r")
                    case UInt8(ascii: "t"): scalars.append("\t")
                    case UInt8(ascii: "u"):
                        var code = try parseHex4()
                        if (0xD800...0xDBFF).contains(code) {
                            guard index + 1 < bytes.count, bytes[index] == UInt8(ascii: "\\"), bytes[index + 1] == UInt8(ascii: "u")
                            else { throw error("Unpaired surrogate") }
                            index += 2
                            let low = try parseHex4()
                            guard (0xDC00...0xDFFF).contains(low) else { throw error("Invalid surrogate pair") }
                            code = 0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00)
                        }
                        guard let scalar = Unicode.Scalar(code) else { throw error("Invalid unicode escape") }
                        scalars.append(scalar)
                    default: throw error("Invalid escape")
                    }
                    continue
                }
                raw.append(byte)
                index += 1
            }
        }

        mutating func parseHex4() throws -> UInt32 {
            guard index + 4 <= bytes.count, let s = String(bytes: bytes[index..<index + 4], encoding: .ascii),
                  let v = UInt32(s, radix: 16) else { throw error("Invalid \\u escape") }
            index += 4
            return v
        }

        mutating func parseNumber() throws -> Double {
            let start = index
            if index < bytes.count, bytes[index] == UInt8(ascii: "-") { index += 1 }
            func digits() -> Int {
                let s = index
                while index < bytes.count, (UInt8(ascii: "0")...UInt8(ascii: "9")).contains(bytes[index]) { index += 1 }
                return index - s
            }
            guard digits() > 0 else { throw error("Invalid value") }
            if index < bytes.count, bytes[index] == UInt8(ascii: ".") {
                index += 1
                guard digits() > 0 else { throw error("Invalid number") }
            }
            if index < bytes.count, bytes[index] == UInt8(ascii: "e") || bytes[index] == UInt8(ascii: "E") {
                index += 1
                if index < bytes.count, bytes[index] == UInt8(ascii: "+") || bytes[index] == UInt8(ascii: "-") { index += 1 }
                guard digits() > 0 else { throw error("Invalid number") }
            }
            guard let text = String(bytes: bytes[start..<index], encoding: .ascii), let value = Double(text) else {
                throw error("Invalid number")
            }
            return value
        }
    }
}
