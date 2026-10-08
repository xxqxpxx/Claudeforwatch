import Foundation

/// One dispatched Server-Sent Event.
public struct SSEEvent: Sendable, Equatable {
    /// `event:` field; "message" when absent (WHATWG default).
    public var event: String
    /// Last event ID in effect when this event was dispatched (`id:` field).
    public var id: String?
    /// `data:` lines joined with "\n".
    public var data: String
    /// `retry:` reconnection time in ms, if the event carried one.
    public var retry: Int?

    public init(event: String = "message", id: String? = nil, data: String, retry: Int? = nil) {
        self.event = event
        self.id = id
        self.data = data
        self.retry = retry
    }
}

/// Incremental SSE parser (WHATWG "event stream interpretation").
///
/// A pure byte state machine: feed it chunks exactly as they come off the
/// socket (lines and even CRLF pairs or UTF-8 sequences may be split across
/// chunks) and it returns the events completed by that chunk.
/// Handles `event:`, `id:`, `data:` (multi-line joins), `retry:`, comments
/// (`:` lines), LF, CR and CRLF line endings, and a leading BOM.
public struct SSEParser: Sendable {
    private var line: [UInt8] = []
    private var lastWasCR = false
    private var atStreamStart = true

    private var eventType = ""
    private var dataBuffer = ""
    private var hasData = false
    private var lastEventID: String?
    private var retry: Int?

    public init() {}

    public mutating func feed(_ data: Data) -> [SSEEvent] {
        feed(bytes: data)
    }

    public mutating func feed(_ string: String) -> [SSEEvent] {
        feed(bytes: Array(string.utf8))
    }

    public mutating func feed<S: Sequence>(bytes: S) -> [SSEEvent] where S.Element == UInt8 {
        var out: [SSEEvent] = []
        for byte in bytes {
            if atStreamStart {
                // Strip a UTF-8 BOM (EF BB BF) at the very start of the stream.
                line.append(byte)
                if line == [0xEF] || line == [0xEF, 0xBB] { continue }
                if line == [0xEF, 0xBB, 0xBF] { line.removeAll(); atStreamStart = false; continue }
                atStreamStart = false
                let pending = line
                line.removeAll()
                for b in pending { consume(b, into: &out) }
                continue
            }
            consume(byte, into: &out)
        }
        return out
    }

    /// Call at end of stream. Processes a final unterminated line and
    /// dispatches a pending event. (The WHATWG algorithm would discard an
    /// event not followed by a blank line; servers that close right after the
    /// last `data:` line are common enough that we keep it.)
    public mutating func flush() -> [SSEEvent] {
        var out: [SSEEvent] = []
        if atStreamStart, !line.isEmpty {
            atStreamStart = false
        }
        if !line.isEmpty {
            processLine(line, into: &out)
            line.removeAll()
        }
        dispatch(into: &out)
        lastWasCR = false
        return out
    }

    private mutating func consume(_ byte: UInt8, into out: inout [SSEEvent]) {
        switch byte {
        case 0x0A: // LF
            if lastWasCR {
                lastWasCR = false // second half of CRLF; line already ended
                return
            }
            endLine(into: &out)
        case 0x0D: // CR
            lastWasCR = true
            endLine(into: &out)
        default:
            lastWasCR = false
            line.append(byte)
        }
    }

    private mutating func endLine(into out: inout [SSEEvent]) {
        let current = line
        line.removeAll(keepingCapacity: true)
        if current.isEmpty {
            dispatch(into: &out)
        } else {
            processLine(current, into: &out)
        }
    }

    private mutating func processLine(_ bytes: [UInt8], into out: inout [SSEEvent]) {
        if bytes.first == UInt8(ascii: ":") { return } // comment
        let field: String
        var value: String
        if let colon = bytes.firstIndex(of: UInt8(ascii: ":")) {
            field = String(decoding: bytes[..<colon], as: UTF8.self)
            var valueStart = colon + 1
            if valueStart < bytes.count, bytes[valueStart] == UInt8(ascii: " ") { valueStart += 1 }
            value = String(decoding: bytes[valueStart...], as: UTF8.self)
        } else {
            field = String(decoding: bytes, as: UTF8.self)
            value = ""
        }
        switch field {
        case "event":
            eventType = value
        case "data":
            dataBuffer += value
            dataBuffer += "\n"
            hasData = true
        case "id":
            if !value.contains("\u{0}") { lastEventID = value }
        case "retry":
            if !value.isEmpty, value.allSatisfy({ $0.isASCII && $0.isNumber }), let ms = Int(value) {
                retry = ms
            }
        default:
            value = ""
        }
    }

    private mutating func dispatch(into out: inout [SSEEvent]) {
        defer {
            eventType = ""
            dataBuffer = ""
            hasData = false
            retry = nil
        }
        guard hasData else { return }
        var data = dataBuffer
        if data.hasSuffix("\n") { data.removeLast() }
        out.append(SSEEvent(
            event: eventType.isEmpty ? "message" : eventType,
            id: lastEventID,
            data: data,
            retry: retry
        ))
    }
}

extension AsyncThrowingStream where Element == Data, Failure == any Error {
    /// Parses a raw byte stream into SSE events.
    public func sseEvents() -> AsyncThrowingStream<SSEEvent, any Error> {
        let upstream = self
        return AsyncThrowingStream<SSEEvent, any Error> { continuation in
            let task = Task {
                var parser = SSEParser()
                do {
                    for try await chunk in upstream {
                        for event in parser.feed(chunk) { continuation.yield(event) }
                    }
                    for event in parser.flush() { continuation.yield(event) }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
