import Foundation

/// A row in the transcript UI.
public struct TranscriptItem: Sendable, Equatable, Identifiable {
    public enum Kind: String, Sendable, Codable {
        case system, user, assistant, tool, turnEnd, permission
    }

    public var id: String
    public var seq: Int
    public var kind: Kind
    public var text: String?
    public var tool: String?
    public var requestId: String?
    public var isError: Bool?
    /// True only for the in-progress assistant text built from `stream_event` deltas.
    public var isStreaming: Bool

    public init(
        id: String, seq: Int, kind: Kind, text: String? = nil, tool: String? = nil,
        requestId: String? = nil, isError: Bool? = nil, isStreaming: Bool = false
    ) {
        self.id = id
        self.seq = seq
        self.kind = kind
        self.text = text
        self.tool = tool
        self.requestId = requestId
        self.isError = isError
        self.isStreaming = isStreaming
    }
}

public struct PermissionQuestion: Sendable, Equatable {
    public var question: String
    public var header: String?
    public var options: [String]
    public var multiSelect: Bool
}

/// A `can_use_tool` prompt waiting for Allow/Deny (§5.5).
public struct PendingPermission: Sendable, Equatable, Identifiable {
    public var id: String { requestId }
    public var requestId: String
    public var tool: String
    public var summary: String
    public var description: String?
    /// Echoed back unchanged as `updatedInput` on Allow.
    public var input: JSONValue
    /// Non-empty for `AskUserQuestion`: render the options as buttons and send
    /// the chosen label as a normal user message.
    public var questions: [PermissionQuestion]

    public var isQuestion: Bool { tool == "AskUserQuestion" }
}

/// Reduces session events into transcript items (docs/PROTOCOL.md §5.2–§5.5,
/// spec/README.md). Pure value type; the view model owns one per open session.
public struct TranscriptState: Sendable, Equatable {
    public private(set) var finalItems: [TranscriptItem] = []
    public private(set) var lastSequence: Int?
    public private(set) var pendingPermission: PendingPermission?
    /// Frames ignored because their SSE event was not `client_event`.
    public private(set) var ignoredFrames: [String] = []
    /// Sequence numbers dropped as duplicates.
    public private(set) var droppedDuplicates: [Int] = []
    /// Set by `catch_up_truncated`; the owner should refetch history.
    public var needsHistoryRefetch = false
    /// From the last `system/init` or assistant message.
    public private(set) var model: String?
    /// True between a user message and the `result` that ends the turn.
    public private(set) var isTurnInProgress = false

    private var streaming: (seq: Int, text: String)?

    public init() {}

    public static func == (lhs: TranscriptState, rhs: TranscriptState) -> Bool {
        lhs.finalItems == rhs.finalItems && lhs.lastSequence == rhs.lastSequence
            && lhs.pendingPermission == rhs.pendingPermission && lhs.streamingText == rhs.streamingText
            && lhs.needsHistoryRefetch == rhs.needsHistoryRefetch && lhs.isTurnInProgress == rhs.isTurnInProgress
    }

    /// Items to render: final items plus the streaming assistant text, if any.
    public var items: [TranscriptItem] {
        guard let streaming else { return finalItems }
        return finalItems + [TranscriptItem(
            id: "stream-\(streaming.seq)", seq: streaming.seq, kind: .assistant,
            text: streaming.text, isStreaming: true
        )]
    }

    /// The in-progress assistant text from `stream_event` deltas, if any.
    public var streamingText: String? { streaming?.text }

    // MARK: Applying

    /// Applies `GET …/events` results. They arrive `sort_order=desc`; they are
    /// applied in ascending sequence order regardless of input order.
    public mutating func apply(history frames: [EventFrame]) {
        for frame in frames.sorted(by: { $0.sequenceNum < $1.sequenceNum }) {
            apply(frame: frame)
        }
    }

    /// Applies one SSE frame from `…/events/stream`.
    public mutating func apply(sse: SSEEvent) {
        guard sse.event == "client_event" else {
            if sse.event == "catch_up_truncated" { needsHistoryRefetch = true }
            ignoredFrames.append(sse.event)
            return
        }
        guard let frame = EventFrame(sseData: sse.data) else { return }
        apply(frame: frame)
    }

    /// Applies one event record. Returns false if it was dropped as a duplicate
    /// or ignored.
    @discardableResult
    public mutating func apply(frame: EventFrame) -> Bool {
        if let type = frame.eventType, type != "client_event" {
            if type == "catch_up_truncated" { needsHistoryRefetch = true }
            ignoredFrames.append(type)
            return false
        }
        // De-dupe by integer sequence number; sequence numbers only grow.
        if let last = lastSequence, frame.sequenceNum <= last {
            droppedDuplicates.append(frame.sequenceNum)
            return false
        }
        lastSequence = frame.sequenceNum
        reduce(frame.payload, seq: frame.sequenceNum)
        return true
    }

    /// Call after the app answered a permission prompt.
    public mutating func resolvePermission(requestId: String) {
        if pendingPermission?.requestId == requestId { pendingPermission = nil }
    }

    /// Clears everything (before a full history refetch).
    public mutating func reset() {
        self = TranscriptState()
    }

    // MARK: Reduction (§5.3)

    private mutating func reduce(_ payload: SessionPayload, seq: Int) {
        switch payload {
        case .user(let message):
            let hasNonToolResult = message.content.contains { block in
                if case .toolResult = block { return false }
                return true
            }
            // tool_result-only user turns are hidden (they're tool output plumbing).
            guard hasNonToolResult else { return }
            let text = message.content.compactMap { block -> String? in
                if case .text(let t) = block { return t }
                return nil
            }.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { return }
            finalizeStreaming()
            isTurnInProgress = true
            append(seq: seq, kind: .user, text: text)

        case .assistant(let message):
            if let m = message.model { model = m }
            let hasText = message.content.contains { if case .text = $0 { return true }; return false }
            // The final assistant payload replaces the streamed text.
            if hasText { streaming = nil }
            for block in message.content {
                switch block {
                case .text(let t):
                    let trimmed = t.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !trimmed.isEmpty { append(seq: seq, kind: .assistant, text: trimmed) }
                case .toolUse(_, let name, let input):
                    append(seq: seq, kind: .tool, text: ToolSummary.summarize(tool: name, input: input), tool: name)
                case .thinking, .toolResult, .unknown:
                    break
                }
            }

        case .streamEvent(let event):
            if let delta = event.textDelta {
                if streaming == nil { streaming = (seq, "") }
                streaming?.text += delta
            }

        case .result(let result):
            finalizeStreaming()
            isTurnInProgress = false
            pendingPermission = nil
            var item = TranscriptItem(id: "\(seq)-\(finalItems.count)", seq: seq, kind: .turnEnd, isError: result.isError)
            if result.isError { item.text = result.result ?? result.subtype ?? "Error" }
            finalItems.append(item)

        case .system(let system):
            switch system.subtype {
            case "init":
                if let m = system.model { model = m }
                let text = system.model.map { "Session started (\($0))" } ?? "Session started"
                append(seq: seq, kind: .system, text: text)
            case "compact_boundary":
                append(seq: seq, kind: .system, text: "— context compacted —")
            default:
                break
            }

        case .controlRequest(let request):
            guard request.subtype == "can_use_tool" else { return }
            let tool = request.toolName ?? "tool"
            let summary = ToolSummary.summarize(tool: tool, input: request.input)
            append(seq: seq, kind: .permission, text: summary, tool: tool, requestId: request.requestId)
            pendingPermission = PendingPermission(
                requestId: request.requestId,
                tool: tool,
                summary: summary,
                description: request.description,
                input: request.input,
                questions: Self.questions(from: request.input, tool: tool)
            )

        case .controlResponse(let response):
            if response.requestId == nil || response.requestId == pendingPermission?.requestId {
                pendingPermission = nil
            }

        case .controlCancelRequest(let requestId):
            if requestId == nil || requestId == pendingPermission?.requestId { pendingPermission = nil }

        case .unknown:
            break
        }
    }

    private mutating func append(seq: Int, kind: TranscriptItem.Kind, text: String?, tool: String? = nil, requestId: String? = nil) {
        finalItems.append(TranscriptItem(
            id: "\(seq)-\(finalItems.count)", seq: seq, kind: kind, text: text, tool: tool, requestId: requestId
        ))
    }

    /// Keeps streamed text that never got a final assistant payload (e.g. an
    /// interrupted turn) as a normal assistant bubble.
    private mutating func finalizeStreaming() {
        guard let s = streaming else { return }
        streaming = nil
        let trimmed = s.text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { append(seq: s.seq, kind: .assistant, text: trimmed) }
    }

    static func questions(from input: JSONValue, tool: String) -> [PermissionQuestion] {
        guard tool == "AskUserQuestion", let qs = input["questions"]?.arrayValue else { return [] }
        return qs.compactMap { q in
            guard let text = q["question"]?.stringValue else { return nil }
            let options = (q["options"]?.arrayValue ?? []).compactMap { opt in
                opt["label"]?.stringValue ?? opt.stringValue
            }
            return PermissionQuestion(
                question: text, header: q["header"]?.stringValue,
                options: options, multiSelect: q["multiSelect"]?.boolValue ?? false
            )
        }
    }
}
