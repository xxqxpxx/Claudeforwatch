import Foundation

/// What the complication/widget shows. The app writes it into the App Group
/// container; the widget extension only reads it (no network in the widget).
public struct WidgetSnapshot: Codable, Sendable, Equatable {
    public static let fileName = "widget-snapshot.json"

    /// Sessions with `worker_status == requires_action`; nil outside claudeAccount mode.
    public var needsActionCount: Int?
    /// Headline usage 0–100; nil when unknown.
    public var usagePercent: Int?
    public var signedIn: Bool
    /// Epoch ms.
    public var updatedAt: Int64

    public init(needsActionCount: Int? = nil, usagePercent: Int? = nil, signedIn: Bool = false, updatedAt: Int64) {
        self.needsActionCount = needsActionCount
        self.usagePercent = usagePercent
        self.signedIn = signedIn
        self.updatedAt = updatedAt
    }

    public static let placeholder = WidgetSnapshot(needsActionCount: 1, usagePercent: 42, signedIn: true, updatedAt: 0)
}

/// A question captured by the "Ask Claude" App Intent, answered by the app on
/// launch (the intent opens the app; the reply streams there).
public struct PendingAsk: Codable, Sendable, Equatable {
    public static let fileName = "pending-ask.json"
    /// Older questions are discarded rather than answered out of context.
    public static let maxAge: TimeInterval = 10 * 60

    public var text: String
    /// Epoch ms.
    public var createdAt: Int64

    public init(text: String, createdAt: Int64) {
        self.text = text
        self.createdAt = createdAt
    }

    public func isFresh(now: Date = Date()) -> Bool {
        let nowMs = Int64(now.timeIntervalSince1970 * 1000)
        return nowMs - createdAt <= Int64(PendingAsk.maxAge * 1000)
    }
}

/// Tiny JSON-file helpers for the App Group container (Foundation only).
public enum SharedJSONFile {
    public static func read<T: Decodable>(_ type: T.Type, named name: String, in directory: URL) -> T? {
        guard let data = try? Data(contentsOf: directory.appendingPathComponent(name)) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    public static func write<T: Encodable>(_ value: T, named name: String, in directory: URL) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let data = try JSONEncoder().encode(value)
        try data.write(to: directory.appendingPathComponent(name), options: [.atomic])
    }

    public static func remove(named name: String, in directory: URL) {
        try? FileManager.default.removeItem(at: directory.appendingPathComponent(name))
    }

    /// Reads and deletes in one step (used for the one-shot pending question).
    public static func take<T: Decodable>(_ type: T.Type, named name: String, in directory: URL) -> T? {
        let value = read(type, named: name, in: directory)
        remove(named: name, in: directory)
        return value
    }
}
