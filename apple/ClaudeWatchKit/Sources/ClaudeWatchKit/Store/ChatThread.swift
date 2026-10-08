import Foundation

public enum ChatRole: String, Codable, Sendable {
    case user, assistant
}

/// One stored message (docs/PROTOCOL.md §4 thread JSON).
public struct ChatMessage: Codable, Sendable, Equatable {
    public var role: ChatRole
    public var text: String
    /// Epoch milliseconds.
    public var at: Int64
    public var stopReason: String?

    public init(role: ChatRole, text: String, at: Int64, stopReason: String? = nil) {
        self.role = role
        self.text = text
        self.at = at
        self.stopReason = stopReason
    }

    enum CodingKeys: String, CodingKey { case role, text, at, stopReason }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(role, forKey: .role)
        try c.encode(text, forKey: .text)
        try c.encode(at, forKey: .at)
        try c.encode(stopReason, forKey: .stopReason) // explicit null, matches the spec shape
    }
}

/// A local quick-chat thread, stored on the watch only (§4).
public struct ChatThread: Codable, Sendable, Equatable, Identifiable {
    public static let titleLength = 40

    public var id: String
    public var title: String
    public var createdAt: Int64
    public var updatedAt: Int64
    public var model: String
    public var messages: [ChatMessage]

    public init(
        id: String = UUID().uuidString.lowercased(),
        title: String = "",
        createdAt: Int64,
        updatedAt: Int64? = nil,
        model: String = ClaudeModel.default.rawValue,
        messages: [ChatMessage] = []
    ) {
        self.id = id
        self.title = title
        self.createdAt = createdAt
        self.updatedAt = updatedAt ?? createdAt
        self.model = model
        self.messages = messages
    }

    public static func nowMillis(_ date: Date = Date()) -> Int64 {
        Int64((date.timeIntervalSince1970 * 1000).rounded())
    }

    /// First 40 characters of the first user message.
    public static func title(from text: String) -> String {
        let flat = text.split(whereSeparator: \.isNewline).joined(separator: " ")
            .trimmingCharacters(in: .whitespaces)
        return String(flat.prefix(titleLength))
    }

    public var displayTitle: String { title.isEmpty ? "New chat" : title }

    public mutating func appendUser(_ text: String, at: Int64) {
        messages.append(ChatMessage(role: .user, text: text, at: at))
        if title.isEmpty { title = ChatThread.title(from: text) }
        updatedAt = at
    }

    public mutating func appendAssistant(_ text: String, stopReason: String?, at: Int64) {
        messages.append(ChatMessage(role: .assistant, text: text, at: at, stopReason: stopReason))
        updatedAt = at
    }

    /// Turns to send (the client trims to the last 20).
    public var turns: [ChatTurn] { messages.map { ChatTurn(role: $0.role, text: $0.text) } }

    public var lastMessagePreview: String? { messages.last?.text }
}

/// Persistence for chat threads. The app uses `JSONFileThreadStore` in its
/// container; tests use `InMemoryThreadStore`.
public protocol ThreadStore: Sendable {
    /// All threads, most recently updated first.
    func list() async throws -> [ChatThread]
    func get(id: String) async throws -> ChatThread?
    func save(_ thread: ChatThread) async throws
    func delete(id: String) async throws
}

extension ThreadStore {
    static func sorted(_ threads: some Sequence<ChatThread>) -> [ChatThread] {
        threads.sorted { $0.updatedAt != $1.updatedAt ? $0.updatedAt > $1.updatedAt : $0.id < $1.id }
    }
}

public actor InMemoryThreadStore: ThreadStore {
    private var threads: [String: ChatThread]

    public init(_ initial: [ChatThread] = []) {
        threads = Dictionary(initial.map { ($0.id, $0) }, uniquingKeysWith: { _, b in b })
    }

    public func list() async throws -> [ChatThread] { Self.sorted(threads.values) }
    public func get(id: String) async throws -> ChatThread? { threads[id] }
    public func save(_ thread: ChatThread) async throws { threads[thread.id] = thread }
    public func delete(id: String) async throws { threads[id] = nil }
}

/// Stores all threads in one JSON file (`threads.json`), written atomically.
/// Keeps at most `maxThreads` (oldest dropped) to respect the watch's storage.
public actor JSONFileThreadStore: ThreadStore {
    public let fileURL: URL
    public let maxThreads: Int
    private var cache: [String: ChatThread]?

    public init(directory: URL, fileName: String = "threads.json", maxThreads: Int = 100) {
        self.fileURL = directory.appendingPathComponent(fileName)
        self.maxThreads = maxThreads
    }

    public func list() async throws -> [ChatThread] { Self.sorted(try load().values) }

    public func get(id: String) async throws -> ChatThread? { try load()[id] }

    public func save(_ thread: ChatThread) async throws {
        var all = try load()
        all[thread.id] = thread
        if all.count > maxThreads {
            for old in Self.sorted(all.values).dropFirst(maxThreads) { all[old.id] = nil }
        }
        try write(all)
    }

    public func delete(id: String) async throws {
        var all = try load()
        all[id] = nil
        try write(all)
    }

    private func load() throws -> [String: ChatThread] {
        if let cache { return cache }
        guard FileManager.default.fileExists(atPath: fileURL.path) else {
            cache = [:]
            return [:]
        }
        let data = try Data(contentsOf: fileURL)
        let list: [ChatThread]
        do {
            list = try JSONDecoder().decode([ChatThread].self, from: data)
        } catch {
            // Corrupt file: start fresh rather than crash on every launch.
            list = []
        }
        let dict = Dictionary(list.map { ($0.id, $0) }, uniquingKeysWith: { _, b in b })
        cache = dict
        return dict
    }

    private func write(_ all: [String: ChatThread]) throws {
        try FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(Self.sorted(all.values))
        try data.write(to: fileURL, options: [.atomic])
        cache = all
    }
}
