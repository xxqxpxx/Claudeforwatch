import Foundation
@testable import ClaudeWatchKit

/// Loads files from the repo's shared `spec/` directory.
enum Fixtures {
    static let specDirectory: URL = {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while dir.path != "/" {
            let candidate = dir.appendingPathComponent("spec/fixtures")
            if FileManager.default.fileExists(atPath: candidate.path) {
                return dir.appendingPathComponent("spec")
            }
            dir = dir.deletingLastPathComponent()
        }
        fatalError("spec/ not found above \(#filePath)")
    }()

    static func fixture(_ name: String) throws -> Data {
        try Data(contentsOf: specDirectory.appendingPathComponent("fixtures/\(name)"))
    }

    static func fixtureText(_ name: String) throws -> String {
        String(decoding: try fixture(name), as: UTF8.self)
    }

    static func expected(_ name: String) throws -> JSONValue {
        try JSONValue.parse(try Data(contentsOf: specDirectory.appendingPathComponent("expected/\(name)")))
    }

    /// Parses a whole SSE fixture.
    static func sseEvents(_ name: String) throws -> [SSEEvent] {
        var parser = SSEParser()
        return parser.feed(try fixture(name)) + parser.flush()
    }
}

/// Mutable clock for tests.
final class TestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var current: Date

    init(_ date: Date = Date(timeIntervalSince1970: 1_760_000_000)) { current = date }

    var now: Date { lock.withLock { current } }
    func advance(_ seconds: TimeInterval) { lock.withLock { current = current.addingTimeInterval(seconds) } }
    var nowMillis: Int64 { Int64((now.timeIntervalSince1970 * 1000).rounded()) }
}

/// Deterministic UUIDs for request-body assertions.
final class SequentialIDs: @unchecked Sendable {
    private let lock = NSLock()
    private var n = 0
    func next() -> String { lock.withLock { n += 1; return "uuid-\(n)" } }
}

extension TranscriptItem {
    /// The item in the `spec/expected` JSON shape (only non-nil keys).
    var expectedShape: JSONValue {
        var o = JSONObject()
        o["seq"] = .number(Double(seq))
        o["kind"] = .string(kind.rawValue)
        if let text { o["text"] = .string(text) }
        if let tool { o["tool"] = .string(tool) }
        if let requestId { o["requestId"] = .string(requestId) }
        if let isError { o["isError"] = .bool(isError) }
        return .object(o)
    }
}

let fixtureOAuthCredentials = Credentials(
    mode: .claudeAccount,
    accessToken: "sk-ant-oat01-old",
    refreshToken: "sk-ant-ort01-old",
    expiresAt: 4_000_000_000_000, // far future; tests that need expiry override it
    scopes: ["user:profile", "user:inference", "user:sessions:claude_code"],
    organizationUuid: "org-uuid-fixture",
    accountEmail: "you@example.com"
)
