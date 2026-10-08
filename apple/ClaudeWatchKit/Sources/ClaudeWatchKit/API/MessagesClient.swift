import Foundation

// MARK: - Constants (docs/PROTOCOL.md §4)

/// Watch-sized system prompt used in every mode.
public let WATCH_SYSTEM_PROMPT = """
You are Claude, answering on a smartwatch. Reply in plain text, no markdown, \
no lists, no code fences. Be brief: one to three short sentences unless the \
user asks for detail. If the user dictated text, tolerate transcription errors.
"""

/// UNOFFICIAL: subscription OAuth tokens are only accepted for inference when
/// the system prompt begins with this line (§4).
public let CLAUDE_CODE_IDENTITY_LINE = "You are Claude Code, Anthropic's official CLI for Claude."

public enum SystemPrompt {
    /// `apiKey`: `WATCH_SYSTEM_PROMPT`. `claudeAccount`: identity line, newline, `WATCH_SYSTEM_PROMPT`.
    public static func forMode(_ mode: AuthMode) -> String {
        switch mode {
        case .apiKey: return WATCH_SYSTEM_PROMPT
        case .claudeAccount: return CLAUDE_CODE_IDENTITY_LINE + "\n" + WATCH_SYSTEM_PROMPT
        }
    }
}

public enum ClaudeModel: String, Codable, Sendable, CaseIterable, Identifiable {
    case haiku5_5 = "claude-haiku-5-5"
    case sonnet5_5 = "claude-sonnet-5-5"
    case opus5_5 = "claude-opus-5-5"

    public static let `default`: ClaudeModel = .haiku5_5
    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .haiku5_5: return "Haiku 5.5"
        case .sonnet5_5: return "Sonnet 5.5"
        case .opus5_5: return "Opus 5.5"
        }
    }

    /// Display name for any model id string (sessions report arbitrary ids).
    public static func displayName(for id: String?) -> String {
        guard let id else { return "" }
        return ClaudeModel(rawValue: id)?.displayName ?? id
    }
}

public enum Effort: String, Codable, Sendable, CaseIterable, Identifiable {
    case low, medium, high
    public static let `default`: Effort = .low
    public var id: String { rawValue }
}

public struct ChatTurn: Sendable, Equatable {
    public var role: ChatRole
    public var text: String

    public init(role: ChatRole, text: String) {
        self.role = role
        self.text = text
    }
}

// MARK: - Stream events

public enum MessagesStreamEvent: Sendable, Equatable {
    /// `message_start`: the model actually serving the reply.
    case started(model: String)
    /// `content_block_delta` / `text_delta`.
    case textDelta(String)
    /// `message_delta`: why generation stopped and how many tokens it used.
    case stop(reason: String?, outputTokens: Int?)
    /// A streamed `error` event.
    case error(type: String, message: String)
}

/// Pure SSE → `MessagesStreamEvent` mapping (no I/O), shared by the client and tests.
public struct MessagesStreamDecoder: Sendable {
    public private(set) var finished = false

    public init() {}

    public mutating func decode(_ event: SSEEvent) -> [MessagesStreamEvent] {
        guard let json = try? JSONValue.parse(event.data) else { return [] }
        let type = json["type"]?.stringValue ?? event.event
        switch type {
        case "message_start":
            if let model = json["message"]?["model"]?.stringValue { return [.started(model: model)] }
            return []
        case "content_block_delta":
            // Only text deltas are shown; thinking/signature/input_json deltas are ignored.
            guard json["delta"]?["type"]?.stringValue == "text_delta",
                  let text = json["delta"]?["text"]?.stringValue else { return [] }
            return [.textDelta(text)]
        case "message_delta":
            let reason = json["delta"]?["stop_reason"]?.stringValue
            let tokens = json["usage"]?["output_tokens"]?.intValue
            return [.stop(reason: reason, outputTokens: tokens)]
        case "message_stop":
            finished = true
            return []
        case "error":
            finished = true
            return [.error(
                type: json["error"]?["type"]?.stringValue ?? "error",
                message: json["error"]?["message"]?.stringValue ?? ""
            )]
        default: // ping, content_block_start/stop, unknown future events
            return []
        }
    }
}

/// The reduced reply, shaped like `spec/expected/messages_*.json`.
public struct MessagesReduction: Sendable, Equatable {
    public var text = ""
    public var stopReason: String?
    public var outputTokens: Int?
    public var model: String?
    public var errorType: String?
    public var errorMessage: String?

    public init() {}

    public mutating func apply(_ event: MessagesStreamEvent) {
        switch event {
        case .started(let m): model = m
        case .textDelta(let t): text += t
        case .stop(let reason, let tokens):
            if let reason { stopReason = reason }
            if let tokens { outputTokens = tokens }
        case .error(let type, let message):
            errorType = type
            errorMessage = message
        }
    }

    public static func reduce(_ events: [MessagesStreamEvent]) -> MessagesReduction {
        var r = MessagesReduction()
        for e in events { r.apply(e) }
        return r
    }

    /// Text to show/store: `max_tokens` appends "…"; a refusal shows the notice.
    public var displayText: String {
        switch stopReason {
        case "max_tokens": return text + "…"
        case "refusal": return text.isEmpty ? MessagesReduction.refusalNotice : text
        default: return text
        }
    }

    public static let refusalNotice = "Claude declined to answer this."
}

extension MessagesStreamEvent {
    /// Reduces any sequence of events (e.g. a collected stream).
    public static func reduce(_ events: [MessagesStreamEvent]) -> MessagesReduction {
        MessagesReduction.reduce(events)
    }
}

// MARK: - Client

/// Streaming chat over `POST /v1/messages` (docs/PROTOCOL.md §4).
public struct MessagesClient: Sendable {
    public static let maxTokens = 400
    public static let historyLimit = 20

    public let transport: AuthorizedTransport
    public let baseURL: URL

    public init(http: any HTTPClient, auth: AuthProvider, baseURL: URL = URL(string: "https://api.anthropic.com")!) {
        self.transport = AuthorizedTransport(http: http, auth: auth)
        self.baseURL = baseURL
    }

    /// The `messages` array: last 20 turns, trimmed from the front; empty turns
    /// dropped; consecutive same-role turns merged; must start with a user turn
    /// and must not end with an assistant turn (no prefill on 5.x models).
    public static func requestMessages(from turns: [ChatTurn]) -> [ChatTurn] {
        var cleaned: [ChatTurn] = []
        for turn in turns {
            let text = turn.text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { continue }
            if let last = cleaned.last, last.role == turn.role {
                cleaned[cleaned.count - 1].text = last.text + "\n\n" + text
            } else {
                cleaned.append(ChatTurn(role: turn.role, text: text))
            }
        }
        while cleaned.last?.role == .assistant { cleaned.removeLast() }
        var trimmed = Array(cleaned.suffix(historyLimit))
        while trimmed.first?.role == .assistant { trimmed.removeFirst() }
        return trimmed
    }

    /// JSON body for the request (exposed for tests).
    public static func requestBody(turns: [ChatTurn], model: ClaudeModel, effort: Effort, mode: AuthMode) -> Data {
        let messages = requestMessages(from: turns).map { turn -> JSONValue in
            .object(["role": .string(turn.role.rawValue), "content": .string(turn.text)])
        }
        let body: JSONObject = [
            "model": .string(model.rawValue),
            "max_tokens": .number(Double(maxTokens)),
            "stream": .bool(true),
            "system": .string(SystemPrompt.forMode(mode)),
            "output_config": .object(["effort": .string(effort.rawValue)]),
            "messages": .array(messages),
        ]
        return JSONValue.object(body).serializedData()
    }

    /// Streams a reply. Throws `APIError` for HTTP failures; a streamed `error`
    /// event is delivered as `.error` and ends the stream. Cancelling the
    /// consumer cancels the request. Never retried (it may have been billed).
    public func stream(
        turns: [ChatTurn],
        model: ClaudeModel = .default,
        effort: Effort = .default
    ) -> AsyncThrowingStream<MessagesStreamEvent, any Error> {
        let transport = self.transport
        let url = baseURL.appendingPathComponent("v1/messages")
        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    guard let mode = try await transport.auth.load().mode else { throw APIError.signedOut }
                    let body = Self.requestBody(turns: turns, model: model, effort: effort, mode: mode)
                    let response = try await transport.openStream(
                        .messages, method: .post, url: url, body: body,
                        extraHeaders: ["Accept": "text/event-stream"]
                    )
                    var decoder = MessagesStreamDecoder()
                    for try await sse in response.body.sseEvents() {
                        for event in decoder.decode(sse) { continuation.yield(event) }
                        if decoder.finished { break }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Streams and collects into a `MessagesReduction`.
    public func reply(
        turns: [ChatTurn],
        model: ClaudeModel = .default,
        effort: Effort = .default
    ) async throws -> MessagesReduction {
        var r = MessagesReduction()
        for try await event in stream(turns: turns, model: model, effort: effort) { r.apply(event) }
        return r
    }

    /// Validates an API key with a 1-token, non-streaming call (docs/PLAN.md §3).
    public static func validationRequest(apiKey: String, userAgent: String = ClientInfo.defaultUserAgent) -> HTTPRequest {
        var headers = AuthProvider.headers(for: .messages, mode: .apiKey, secret: apiKey, organizationUuid: nil, userAgent: userAgent)
        headers["Accept"] = "application/json"
        let body: JSONObject = [
            "model": .string(ClaudeModel.default.rawValue),
            "max_tokens": .number(1),
            "messages": .array([.object(["role": .string("user"), "content": .string("ping")])]),
        ]
        return HTTPRequest(
            method: .post, url: URL(string: "https://api.anthropic.com/v1/messages")!,
            headers: headers, body: JSONValue.object(body).serializedData()
        )
    }

    public static func validate(apiKey: String, http: any HTTPClient) async throws {
        let response = try await http.data(for: validationRequest(apiKey: apiKey))
        guard response.isSuccess else {
            throw APIError.from(status: response.status, headers: response.headers, body: response.body)
        }
    }
}
