import Foundation

/// One content block of a user/assistant message (§5.3).
public enum ContentBlock: Sendable, Equatable {
    case text(String)
    case toolUse(id: String?, name: String, input: JSONValue)
    case thinking(String)
    case toolResult(toolUseId: String?, content: JSONValue, isError: Bool)
    case unknown(type: String)

    public init(json: JSONValue) {
        let type = json["type"]?.stringValue ?? ""
        switch type {
        case "text":
            self = .text(json["text"]?.stringValue ?? "")
        case "tool_use", "server_tool_use":
            self = .toolUse(
                id: json["id"]?.stringValue,
                name: json["name"]?.stringValue ?? "tool",
                input: json["input"] ?? .object(JSONObject())
            )
        case "thinking", "redacted_thinking":
            self = .thinking(json["thinking"]?.stringValue ?? "")
        case "tool_result":
            self = .toolResult(
                toolUseId: json["tool_use_id"]?.stringValue,
                content: json["content"] ?? .null,
                isError: json["is_error"]?.boolValue ?? false
            )
        default:
            self = .unknown(type: type)
        }
    }

    /// `message.content` may be a string or an array of blocks.
    static func blocks(from content: JSONValue?) -> [ContentBlock] {
        switch content {
        case .string(let s)?: return [.text(s)]
        case .array(let a)?: return a.map(ContentBlock.init(json:))
        default: return []
        }
    }
}

public struct MessagePayload: Sendable, Equatable {
    public var uuid: String?
    public var role: String?
    public var model: String?
    public var content: [ContentBlock]
}

public struct ResultPayload: Sendable, Equatable {
    public var uuid: String?
    public var subtype: String?
    public var isError: Bool
    public var result: String?
    public var stopReason: String?
}

public struct SystemPayload: Sendable, Equatable {
    public var uuid: String?
    public var subtype: String?
    public var model: String?
}

/// `stream_event`: a raw Messages API stream event relayed by the worker.
public struct StreamEventPayload: Sendable, Equatable {
    public var uuid: String?
    public var event: JSONValue

    /// The text of a `content_block_delta` / `text_delta`, if this is one.
    public var textDelta: String? {
        guard event["type"]?.stringValue == "content_block_delta",
              event["delta"]?["type"]?.stringValue == "text_delta" else { return nil }
        return event["delta"]?["text"]?.stringValue
    }

    public var eventType: String? { event["type"]?.stringValue }
}

/// An incoming `control_request` (§5.5), e.g. a `can_use_tool` permission prompt.
public struct ControlRequestPayload: Sendable, Equatable {
    public var uuid: String?
    public var requestId: String
    public var subtype: String
    public var toolName: String?
    public var input: JSONValue
    public var description: String?
    public var raw: JSONValue
}

public struct ControlResponsePayload: Sendable, Equatable {
    public var requestId: String?
    public var subtype: String?
}

/// `payload` of a session event, decoded by its `type` field.
public enum SessionPayload: Sendable, Equatable {
    case user(MessagePayload)
    case assistant(MessagePayload)
    case streamEvent(StreamEventPayload)
    case result(ResultPayload)
    case system(SystemPayload)
    case controlRequest(ControlRequestPayload)
    case controlResponse(ControlResponsePayload)
    case controlCancelRequest(requestId: String?)
    /// `tool_progress`, `tool_use_summary`, `rate_limit_event` and anything new.
    case unknown(type: String, raw: JSONValue)

    public var type: String {
        switch self {
        case .user: return "user"
        case .assistant: return "assistant"
        case .streamEvent: return "stream_event"
        case .result: return "result"
        case .system: return "system"
        case .controlRequest: return "control_request"
        case .controlResponse: return "control_response"
        case .controlCancelRequest: return "control_cancel_request"
        case .unknown(let t, _): return t
        }
    }

    public init(json: JSONValue) {
        let type = json["type"]?.stringValue ?? ""
        let uuid = json["uuid"]?.stringValue
        switch type {
        case "user", "assistant":
            let message = json["message"]
            let payload = MessagePayload(
                uuid: uuid,
                role: message?["role"]?.stringValue ?? type,
                model: message?["model"]?.stringValue,
                content: ContentBlock.blocks(from: message?["content"])
            )
            self = type == "user" ? .user(payload) : .assistant(payload)
        case "stream_event":
            self = .streamEvent(StreamEventPayload(uuid: uuid, event: json["event"] ?? .null))
        case "result":
            self = .result(ResultPayload(
                uuid: uuid,
                subtype: json["subtype"]?.stringValue,
                isError: json["is_error"]?.boolValue ?? false,
                result: json["result"]?.stringValue,
                stopReason: json["stop_reason"]?.stringValue
            ))
        case "system":
            self = .system(SystemPayload(
                uuid: uuid, subtype: json["subtype"]?.stringValue, model: json["model"]?.stringValue
            ))
        case "control_request":
            let request = json["request"]
            self = .controlRequest(ControlRequestPayload(
                uuid: uuid,
                requestId: json["request_id"]?.stringValue ?? "",
                subtype: request?["subtype"]?.stringValue ?? "",
                toolName: request?["tool_name"]?.stringValue,
                input: request?["input"] ?? .object(JSONObject()),
                description: request?["description"]?.stringValue,
                raw: json
            ))
        case "control_response":
            let response = json["response"]
            self = .controlResponse(ControlResponsePayload(
                requestId: response?["request_id"]?.stringValue ?? json["request_id"]?.stringValue,
                subtype: response?["subtype"]?.stringValue
            ))
        case "control_cancel_request":
            self = .controlCancelRequest(requestId: json["request_id"]?.stringValue)
        default:
            self = .unknown(type: type, raw: json)
        }
    }
}

extension SessionPayload: Decodable {
    public init(from decoder: any Decoder) throws {
        self.init(json: try JSONValue(from: decoder))
    }
}

/// One `{sequence_num, payload}` record from history or a `client_event` SSE frame (§5.2).
public struct EventFrame: Sendable, Equatable {
    /// Integer sequence number (the wire sends a string).
    public var sequenceNum: Int
    public var eventType: String?
    public var source: String?
    public var payload: SessionPayload

    public init(sequenceNum: Int, eventType: String? = "client_event", source: String? = nil, payload: SessionPayload) {
        self.sequenceNum = sequenceNum
        self.eventType = eventType
        self.source = source
        self.payload = payload
    }

    /// `nil` if the record has no usable sequence number or payload.
    public init?(json: JSONValue) {
        guard let seq = json["sequence_num"]?.intValue, let payload = json["payload"], payload.objectValue != nil else {
            return nil
        }
        self.init(
            sequenceNum: seq,
            eventType: json["event_type"]?.stringValue,
            source: json["source"]?.stringValue,
            payload: SessionPayload(json: payload)
        )
    }

    /// Parses a `data:` line of the events stream.
    public init?(sseData: String) {
        guard let json = try? JSONValue.parse(sseData) else { return nil }
        self.init(json: json)
    }

    /// Parses `GET …/events` (`{data:[…]}`), in the order received.
    public static func history(from data: Data) throws -> [EventFrame] {
        let json = try JSONValue.parse(data)
        guard let items = json["data"]?.arrayValue else { throw APIError.malformedResponse }
        return items.compactMap(EventFrame.init(json:))
    }
}

extension EventFrame: Decodable {
    public init(from decoder: any Decoder) throws {
        let json = try JSONValue(from: decoder)
        guard let frame = EventFrame(json: json) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Not an event frame"))
        }
        self = frame
    }
}
