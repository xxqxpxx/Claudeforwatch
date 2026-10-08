import Foundation
import Testing
@testable import ClaudeWatchKit

@Suite struct SSEParserTests {
    static let sample = "event: client_event\nid: 7\ndata: {\"a\":1}\n\n: keep-alive comment\n\nevent: ping\ndata: x\ndata: y\n\n"

    func parseWhole(_ s: String) -> [SSEEvent] {
        var p = SSEParser()
        return p.feed(s) + p.flush()
    }

    @Test func basicFields() {
        let events = parseWhole(Self.sample)
        #expect(events == [
            SSEEvent(event: "client_event", id: "7", data: #"{"a":1}"#),
            SSEEvent(event: "ping", id: "7", data: "x\ny"),
        ])
    }

    @Test func everySplitPointGivesSameEvents() {
        let bytes = Array(Self.sample.utf8)
        let whole = parseWhole(Self.sample)
        for split in 0...bytes.count {
            var p = SSEParser()
            let events = p.feed(bytes: bytes[..<split]) + p.feed(bytes: bytes[split...]) + p.flush()
            #expect(events == whole, "split at \(split)")
        }
    }

    @Test func byteAtATime() {
        var p = SSEParser()
        var events: [SSEEvent] = []
        for b in Self.sample.utf8 { events += p.feed(bytes: [b]) }
        events += p.flush()
        #expect(events == parseWhole(Self.sample))
    }

    @Test func crlfAndCrLineEndings() {
        let lf = parseWhole("event: a\ndata: 1\n\n")
        #expect(parseWhole("event: a\r\ndata: 1\r\n\r\n") == lf)
        #expect(parseWhole("event: a\rdata: 1\r\r") == lf)
    }

    @Test func crlfSplitAcrossChunks() {
        var p = SSEParser()
        var events = p.feed("data: 1\r")
        events += p.feed("\n\r")
        events += p.feed("\ndata: 2\r\n\r\n")
        #expect(events == [SSEEvent(data: "1"), SSEEvent(data: "2")])
    }

    @Test func multibyteUTF8SplitAcrossChunks() {
        let bytes = Array("data: héllo ✓\n\n".utf8)
        var p = SSEParser()
        var events: [SSEEvent] = []
        for i in stride(from: 0, to: bytes.count, by: 1) { events += p.feed(bytes: [bytes[i]]) }
        #expect(events == [SSEEvent(data: "héllo ✓")])
    }

    @Test func commentsAndUnknownFieldsIgnored() {
        #expect(parseWhole(":hi\nfoo: bar\ndata: z\n\n") == [SSEEvent(data: "z")])
        #expect(parseWhole(": only a comment\n\n").isEmpty)
    }

    @Test func fieldParsingDetails() {
        // No space after the colon, a field with no colon, empty data lines.
        #expect(parseWhole("data:x\n\n") == [SSEEvent(data: "x")])
        #expect(parseWhole("data\n\n") == [SSEEvent(data: "")])
        #expect(parseWhole("data: a\ndata:\ndata: b\n\n") == [SSEEvent(data: "a\n\nb")])
        // Only the first space is stripped.
        #expect(parseWhole("data:  two\n\n") == [SSEEvent(data: " two")])
        // Event without data is not dispatched; event name resets.
        #expect(parseWhole("event: x\n\ndata: y\n\n") == [SSEEvent(data: "y")])
    }

    @Test func retryAndIdPersistence() {
        let events = parseWhole("id: 5\nretry: 3000\ndata: a\n\ndata: b\n\nid\ndata: c\n\n")
        #expect(events == [
            SSEEvent(id: "5", data: "a", retry: 3000),
            SSEEvent(id: "5", data: "b"),
            SSEEvent(id: "", data: "c"),
        ])
    }

    @Test func flushDispatchesUnterminatedEvent() {
        var p = SSEParser()
        #expect(p.feed("event: e\ndata: last").isEmpty)
        #expect(p.flush() == [SSEEvent(event: "e", data: "last")])
        #expect(p.flush().isEmpty)
    }

    @Test func leadingBOMStripped() {
        let bytes: [UInt8] = [0xEF, 0xBB, 0xBF] + Array("data: q\n\n".utf8)
        var p = SSEParser()
        #expect(p.feed(bytes: bytes.prefix(2)).isEmpty)
        #expect(p.feed(bytes: bytes.dropFirst(2)) == [SSEEvent(data: "q")])
    }

    @Test func fixturesParseIdenticallyAtAnyChunkSize() throws {
        for name in ["messages_stream.sse", "messages_refusal.sse", "messages_error.sse", "session_events_stream.sse"] {
            let bytes = Array(try Fixtures.fixture(name))
            let whole = try Fixtures.sseEvents(name)
            #expect(!whole.isEmpty)
            for size in [1, 2, 3, 5, 8, 13, 64] {
                var p = SSEParser()
                var events: [SSEEvent] = []
                var i = 0
                while i < bytes.count {
                    events += p.feed(bytes: bytes[i..<min(i + size, bytes.count)])
                    i += size
                }
                events += p.flush()
                #expect(events == whole, "\(name) chunk \(size)")
            }
        }
        let stream = try Fixtures.sseEvents("session_events_stream.sse")
        #expect(stream.count == 8)
        #expect(stream.map(\.event).filter { $0 == "session_update" }.count == 1)
        #expect(stream[0].id == "7")
    }

    @Test func asyncAdapterParsesByteStream() async throws {
        let chunks = ["event: a\nda", "ta: 1\n", "\ndata: 2\n\n"]
        let upstream = AsyncThrowingStream<Data, any Error> { c in
            for ch in chunks { c.yield(Data(ch.utf8)) }
            c.finish()
        }
        var out: [SSEEvent] = []
        for try await e in upstream.sseEvents() { out.append(e) }
        #expect(out == [SSEEvent(event: "a", data: "1"), SSEEvent(data: "2")])
    }
}
