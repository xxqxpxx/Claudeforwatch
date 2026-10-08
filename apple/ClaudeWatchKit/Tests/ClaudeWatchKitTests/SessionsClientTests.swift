import Foundation
import Testing
@testable import ClaudeWatchKit

@Suite struct SessionsClientTests {
    func makeClient(_ handler: @escaping StubHTTPClient.Handler) -> (SessionsClient, StubHTTPClient) {
        let stub = StubHTTPClient(chunkSize: 5, handler: handler)
        let auth = AuthProvider(store: InMemoryTokenStore(fixtureOAuthCredentials), oauth: OAuthClient(http: stub), userAgent: "UA")
        let ids = SequentialIDs()
        return (SessionsClient(http: stub, auth: auth, makeUUID: { ids.next() }, nowMillis: { 1_760_000_000_123 }), stub)
    }

    @Test func listUsesNoBetaHeader() async throws {
        let body = try Fixtures.fixture("sessions_list.json")
        let (client, stub) = makeClient { _ in .init(status: 200, body: body) }
        let list = try await client.list()
        #expect(list.data.count == 4)
        let req = try #require(await stub.requests.first)
        #expect(req.method == .get)
        #expect(req.url.absoluteString == "https://api.anthropic.com/v1/code/sessions")
        #expect(req.header("anthropic-beta") == nil)
        #expect(req.header("anthropic-client-platform") == "web_claude_ai")
        #expect(req.header("x-organization-uuid") == "org-uuid-fixture")
        #expect(req.header("Authorization") == "Bearer sk-ant-oat01-old")
    }

    @Test func historyRequestAndParse() async throws {
        let body = try Fixtures.fixture("session_events_history.json")
        let (client, stub) = makeClient { _ in .init(status: 200, body: body) }
        let frames = try await client.history(id: "session_01Idle")
        #expect(frames.count == 6)
        let req = try #require(await stub.requests.first)
        #expect(req.url.absoluteString == "https://api.anthropic.com/v1/code/sessions/session_01Idle/events?limit=60&sort_order=desc")
        #expect(req.header("anthropic-beta") == "ccr-byoc-2025-07-29")
    }

    @Test func liveStreamResumesFromSequence() async throws {
        let body = try Fixtures.fixture("session_events_stream.sse")
        let (client, stub) = makeClient { _ in .init(status: 200, body: body) }
        var state = TranscriptState()
        state.apply(history: try EventFrame.history(from: try Fixtures.fixture("session_events_history.json")))
        for try await sse in client.events(id: "cse_02Action", fromSequence: state.lastSequence) {
            state.apply(sse: sse)
        }
        #expect(state.lastSequence == 12)
        #expect(state.pendingPermission?.requestId == "req_fixture_1")
        let req = try #require(await stub.requests.first)
        #expect(req.url.absoluteString == "https://api.anthropic.com/v1/code/sessions/cse_02Action/events/stream?from_sequence_num=6")
        #expect(req.header("Last-Event-ID") == "6")
        #expect(req.header("Accept") == "text/event-stream")
        #expect(req.header("anthropic-beta") == "ccr-byoc-2025-07-29")
    }

    @Test func sendUserText() async throws {
        let (client, stub) = makeClient { _ in .init(status: 200, text: "{}") }
        try await client.send(text: "Run the tests", to: "session_X")
        let req = try #require(await stub.requests.first)
        #expect(req.method == .post)
        #expect(req.url.absoluteString == "https://api.anthropic.com/v1/code/sessions/session_X/events")
        let expected = try JSONValue.parse(#"{"session_id":"session_X","events":[{"payload":{"type":"user","uuid":"uuid-1","message":{"role":"user","content":[{"type":"text","text":"Run the tests"}]}}}]}"#)
        #expect(req.jsonBody == expected)
    }

    @Test func controlRequests() async throws {
        let (client, stub) = makeClient { _ in .init(status: 200, text: "{}") }
        try await client.interrupt("s")
        try await client.setPermissionMode(.acceptEdits, for: "s")
        try await client.setModel("claude-sonnet-5-5", for: "s")
        let payloads = await stub.requests.map { $0.jsonBody?["events"]?.arrayValue?.first?["payload"] }
        #expect(payloads == [
            try JSONValue.parse(#"{"type":"control_request","request_id":"uuid-1","request":{"subtype":"interrupt"}}"#),
            try JSONValue.parse(#"{"type":"control_request","request_id":"uuid-2","request":{"subtype":"set_permission_mode","mode":"acceptEdits"}}"#),
            try JSONValue.parse(#"{"type":"control_request","request_id":"uuid-3","request":{"subtype":"set_model","model":"claude-sonnet-5-5"}}"#),
        ])
    }

    @Test func permissionResponses() async throws {
        let (client, stub) = makeClient { _ in .init(status: 200, text: "{}") }
        let input = try JSONValue.parse(#"{"command":"git push -u origin feature","timeout":5}"#)
        let permission = PendingPermission(requestId: "req_fixture_1", tool: "Bash", summary: "git push", description: nil, input: input, questions: [])
        try await client.respond(to: permission, allow: true, in: "s")
        try await client.respond(to: permission, allow: false, in: "s")
        let payloads = await stub.requests.map { $0.jsonBody?["events"]?.arrayValue?.first?["payload"] }
        #expect(payloads[0] == (try JSONValue.parse(#"{"type":"control_response","response":{"subtype":"success","request_id":"req_fixture_1","response":{"behavior":"allow","updatedInput":{"command":"git push -u origin feature","timeout":5}}}}"#)))
        #expect(payloads[1] == (try JSONValue.parse(#"{"type":"control_response","response":{"subtype":"success","request_id":"req_fixture_1","response":{"behavior":"deny","message":"User denied from watch"}}}"#)))
        // updatedInput is echoed byte-for-byte in original key order.
        let raw = String(decoding: await stub.requests[0].body!, as: UTF8.self)
        #expect(raw.contains(#""updatedInput":{"command":"git push -u origin feature","timeout":5}"#))
    }

    @Test func markReadArchivePresence() async throws {
        let (client, stub) = makeClient { req in
            req.url.path.hasSuffix("/archive") ? .init(status: 409, text: "{}") : .init(status: 200, text: "{}")
        }
        try await client.markRead("s")
        try await client.archive("s") // 409 counts as success
        try await client.presence("s", clientID: "install-1", connected: true)
        try await client.presence("s", clientID: "install-1", connected: false)
        let reqs = await stub.requests
        #expect(reqs.map(\.url.path) == [
            "/v1/code/sessions/s/mark_read", "/v1/code/sessions/s/archive",
            "/v1/code/sessions/s/client/presence", "/v1/code/sessions/s/client/presence",
        ])
        #expect(reqs[0].jsonBody == .object([:]))
        #expect(reqs[1].jsonBody == .object([:]))
        #expect(reqs[2].jsonBody == (try JSONValue.parse(#"{"client_id":"install-1","connected_at":1760000000123}"#)))
        #expect(reqs[3].jsonBody == (try JSONValue.parse(#"{"client_id":"install-1","clear":true}"#)))
        #expect(reqs.allSatisfy { $0.header("anthropic-beta") == "ccr-byoc-2025-07-29" })
    }

    @Test func trustedDeviceError() async throws {
        let (client, _) = makeClient { _ in .init(status: 403, text: #"{"error":{"type":"permission_error","message":"Trusted device required"}}"#) }
        await #expect(throws: APIError.trustedDeviceRequired) { _ = try await client.list() }
        #expect(APIError.trustedDeviceRequired.userMessage == "This organization requires Trusted Devices; enroll from claude.ai")
    }

    @Test func apiKeyModeCannotListSessions() async throws {
        let stub = StubHTTPClient(queue: [])
        let auth = AuthProvider(store: InMemoryTokenStore(.apiKey("k")), oauth: OAuthClient(http: stub))
        await #expect(throws: APIError.notAvailableInMode) { _ = try await SessionsClient(http: stub, auth: auth).list() }
        #expect(await stub.requestCount == 0)
    }
}

@Suite struct UsageAndRoutinesTests {
    @Test func usageAndProfileDecode() throws {
        let usage = try JSONDecoder().decode(Usage.self, from: Data(#"{"five_hour":{"utilization":12.6,"resets_at":"2026-10-08T12:00:00Z"},"seven_day":{"utilization":55,"resets_at":null},"extra":1}"#.utf8))
        #expect(usage.headlinePercent == 55)
        let profile = try JSONDecoder().decode(Profile.self, from: Data(#"{"account":{"email_address":"you@example.com","uuid":"a"},"organization":{"uuid":"o","name":"Org"}}"#.utf8))
        #expect(profile.account?.emailAddress == "you@example.com")
        #expect(profile.organization?.uuid == "o")
    }

    @Test func routineFire() async throws {
        let stub = StubHTTPClient(queue: [.init(status: 200, text: #"{"claude_code_session_id":"session_new","claude_code_session_url":"https://claude.ai/code/session_new"}"#)])
        let client = RoutinesClient(http: stub, userAgent: "UA")
        let result = try await client.fire(triggerID: "trig_abc", token: "sk-ant-oat01-routine", text: "Fix the build")
        #expect(result.claudeCodeSessionId == "session_new")
        let req = try #require(await stub.requests.first)
        #expect(req.url.absoluteString == "https://api.anthropic.com/v1/claude_code/routines/trig_abc/fire")
        #expect(req.header("Authorization") == "Bearer sk-ant-oat01-routine")
        #expect(req.header("anthropic-version") == "2023-06-01")
        #expect(req.jsonBody == .object(["text": .string("Fix the build")]))
        #expect(throws: RoutinesClient.RoutineError.invalidTriggerID) {
            _ = try client.fireRequest(triggerID: "../evil", token: "t", text: "x")
        }
    }
}
