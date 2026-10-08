import Foundation
import Testing
@testable import ClaudeWatchKit

@Suite struct SessionReducerTests {
    @Test func historyFixtureMatchesExpected() throws {
        let frames = try EventFrame.history(from: try Fixtures.fixture("session_events_history.json"))
        #expect(frames.map(\.sequenceNum) == [6, 5, 4, 3, 2, 1]) // desc as received
        var state = TranscriptState()
        state.apply(history: frames)
        let expected = try Fixtures.expected("session_events_history.json")
        #expect(.array(state.items.map(\.expectedShape)) == expected["items"])
        #expect(state.lastSequence == expected["lastSequence"]?.intValue)
        #expect(state.pendingPermission == nil)
        #expect(state.streamingText == nil)
        #expect(state.model == "claude-sonnet-5-5")
        #expect(!state.isTurnInProgress)
    }

    @Test func streamFixtureMatchesExpected() throws {
        let expected = try Fixtures.expected("session_events_stream.json")
        var state = TranscriptState()
        var streamingBefore10: String?
        for sse in try Fixtures.sseEvents("session_events_stream.sse") {
            if sse.event == "client_event", EventFrame(sseData: sse.data)?.sequenceNum == 10 {
                streamingBefore10 = state.streamingText
                // While streaming, the in-progress bubble is the last item.
                #expect(state.items.last?.isStreaming == true)
                #expect(state.items.last?.text == "Committing now.")
            }
            state.apply(sse: sse)
        }
        #expect(.array(state.items.map(\.expectedShape)) == expected["items"])
        #expect(streamingBefore10.map(JSONValue.string) == expected["streamingTextBeforeSeq10"])
        #expect(state.lastSequence == expected["lastSequence"]?.intValue)
        #expect(.array(state.ignoredFrames.map(JSONValue.string)) == expected["ignoredFrames"])
        #expect(state.droppedDuplicates == [try #require(expected["duplicateSequenceDropped"]?.intValue)])
        #expect(state.streamingText == nil)
        #expect(!state.items.contains { $0.isStreaming })

        let pending = try #require(state.pendingPermission)
        let e = try #require(expected["pendingPermission"])
        #expect(.string(pending.requestId) == e["requestId"])
        #expect(.string(pending.tool) == e["tool"])
        #expect(.string(pending.summary) == e["summary"])
        #expect(pending.input == .object(["command": .string("git push -u origin feature")]))
        #expect(!pending.isQuestion)
    }

    @Test func historyThenStreamConcatenates() throws {
        var state = TranscriptState()
        state.apply(history: try EventFrame.history(from: try Fixtures.fixture("session_events_history.json")))
        for sse in try Fixtures.sseEvents("session_events_stream.sse") { state.apply(sse: sse) }
        let h = try Fixtures.expected("session_events_history.json")["items"]!.arrayValue!
        let s = try Fixtures.expected("session_events_stream.json")["items"]!.arrayValue!
        #expect(JSONValue.array(state.items.map(\.expectedShape)) == JSONValue.array(h + s))
        #expect(state.lastSequence == 12)
        // Ids are unique for SwiftUI.
        #expect(Set(state.items.map(\.id)).count == state.items.count)
    }

    @Test func replayingHistoryIsIdempotent() throws {
        let frames = try EventFrame.history(from: try Fixtures.fixture("session_events_history.json"))
        var state = TranscriptState()
        state.apply(history: frames)
        let once = state.items
        state.apply(history: frames)
        #expect(state.items == once)
        #expect(state.droppedDuplicates.count == 6)
    }

    @Test func permissionClearedByResultOrResponse() throws {
        func frame(_ seq: Int, _ payload: String) -> EventFrame {
            EventFrame(sequenceNum: seq, payload: SessionPayload(json: try! JSONValue.parse(payload)))
        }
        let request = #"{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Edit","input":{"file_path":"/a/b.swift","old_string":"x"}}}"#
        var s = TranscriptState()
        s.apply(frame: frame(1, request))
        #expect(s.pendingPermission?.summary == "/a/b.swift")
        s.apply(frame: frame(2, #"{"type":"control_response","response":{"subtype":"success","request_id":"r1","response":{"behavior":"allow"}}}"#))
        #expect(s.pendingPermission == nil)

        s.apply(frame: frame(3, request.replacingOccurrences(of: "r1", with: "r2")))
        #expect(s.pendingPermission?.requestId == "r2")
        s.apply(frame: frame(4, #"{"type":"result","subtype":"error_during_execution","is_error":true,"result":"Boom"}"#))
        #expect(s.pendingPermission == nil)
        #expect(s.items.last == TranscriptItem(id: s.items.last!.id, seq: 4, kind: .turnEnd, text: "Boom", isError: true))

        s.apply(frame: frame(5, request.replacingOccurrences(of: "r1", with: "r3")))
        s.resolvePermission(requestId: "r3")
        #expect(s.pendingPermission == nil)
    }

    @Test func askUserQuestionOptions() throws {
        let payload = #"{"type":"control_request","request_id":"q1","request":{"subtype":"can_use_tool","tool_name":"AskUserQuestion","input":{"questions":[{"question":"Which DB?","header":"DB","multiSelect":false,"options":[{"label":"Postgres","description":"x"},{"label":"SQLite","description":"y"}]}]}}}"#
        var s = TranscriptState()
        s.apply(frame: EventFrame(sequenceNum: 1, payload: SessionPayload(json: try JSONValue.parse(payload))))
        let p = try #require(s.pendingPermission)
        #expect(p.isQuestion)
        #expect(p.summary == "Which DB?")
        #expect(p.questions == [PermissionQuestion(question: "Which DB?", header: "DB", options: ["Postgres", "SQLite"], multiSelect: false)])
    }

    @Test func toolSummaryRules() throws {
        func sum(_ tool: String, _ json: String) throws -> String {
            ToolSummary.summarize(tool: tool, input: try JSONValue.parse(json))
        }
        #expect(try sum("Bash", #"{"description":"d","command":"ls -la\nmore"}"#) == "ls -la")
        #expect(try sum("Read", #"{"limit":5,"file_path":"/x.txt"}"#) == "/x.txt")
        #expect(try sum("Write", #"{"content":"c","file_path":"/y"}"#) == "/y")
        // Generic rule: first string value in document order (not alphabetical).
        #expect(try sum("Grep", #"{"pattern":"TODO","path":"/src"}"#) == "TODO")
        #expect(try sum("WebFetch", #"{"url":"https://x","prompt":"p"}"#) == "https://x")
        #expect(try sum("Task", #"{"n":1,"subagent_type":"general","description":"desc"}"#) == "general")
        #expect(try sum("TodoWrite", #"{"todos":[]}"#) == "")
    }

    @Test func hiddenPayloads() throws {
        var s = TranscriptState()
        let payloads = [
            #"{"type":"tool_progress","tool_use_id":"t","elapsed_ms":1}"#,
            #"{"type":"tool_use_summary","summary":"x"}"#,
            #"{"type":"rate_limit_event"}"#,
            #"{"type":"system","subtype":"status"}"#,
            #"{"type":"control_request","request_id":"x","request":{"subtype":"interrupt"}}"#,
            #"{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"a","content":"ok"}]}}"#,
            #"{"type":"assistant","message":{"role":"assistant","content":[{"type":"thinking","thinking":"hmm"}]}}"#,
            #"{"type":"brand_new_type","foo":1}"#,
        ]
        for (i, p) in payloads.enumerated() {
            s.apply(frame: EventFrame(sequenceNum: i + 1, payload: SessionPayload(json: try JSONValue.parse(p))))
        }
        #expect(s.items.isEmpty)
        #expect(s.lastSequence == payloads.count)
    }

    @Test func systemLinesAndStringContent() throws {
        var s = TranscriptState()
        s.apply(frame: EventFrame(sequenceNum: 1, payload: SessionPayload(json: try JSONValue.parse(#"{"type":"system","subtype":"compact_boundary"}"#))))
        s.apply(frame: EventFrame(sequenceNum: 2, payload: SessionPayload(json: try JSONValue.parse(#"{"type":"user","message":{"role":"user","content":"plain string"}}"#))))
        #expect(s.items.map(\.text) == ["— context compacted —", "plain string"])
    }

    @Test func interruptedStreamKeepsPartialText() throws {
        var s = TranscriptState()
        s.apply(frame: EventFrame(sequenceNum: 1, payload: SessionPayload(json: try JSONValue.parse(#"{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Half"}}}"#))))
        s.apply(frame: EventFrame(sequenceNum: 2, payload: SessionPayload(json: try JSONValue.parse(#"{"type":"result","subtype":"error_during_execution","is_error":false}"#))))
        #expect(s.items.map(\.kind) == [.assistant, .turnEnd])
        #expect(s.items.first?.text == "Half")
        #expect(s.items.first?.isStreaming == false)
    }

    @Test func catchUpTruncatedRequestsRefetch() {
        var s = TranscriptState()
        s.apply(sse: SSEEvent(event: "catch_up_truncated", data: "{}"))
        #expect(s.needsHistoryRefetch)
    }
}

@Suite struct SessionListTests {
    @Test func listFixtureMatchesExpected() throws {
        let list = try JSONDecoder().decode(SessionList.self, from: try Fixtures.fixture("sessions_list.json"))
        #expect(list.resumeToken == "rt_fixture")
        let summary = SessionListReducer.reduce(list.data)
        let expected = try Fixtures.expected("sessions_list.json")
        #expect(.array(summary.visible.map { .string($0.id) }) == expected["visibleOrder"])
        #expect(.array(summary.hidden.map { .string($0.id) }) == expected["hidden"])
        #expect(summary.needsActionCount == expected["needsActionCount"]?.intValue)
        let kinds = try #require(expected["kinds"]?.objectValue)
        for (id, kind) in kinds {
            let session = try #require(list.data.first { $0.id == id })
            #expect(.string(SessionListReducer.kind(of: session).rawValue) == kind)
        }
        let action = try #require(list.data.first { $0.id == "cse_02Action" })
        #expect(action.externalMetadata?.pendingAction?.toolName == "Bash")
        #expect(list.data.first?.externalMetadata?.postTurnSummary == "Logger now uses structured output; tests pass.")
        #expect(SessionListReducer.reduce(list.data, showArchived: true).visible.count == 4)
    }

    @Test func toleratesUnknownAndMissingFields() throws {
        let json = #"{"data":[{"id":"s1","worker_status":"hibernating","brand_new":{"x":1},"last_event_at":"2026-10-08T10:00:00.123Z"},{"id":"s2","last_event_at":"2026-10-08T11:00:00Z","external_metadata":{"post_turn_summary":5}}]}"#
        let list = try JSONDecoder().decode(SessionList.self, from: Data(json.utf8))
        #expect(list.data[0].workerStatus == .other("hibernating"))
        #expect(list.data[1].externalMetadata?.postTurnSummary == nil)
        #expect(SessionListReducer.reduce(list.data).visible.map(\.id) == ["s2", "s1"])
    }
}
