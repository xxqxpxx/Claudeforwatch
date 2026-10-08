import Foundation

/// Claude Code sessions (cloud and Remote Control), docs/PROTOCOL.md §5.
///
/// UNOFFICIAL: this is the private API claude.ai/code and the Claude mobile
/// app use. claudeAccount mode only; may change or be blocked at any time.
public struct SessionsClient: Sendable {
    public enum PermissionMode: String, Sendable, CaseIterable, Identifiable {
        case `default`, acceptEdits, plan, bypassPermissions
        public var id: String { rawValue }
        public var displayName: String {
            switch self {
            case .default: return "Ask"
            case .acceptEdits: return "Accept edits"
            case .plan: return "Plan"
            case .bypassPermissions: return "Bypass"
            }
        }
    }

    /// One-tap replies (§5.4).
    public static let quickReplies = [
        "Continue", "Looks good, proceed", "Run the tests", "Stop", "Explain what you're doing", "Commit and push",
    ]

    public static let historyLimit = 60
    public static let denyMessage = "User denied from watch"

    public let transport: AuthorizedTransport
    public let baseURL: URL
    private let makeUUID: @Sendable () -> String
    private let nowMillis: @Sendable () -> Int64

    public init(
        http: any HTTPClient,
        auth: AuthProvider,
        baseURL: URL = URL(string: "https://api.anthropic.com")!,
        makeUUID: @escaping @Sendable () -> String = { UUID().uuidString.lowercased() },
        nowMillis: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.transport = AuthorizedTransport(http: http, auth: auth)
        self.baseURL = baseURL
        self.makeUUID = makeUUID
        self.nowMillis = nowMillis
    }

    // MARK: URLs

    func url(_ path: String, query: [(String, String)] = []) -> URL {
        var s = baseURL.absoluteString
        if s.hasSuffix("/") { s.removeLast() }
        s += path
        if !query.isEmpty {
            s += "?" + query.map { "\($0.0)=\($0.1.strictlyPercentEncoded)" }.joined(separator: "&")
        }
        return URL(string: s)!
    }

    func sessionPath(_ id: String, _ suffix: String = "") -> String {
        "/v1/code/sessions/\(id.strictlyPercentEncoded)\(suffix)"
    }

    // MARK: Read

    /// `GET /v1/code/sessions` — note: no `anthropic-beta` on this one (§5.1).
    public func list() async throws -> SessionList {
        let r = try await transport.send(.sessionsList, method: .get, url: url("/v1/code/sessions"))
        do { return try JSONDecoder().decode(SessionList.self, from: r.body) } catch { throw APIError.malformedResponse }
    }

    public func get(id: String) async throws -> Session {
        let r = try await transport.send(.sessions, method: .get, url: url(sessionPath(id)))
        let json = try? JSONValue.parse(r.body)
        // Some deployments wrap the object in `{data: …}`.
        let body = json?["data"]?.objectValue != nil ? json!["data"]!.serializedData() : r.body
        do { return try JSONDecoder().decode(Session.self, from: body) } catch { throw APIError.malformedResponse }
    }

    /// `GET …/events?limit=60&sort_order=desc`, returned in server order (newest
    /// first). `TranscriptState.apply(history:)` sorts.
    public func history(id: String, limit: Int = historyLimit) async throws -> [EventFrame] {
        let r = try await transport.send(
            .sessions, method: .get,
            url: url(sessionPath(id, "/events"), query: [("limit", String(limit)), ("sort_order", "desc")])
        )
        return try EventFrame.history(from: r.body)
    }

    /// The request for the live stream (exposed for tests).
    func streamURL(id: String, fromSequence: Int?) -> URL {
        url(sessionPath(id, "/events/stream"), query: fromSequence.map { [("from_sequence_num", String($0))] } ?? [])
    }

    /// Live events as raw SSE events (feed them to `TranscriptState.apply(sse:)`).
    /// Resumes after `fromSequence` via `from_sequence_num` + `Last-Event-ID`.
    /// The stream ends when the server closes it; the caller reconnects from
    /// `TranscriptState.lastSequence` when the view is visible again (§5.2).
    public func events(id: String, fromSequence: Int?) -> AsyncThrowingStream<SSEEvent, any Error> {
        let transport = self.transport
        let url = streamURL(id: id, fromSequence: fromSequence)
        var extra = ["Accept": "text/event-stream", "Cache-Control": "no-cache"]
        if let fromSequence { extra["Last-Event-ID"] = String(fromSequence) }
        let headers = extra
        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let response = try await transport.openStream(.sessions, method: .get, url: url, extraHeaders: headers)
                    for try await event in response.body.sseEvents() { continuation.yield(event) }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    // MARK: Write (§5.4–§5.6)

    /// Body for `POST …/events`: `{"session_id":…,"events":[{"payload":…}]}`.
    public static func eventsBody(sessionID: String, payload: JSONValue) -> Data {
        JSONValue.object([
            "session_id": .string(sessionID),
            "events": .array([.object(["payload": payload])]),
        ]).serializedData()
    }

    public func userMessagePayload(text: String) -> JSONValue {
        .object([
            "type": .string("user"),
            "uuid": .string(makeUUID()),
            "message": .object([
                "role": .string("user"),
                "content": .array([.object(["type": .string("text"), "text": .string(text)])]),
            ]),
        ])
    }

    /// Sends a user turn. Slash commands (`/compact`) are plain text.
    public func send(text: String, to id: String) async throws {
        try await post(id, payload: userMessagePayload(text: text))
    }

    public func controlRequestPayload(_ request: JSONObject) -> JSONValue {
        .object([
            "type": .string("control_request"),
            "request_id": .string(makeUUID()),
            "request": .object(request),
        ])
    }

    public func interrupt(_ id: String) async throws {
        try await post(id, payload: controlRequestPayload(["subtype": .string("interrupt")]))
    }

    public func setModel(_ model: String, for id: String) async throws {
        try await post(id, payload: controlRequestPayload(["subtype": .string("set_model"), "model": .string(model)]))
    }

    public func setPermissionMode(_ mode: PermissionMode, for id: String) async throws {
        try await post(id, payload: controlRequestPayload([
            "subtype": .string("set_permission_mode"), "mode": .string(mode.rawValue),
        ]))
    }

    /// `control_response` payload for a `can_use_tool` prompt (§5.5).
    public static func permissionResponsePayload(requestId: String, allow: Bool, input: JSONValue) -> JSONValue {
        let inner: JSONValue = allow
            ? .object(["behavior": .string("allow"), "updatedInput": input])
            : .object(["behavior": .string("deny"), "message": .string(denyMessage)])
        return .object([
            "type": .string("control_response"),
            "response": .object([
                "subtype": .string("success"),
                "request_id": .string(requestId),
                "response": inner,
            ]),
        ])
    }

    public func respond(to permission: PendingPermission, allow: Bool, in id: String) async throws {
        try await post(id, payload: Self.permissionResponsePayload(
            requestId: permission.requestId, allow: allow, input: permission.input
        ))
    }

    public func markRead(_ id: String) async throws {
        _ = try await transport.send(.sessions, method: .post, url: url(sessionPath(id, "/mark_read")), body: Data("{}".utf8))
    }

    /// 200 or 409 (already archived) both count as success.
    public func archive(_ id: String) async throws {
        _ = try await transport.send(
            .sessions, method: .post, url: url(sessionPath(id, "/archive")), body: Data("{}".utf8), acceptStatuses: [409]
        )
    }

    /// Best-effort presence (`connected_at` on open, `clear` on close).
    public func presence(_ id: String, clientID: String, connected: Bool) async throws {
        let body: JSONValue = connected
            ? .object(["client_id": .string(clientID), "connected_at": .number(Double(nowMillis()))])
            : .object(["client_id": .string(clientID), "clear": .bool(true)])
        _ = try await transport.send(
            .sessions, method: .post, url: url(sessionPath(id, "/client/presence")), body: body.serializedData()
        )
    }

    private func post(_ id: String, payload: JSONValue) async throws {
        _ = try await transport.send(
            .sessions, method: .post, url: url(sessionPath(id, "/events")),
            body: Self.eventsBody(sessionID: id, payload: payload)
        )
    }
}
