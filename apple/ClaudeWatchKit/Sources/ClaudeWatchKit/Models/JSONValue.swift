import Foundation

/// An arbitrary JSON value that keeps object keys in document order.
///
/// Order matters for one rule in docs/PROTOCOL.md §5.3: the one-line summary of
/// an unknown tool's input is its *first* string value. Foundation's
/// `JSONDecoder`/`JSONSerialization` do not preserve key order, so wire data that
/// feeds the transcript reducer is parsed with `JSONValue.parse(_:)`, a small
/// ordered parser. `Codable` conformance is provided too (order then follows
/// the decoder; keys are sorted for determinism).
public enum JSONValue: Sendable, Hashable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object(JSONObject)

    // MARK: Accessors

    public var stringValue: String? {
        if case .string(let s) = self { return s }
        return nil
    }

    public var boolValue: Bool? {
        if case .bool(let b) = self { return b }
        return nil
    }

    public var doubleValue: Double? {
        if case .number(let n) = self { return n }
        return nil
    }

    /// Integer value; also accepts numeric strings (`"sequence_num":"124"`).
    public var intValue: Int? {
        switch self {
        case .number(let n):
            guard n.isFinite, n == n.rounded(), abs(n) < 9.0e15 else { return nil }
            return Int(n)
        case .string(let s):
            return Int(s.trimmingCharacters(in: .whitespaces))
        default:
            return nil
        }
    }

    public var arrayValue: [JSONValue]? {
        if case .array(let a) = self { return a }
        return nil
    }

    public var objectValue: JSONObject? {
        if case .object(let o) = self { return o }
        return nil
    }

    public var isNull: Bool {
        if case .null = self { return true }
        return false
    }

    /// Object member lookup; `nil` for non-objects and missing keys.
    public subscript(key: String) -> JSONValue? {
        objectValue?[key]
    }
}

/// An insertion-ordered JSON object.
public struct JSONObject: Sendable, Hashable, Sequence, ExpressibleByDictionaryLiteral {
    public private(set) var entries: [(key: String, value: JSONValue)]

    public init() { entries = [] }

    public init(_ entries: [(key: String, value: JSONValue)]) {
        self.entries = []
        for (k, v) in entries { self[k] = v }
    }

    public init(dictionaryLiteral elements: (String, JSONValue)...) {
        self.init(elements.map { (key: $0.0, value: $0.1) })
    }

    public var keys: [String] { entries.map(\.key) }
    public var count: Int { entries.count }
    public var isEmpty: Bool { entries.isEmpty }

    public subscript(key: String) -> JSONValue? {
        get { entries.first(where: { $0.key == key })?.value }
        set {
            if let idx = entries.firstIndex(where: { $0.key == key }) {
                if let newValue { entries[idx].value = newValue } else { entries.remove(at: idx) }
            } else if let newValue {
                entries.append((key: key, value: newValue))
            }
        }
    }

    public func makeIterator() -> IndexingIterator<[(key: String, value: JSONValue)]> {
        entries.makeIterator()
    }

    public static func == (lhs: JSONObject, rhs: JSONObject) -> Bool {
        guard lhs.entries.count == rhs.entries.count else { return false }
        // Equality ignores order (JSON semantics); lookups are by key.
        for (k, v) in lhs.entries where rhs[k] != v { return false }
        return true
    }

    public func hash(into hasher: inout Hasher) {
        for (k, v) in entries.sorted(by: { $0.key < $1.key }) {
            hasher.combine(k)
            hasher.combine(v)
        }
    }
}

// MARK: - Codable

extension JSONValue: Codable {
    private struct AnyKey: CodingKey {
        var stringValue: String
        var intValue: Int? { nil }
        init(stringValue: String) { self.stringValue = stringValue }
        init?(intValue: Int) { nil }
    }

    public init(from decoder: any Decoder) throws {
        if let keyed = try? decoder.container(keyedBy: AnyKey.self) {
            var obj = JSONObject()
            for key in keyed.allKeys.sorted(by: { $0.stringValue < $1.stringValue }) {
                obj[key.stringValue] = try keyed.decode(JSONValue.self, forKey: key)
            }
            self = .object(obj)
            return
        }
        if var unkeyed = try? decoder.unkeyedContainer() {
            var arr: [JSONValue] = []
            while !unkeyed.isAtEnd { arr.append(try unkeyed.decode(JSONValue.self)) }
            self = .array(arr)
            return
        }
        let single = try decoder.singleValueContainer()
        if single.decodeNil() { self = .null }
        else if let b = try? single.decode(Bool.self) { self = .bool(b) }
        else if let n = try? single.decode(Double.self) { self = .number(n) }
        else if let s = try? single.decode(String.self) { self = .string(s) }
        else {
            throw DecodingError.dataCorruptedError(in: single, debugDescription: "Unsupported JSON value")
        }
    }

    public func encode(to encoder: any Encoder) throws {
        switch self {
        case .null:
            var c = encoder.singleValueContainer(); try c.encodeNil()
        case .bool(let b):
            var c = encoder.singleValueContainer(); try c.encode(b)
        case .number(let n):
            var c = encoder.singleValueContainer()
            if let i = self.intValue { try c.encode(i) } else { try c.encode(n) }
        case .string(let s):
            var c = encoder.singleValueContainer(); try c.encode(s)
        case .array(let a):
            var c = encoder.unkeyedContainer()
            for v in a { try c.encode(v) }
        case .object(let o):
            var c = encoder.container(keyedBy: AnyKey.self)
            for (k, v) in o { try c.encode(v, forKey: AnyKey(stringValue: k)) }
        }
    }
}

// MARK: - Ordered parser and serializer

public struct JSONParseError: Error, Sendable, Equatable {
    public let offset: Int
    public let reason: String
}

extension JSONValue {
    /// Parses UTF-8 JSON preserving object key order.
    public static func parse(_ data: Data) throws -> JSONValue {
        var parser = OrderedJSONParser(bytes: Array(data))
        return try parser.parseDocument()
    }

    public static func parse(_ string: String) throws -> JSONValue {
        try parse(Data(string.utf8))
    }

    /// Compact JSON text, object keys in stored order.
    public func serialized() -> String {
        var out = ""
        write(into: &out)
        return out
    }

    public func serializedData() -> Data { Data(serialized().utf8) }

    private func write(into out: inout String) {
        switch self {
        case .null: out += "null"
        case .bool(let b): out += b ? "true" : "false"
        case .number(let n):
            if let i = intValue { out += String(i) }
            else if n.isFinite { out += String(n) }
            else { out += "null" }
        case .string(let s): JSONValue.writeString(s, into: &out)
        case .array(let a):
            out += "["
            for (i, v) in a.enumerated() {
                if i > 0 { out += "," }
                v.write(into: &out)
            }
            out += "]"
        case .object(let o):
            out += "{"
            for (i, entry) in o.entries.enumerated() {
                if i > 0 { out += "," }
                JSONValue.writeString(entry.key, into: &out)
                out += ":"
                entry.value.write(into: &out)
            }
            out += "}"
        }
    }

    private static func writeString(_ s: String, into out: inout String) {
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
            default:
                if scalar.value < 0x20 {
                    let hex = String(scalar.value, radix: 16)
                    out += "\\u" + String(repeating: "0", count: 4 - hex.count) + hex
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        out += "\""
    }
}

private struct OrderedJSONParser {
    let bytes: [UInt8]
    var pos = 0
    var depth = 0
    static let maxDepth = 256

    init(bytes: [UInt8]) {
        self.bytes = bytes
        // Skip a UTF-8 BOM.
        if bytes.starts(with: [0xEF, 0xBB, 0xBF]) { pos = 3 }
    }

    mutating func parseDocument() throws -> JSONValue {
        let v = try parseValue()
        skipWhitespace()
        guard pos == bytes.count else { throw fail("trailing characters") }
        return v
    }

    func fail(_ reason: String) -> JSONParseError { JSONParseError(offset: pos, reason: reason) }

    mutating func skipWhitespace() {
        while pos < bytes.count, [0x20, 0x09, 0x0A, 0x0D].contains(bytes[pos]) { pos += 1 }
    }

    mutating func parseValue() throws -> JSONValue {
        skipWhitespace()
        guard pos < bytes.count else { throw fail("unexpected end") }
        switch bytes[pos] {
        case UInt8(ascii: "{"): return try parseObject()
        case UInt8(ascii: "["): return try parseArray()
        case UInt8(ascii: "\""): return .string(try parseString())
        case UInt8(ascii: "t"): try expectLiteral("true"); return .bool(true)
        case UInt8(ascii: "f"): try expectLiteral("false"); return .bool(false)
        case UInt8(ascii: "n"): try expectLiteral("null"); return .null
        default: return .number(try parseNumber())
        }
    }

    mutating func expectLiteral(_ lit: String) throws {
        let u = Array(lit.utf8)
        guard pos + u.count <= bytes.count, Array(bytes[pos..<pos + u.count]) == u else {
            throw fail("invalid literal")
        }
        pos += u.count
    }

    mutating func parseObject() throws -> JSONValue {
        depth += 1
        defer { depth -= 1 }
        guard depth <= Self.maxDepth else { throw fail("nesting too deep") }
        pos += 1
        var obj = JSONObject()
        skipWhitespace()
        if pos < bytes.count, bytes[pos] == UInt8(ascii: "}") { pos += 1; return .object(obj) }
        while true {
            skipWhitespace()
            guard pos < bytes.count, bytes[pos] == UInt8(ascii: "\"") else { throw fail("expected key") }
            let key = try parseString()
            skipWhitespace()
            guard pos < bytes.count, bytes[pos] == UInt8(ascii: ":") else { throw fail("expected ':'") }
            pos += 1
            obj[key] = try parseValue()
            skipWhitespace()
            guard pos < bytes.count else { throw fail("unexpected end in object") }
            if bytes[pos] == UInt8(ascii: ",") { pos += 1; continue }
            if bytes[pos] == UInt8(ascii: "}") { pos += 1; return .object(obj) }
            throw fail("expected ',' or '}'")
        }
    }

    mutating func parseArray() throws -> JSONValue {
        depth += 1
        defer { depth -= 1 }
        guard depth <= Self.maxDepth else { throw fail("nesting too deep") }
        pos += 1
        var arr: [JSONValue] = []
        skipWhitespace()
        if pos < bytes.count, bytes[pos] == UInt8(ascii: "]") { pos += 1; return .array(arr) }
        while true {
            arr.append(try parseValue())
            skipWhitespace()
            guard pos < bytes.count else { throw fail("unexpected end in array") }
            if bytes[pos] == UInt8(ascii: ",") { pos += 1; continue }
            if bytes[pos] == UInt8(ascii: "]") { pos += 1; return .array(arr) }
            throw fail("expected ',' or ']'")
        }
    }

    mutating func parseHex4() throws -> UInt32 {
        guard pos + 4 <= bytes.count else { throw fail("bad \\u escape") }
        var v: UInt32 = 0
        for _ in 0..<4 {
            let c = bytes[pos]
            let d: UInt32
            switch c {
            case 0x30...0x39: d = UInt32(c - 0x30)
            case 0x41...0x46: d = UInt32(c - 0x41 + 10)
            case 0x61...0x66: d = UInt32(c - 0x61 + 10)
            default: throw fail("bad hex digit")
            }
            v = v * 16 + d
            pos += 1
        }
        return v
    }

    mutating func parseString() throws -> String {
        pos += 1 // opening quote
        var buf: [UInt8] = []
        while pos < bytes.count {
            let c = bytes[pos]
            if c == UInt8(ascii: "\"") {
                pos += 1
                return String(decoding: buf, as: UTF8.self)
            }
            if c == UInt8(ascii: "\\") {
                pos += 1
                guard pos < bytes.count else { break }
                let e = bytes[pos]
                pos += 1
                switch e {
                case UInt8(ascii: "\""): buf.append(0x22)
                case UInt8(ascii: "\\"): buf.append(0x5C)
                case UInt8(ascii: "/"): buf.append(0x2F)
                case UInt8(ascii: "b"): buf.append(0x08)
                case UInt8(ascii: "f"): buf.append(0x0C)
                case UInt8(ascii: "n"): buf.append(0x0A)
                case UInt8(ascii: "r"): buf.append(0x0D)
                case UInt8(ascii: "t"): buf.append(0x09)
                case UInt8(ascii: "u"):
                    var code = try parseHex4()
                    if (0xD800...0xDBFF).contains(code) {
                        // Surrogate pair.
                        if pos + 6 <= bytes.count, bytes[pos] == UInt8(ascii: "\\"), bytes[pos + 1] == UInt8(ascii: "u") {
                            pos += 2
                            let low = try parseHex4()
                            if (0xDC00...0xDFFF).contains(low) {
                                code = 0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00)
                            } else {
                                code = 0xFFFD
                            }
                        } else {
                            code = 0xFFFD
                        }
                    } else if (0xDC00...0xDFFF).contains(code) {
                        code = 0xFFFD
                    }
                    let scalar = Unicode.Scalar(code) ?? "\u{FFFD}"
                    buf.append(contentsOf: Array(String(Character(scalar)).utf8))
                default:
                    throw fail("bad escape")
                }
                continue
            }
            buf.append(c)
            pos += 1
        }
        throw fail("unterminated string")
    }

    mutating func parseNumber() throws -> Double {
        let start = pos
        while pos < bytes.count {
            let c = bytes[pos]
            let isNumChar = (c >= 0x30 && c <= 0x39) || c == UInt8(ascii: "-") || c == UInt8(ascii: "+")
                || c == UInt8(ascii: ".") || c == UInt8(ascii: "e") || c == UInt8(ascii: "E")
            if !isNumChar { break }
            pos += 1
        }
        guard pos > start, let d = Double(String(decoding: bytes[start..<pos], as: UTF8.self)) else {
            throw fail("invalid number")
        }
        return d
    }
}
