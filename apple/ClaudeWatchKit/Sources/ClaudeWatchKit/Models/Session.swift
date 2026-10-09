import Foundation

/// `worker_status` (§5.1). Unknown values are preserved.
public enum WorkerStatus: Sendable, Hashable, Codable {
    case idle, running, requiresAction
    case other(String)

    public init(rawValue: String) {
        switch rawValue {
        case "idle": self = .idle
        case "running": self = .running
        case "requires_action": self = .requiresAction
        default: self = .other(rawValue)
        }
    }

    public var rawValue: String {
        switch self {
        case .idle: return "idle"
        case .running: return "running"
        case .requiresAction: return "requires_action"
        case .other(let s): return s
        }
    }

    public init(from decoder: any Decoder) throws {
        self.init(rawValue: try decoder.singleValueContainer().decode(String.self))
    }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(rawValue)
    }
}

/// One Claude Code session from `GET /v1/code/sessions` (§5.1).
/// UNOFFICIAL private API; every field except `id` is optional and unknown
/// fields are ignored so server additions don't break decoding.
public struct Session: Codable, Sendable, Equatable, Identifiable {
    public struct Config: Codable, Sendable, Equatable {
        public var model: String?
        public init(model: String? = nil) { self.model = model }
    }

    public struct PendingAction: Codable, Sendable, Equatable {
        public var type: String?
        public var toolName: String?

        enum CodingKeys: String, CodingKey {
            case type
            case toolName = "tool_name"
        }
    }

    /// Free-form metadata. `post_turn_summary` arrived as a string in early captures and as an
    /// object (`{"needs_action": …}`) on a live account in Oct 2026, so it is kept as raw JSON.
    public struct ExternalMetadata: Codable, Sendable, Equatable {
        public var postTurnSummaryRaw: JSONValue?
        public var pendingAction: PendingAction?

        public init(postTurnSummary: String? = nil, pendingAction: PendingAction? = nil) {
            self.postTurnSummaryRaw = postTurnSummary.map(JSONValue.string)
            self.pendingAction = pendingAction
        }

        /// One display line, whatever shape the summary has (PROTOCOL §5.1).
        public var postTurnSummary: String? { SummaryText.of(postTurnSummaryRaw) }

        /// What the session waits for, when the summary says so.
        public var needsActionText: String? {
            guard let s = postTurnSummaryRaw?["needs_action"]?.stringValue, !s.isEmpty else { return nil }
            return s
        }

        enum CodingKeys: String, CodingKey {
            case postTurnSummary = "post_turn_summary"
            case pendingAction = "pending_action"
        }

        public init(from decoder: any Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            postTurnSummaryRaw = try? c.decodeIfPresent(JSONValue.self, forKey: .postTurnSummary)
            pendingAction = try? c.decodeIfPresent(PendingAction.self, forKey: .pendingAction)
        }

        public func encode(to encoder: any Encoder) throws {
            var c = encoder.container(keyedBy: CodingKeys.self)
            try c.encodeIfPresent(postTurnSummaryRaw, forKey: .postTurnSummary)
            try c.encodeIfPresent(pendingAction, forKey: .pendingAction)
        }
    }

    public var id: String
    public var title: String?
    public var createdAt: String?
    public var lastEventAt: String?
    public var status: String?
    public var statusBucket: String?
    public var workerStatus: WorkerStatus?
    public var connectionStatus: String?
    public var environmentKind: String?
    public var unread: Bool?
    public var config: Config?
    public var externalMetadata: ExternalMetadata?

    public init(
        id: String, title: String? = nil, createdAt: String? = nil, lastEventAt: String? = nil,
        status: String? = "active", statusBucket: String? = nil, workerStatus: WorkerStatus? = nil,
        connectionStatus: String? = nil, environmentKind: String? = nil, unread: Bool? = nil,
        config: Config? = nil, externalMetadata: ExternalMetadata? = nil
    ) {
        self.id = id
        self.title = title
        self.createdAt = createdAt
        self.lastEventAt = lastEventAt
        self.status = status
        self.statusBucket = statusBucket
        self.workerStatus = workerStatus
        self.connectionStatus = connectionStatus
        self.environmentKind = environmentKind
        self.unread = unread
        self.config = config
        self.externalMetadata = externalMetadata
    }

    enum CodingKeys: String, CodingKey {
        case id, title, status, unread, config
        case createdAt = "created_at"
        case lastEventAt = "last_event_at"
        case statusBucket = "status_bucket"
        case workerStatus = "worker_status"
        case connectionStatus = "connection_status"
        case environmentKind = "environment_kind"
        case externalMetadata = "external_metadata"
    }

    public var isArchived: Bool { status == "archived" }
    public var needsAction: Bool { workerStatus == .requiresAction }
    public var isRunning: Bool { workerStatus == .running }
    public var kind: SessionKind { environmentKind == "bridge" ? .remoteControl : .cloud }
    public var model: String? { config?.model }
    public var displayTitle: String {
        if let t = title?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty { return t }
        return id
    }
    public var lastEventDate: Date? { lastEventAt.flatMap(ISO8601.parse) }
}

/// `environment_kind == "bridge"` is Remote Control on the user's machine.
public enum SessionKind: String, Codable, Sendable {
    case cloud
    case remoteControl
}

public struct SessionList: Codable, Sendable, Equatable {
    public var data: [Session]
    public var nextCursor: String?
    public var resumeToken: String?
    /// Sessions skipped because they could not be decoded (not on the wire).
    public var skipped: Int = 0

    enum CodingKeys: String, CodingKey {
        case data
        case nextCursor = "next_cursor"
        case resumeToken = "resume_token"
    }

    public init(data: [Session], nextCursor: String? = nil, resumeToken: String? = nil, skipped: Int = 0) {
        self.data = data
        self.nextCursor = nextCursor
        self.resumeToken = resumeToken
        self.skipped = skipped
    }

    /// Decodes one session at a time: an odd session is skipped, never the whole list.
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        var items = try c.nestedUnkeyedContainer(forKey: .data)
        var sessions: [Session] = []
        var total = 0
        while !items.isAtEnd {
            total += 1
            if let s = try? items.decode(Session.self) {
                sessions.append(s)
            } else {
                _ = try? items.decode(JSONValue.self) // consume the bad element
            }
        }
        data = sessions
        skipped = total - sessions.count
        nextCursor = try? c.decodeIfPresent(String.self, forKey: .nextCursor)
        resumeToken = try? c.decodeIfPresent(String.self, forKey: .resumeToken)
    }
}

/// Flattens an unknown summary value into display text.
public enum SummaryText {
    static let preferredKeys = ["needs_action", "summary", "text", "title", "status", "description"]

    public static func of(_ value: JSONValue?) -> String? {
        guard let value else { return nil }
        switch value {
        case .string(let s): return s.isEmpty ? nil : s
        case .object(let o):
            for key in preferredKeys { if let s = of(o[key]) { return s } }
            for entry in o.entries { if let s = of(entry.value) { return s } }
            return nil
        case .array(let a):
            for v in a { if let s = of(v) { return s } }
            return nil
        default: return nil
        }
    }
}

enum ISO8601 {
    /// Parses RFC 3339 timestamps with or without fractional seconds.
    static func parse(_ s: String) -> Date? {
        if let d = try? Date(s, strategy: Date.ISO8601FormatStyle(includingFractionalSeconds: false)) { return d }
        if let d = try? Date(s, strategy: Date.ISO8601FormatStyle(includingFractionalSeconds: true)) { return d }
        return nil
    }
}
