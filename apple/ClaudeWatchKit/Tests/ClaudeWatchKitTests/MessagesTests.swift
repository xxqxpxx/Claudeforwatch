import Foundation
import Testing
@testable import ClaudeWatchKit

@Suite struct MessagesTests {
    func reduceFixture(_ name: String) throws -> MessagesReduction {
        var decoder = MessagesStreamDecoder()
        let events = try Fixtures.sseEvents(name).flatMap { decoder.decode($0) }
        return MessagesStreamEvent.reduce(events)
    }

    func assertMatches(_ r: MessagesReduction, expected name: String) throws {
        let e = try Fixtures.expected(name)
        if let text = e["text"] { #expect(.string(r.text) == text) }
        if let v = e["stopReason"] { #expect(r.stopReason.map(JSONValue.string) ?? .null == v) }
        if let v = e["outputTokens"] { #expect(r.outputTokens.map { JSONValue.number(Double($0)) } ?? .null == v) }
        if let v = e["model"] { #expect(r.model.map(JSONValue.string) ?? .null == v) }
        if let v = e["errorType"] { #expect(r.errorType.map(JSONValue.string) ?? .null == v) }
        if let v = e["errorMessage"] { #expect(r.errorMessage.map(JSONValue.string) ?? .null == v) }
    }

    @Test(arguments: [
        ("messages_stream.sse", "messages_stream.json"),
        ("messages_refusal.sse", "messages_refusal.json"),
        ("messages_error.sse", "messages_error.json"),
    ])
    func fixtureReductions(fixture: String, expected: String) throws {
        try assertMatches(try reduceFixture(fixture), expected: expected)
    }

    @Test func streamEvents() throws {
        var decoder = MessagesStreamDecoder()
        let events = try Fixtures.sseEvents("messages_stream.sse").flatMap { decoder.decode($0) }
        #expect(events == [
            .started(model: "claude-haiku-5-5"),
            .textDelta("Hello"),
            .textDelta(" from your wrist."),
            .stop(reason: "end_turn", outputTokens: 6),
        ])
        #expect(decoder.finished)
    }

    @Test func displayTextRules() {
        var r = MessagesReduction()
        r.text = "Cut"
        r.stopReason = "max_tokens"
        #expect(r.displayText == "Cut…")
        r.stopReason = "refusal"
        #expect(r.displayText == "Cut")
        r.text = ""
        #expect(r.displayText == MessagesReduction.refusalNotice)
    }

    @Test func systemPromptPerMode() {
        #expect(SystemPrompt.forMode(.apiKey) == WATCH_SYSTEM_PROMPT)
        #expect(SystemPrompt.forMode(.claudeAccount)
            == "You are Claude Code, Anthropic's official CLI for Claude.\n" + WATCH_SYSTEM_PROMPT)
        #expect(WATCH_SYSTEM_PROMPT == "You are Claude, answering on a smartwatch. Reply in plain text, no markdown, no lists, no code fences. Be brief: one to three short sentences unless the user asks for detail. If the user dictated text, tolerate transcription errors.")
    }

    @Test func modelsAndDefaults() {
        #expect(ClaudeModel.default.rawValue == "claude-haiku-5-5")
        #expect(ClaudeModel.allCases.map(\.rawValue) == ["claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5"])
        #expect(Effort.default == .low)
    }

    @Test func requestBodyShape() throws {
        let body = try JSONValue.parse(MessagesClient.requestBody(
            turns: [ChatTurn(role: .user, text: "Hi")], model: .default, effort: .low, mode: .apiKey
        ))
        #expect(body["model"] == .string("claude-haiku-5-5"))
        #expect(body["max_tokens"]?.intValue == 400)
        #expect(body["stream"] == .bool(true))
        #expect(body["system"] == .string(WATCH_SYSTEM_PROMPT))
        #expect(body["output_config"] == .object(["effort": .string("low")]))
        #expect(body["messages"] == .array([.object(["role": .string("user"), "content": .string("Hi")])]))
        #expect(body["budget_tokens"] == nil)
        #expect(body["thinking"] == nil)
    }

    @Test func historyTrimmedToLast20Turns() {
        var turns: [ChatTurn] = []
        for i in 0..<15 {
            turns.append(ChatTurn(role: .user, text: "q\(i)"))
            turns.append(ChatTurn(role: .assistant, text: "a\(i)"))
        }
        turns.append(ChatTurn(role: .user, text: "final"))
        let sent = MessagesClient.requestMessages(from: turns)
        // Last 20 of 31 starts with a5; a leading assistant turn is dropped, leaving 19.
        #expect(sent.count == 19)
        #expect(sent.last == ChatTurn(role: .user, text: "final"))
        #expect(sent.first == ChatTurn(role: .user, text: "q6"))
        #expect(MessagesClient.requestMessages(from: Array(turns.suffix(20))).count == 19)
        // Never more than 20, whatever the input.
        let many = (0..<100).map { ChatTurn(role: $0 % 3 == 0 ? .assistant : .user, text: "t\($0)") }
        #expect(MessagesClient.requestMessages(from: many).count <= 20)
    }

    @Test func noAssistantPrefillAndMergedRoles() {
        let sent = MessagesClient.requestMessages(from: [
            ChatTurn(role: .assistant, text: "orphan"),
            ChatTurn(role: .user, text: "one"),
            ChatTurn(role: .assistant, text: ""),
            ChatTurn(role: .user, text: "two"),
            ChatTurn(role: .assistant, text: "partial"),
        ])
        #expect(sent == [ChatTurn(role: .user, text: "one\n\ntwo")])
    }

    func makeAuth(_ creds: Credentials, stub: StubHTTPClient) -> AuthProvider {
        AuthProvider(store: InMemoryTokenStore(creds), oauth: OAuthClient(http: stub), userAgent: "UA")
    }

    @Test(arguments: [1, 3, 17, 4096])
    func clientStreamsFixtureAtAnyChunkSize(chunk: Int) async throws {
        let sse = try Fixtures.fixture("messages_stream.sse")
        let stub = StubHTTPClient(chunkSize: chunk, queue: [.init(status: 200, body: sse)])
        let client = MessagesClient(http: stub, auth: makeAuth(.apiKey("sk-ant-api03-k"), stub: stub))
        let r = try await client.reply(turns: [ChatTurn(role: .user, text: "Hello?")])
        try assertMatches(r, expected: "messages_stream.json")
        let req = try #require(await stub.requests.first)
        #expect(req.url.absoluteString == "https://api.anthropic.com/v1/messages")
        #expect(req.method == .post)
        #expect(req.header("x-api-key") == "sk-ant-api03-k")
        #expect(req.header("anthropic-beta") == nil)
        #expect(req.jsonBody?["system"] == .string(WATCH_SYSTEM_PROMPT))
    }

    @Test func claudeAccountRequestCarriesIdentity() async throws {
        let stub = StubHTTPClient(queue: [.init(status: 200, body: try Fixtures.fixture("messages_stream.sse"))])
        let client = MessagesClient(http: stub, auth: makeAuth(fixtureOAuthCredentials, stub: stub))
        _ = try await client.reply(turns: [ChatTurn(role: .user, text: "Hi")], model: .sonnet5_5)
        let req = try #require(await stub.requests.first)
        #expect(req.header("Authorization") == "Bearer sk-ant-oat01-old")
        #expect(req.header("anthropic-beta") == "oauth-2025-04-20,claude-code-20250219")
        let system = try #require(req.jsonBody?["system"]?.stringValue)
        #expect(system.hasPrefix(CLAUDE_CODE_IDENTITY_LINE + "\n"))
        #expect(req.jsonBody?["model"] == .string("claude-sonnet-5-5"))
    }

    @Test func streamedErrorEventIsDelivered() async throws {
        let stub = StubHTTPClient(queue: [.init(status: 200, body: try Fixtures.fixture("messages_error.sse"))])
        let client = MessagesClient(http: stub, auth: makeAuth(.apiKey("k"), stub: stub))
        let r = try await client.reply(turns: [ChatTurn(role: .user, text: "Hi")])
        try assertMatches(r, expected: "messages_error.json")
    }

    @Test func httpErrorMapping() async throws {
        let cases: [(StubHTTPClient.Reply, APIError)] = [
            (.init(status: 429, headers: ["Retry-After": "12"], text: "{}"), .rateLimited(retryAfter: "12")),
            (.init(status: 529, text: #"{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"#), .overloaded),
            (.init(status: 400, text: #"{"type":"error","error":{"type":"invalid_request_error","message":"This credential is only authorized for use with Claude Code and cannot be used for other API requests."}}"#), .claudeCodeOnly),
            (.init(status: 400, text: #"{"type":"error","error":{"type":"invalid_request_error","message":"bad"}}"#), .http(status: 400, type: "invalid_request_error", message: "bad")),
        ]
        for (reply, expected) in cases {
            let stub = StubHTTPClient(queue: [reply])
            let client = MessagesClient(http: stub, auth: makeAuth(.apiKey("k"), stub: stub))
            await #expect(throws: expected) {
                _ = try await client.reply(turns: [ChatTurn(role: .user, text: "Hi")])
            }
            #expect(await stub.requestCount == 1, "completions are never retried")
        }
    }

    @Test func apiKeyValidationRequest() {
        let r = MessagesClient.validationRequest(apiKey: "sk-ant-api03-x", userAgent: "UA")
        #expect(r.header("x-api-key") == "sk-ant-api03-x")
        #expect(r.jsonBody?["max_tokens"]?.intValue == 1)
        #expect(r.jsonBody?["stream"] == nil)
    }
}
