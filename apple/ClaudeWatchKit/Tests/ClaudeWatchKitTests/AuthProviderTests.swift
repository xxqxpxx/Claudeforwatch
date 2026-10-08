import Foundation
import Testing
@testable import ClaudeWatchKit

@Suite struct AuthProviderTests {
    let ua = "ClaudeForWatch/0.1.0 (watchOS)"

    func provider(_ creds: Credentials?, stub: StubHTTPClient, clock: TestClock = TestClock()) -> (AuthProvider, InMemoryTokenStore) {
        let store = InMemoryTokenStore(creds)
        let auth = AuthProvider(store: store, oauth: OAuthClient(http: stub, userAgent: ua), userAgent: ua, now: { clock.now })
        return (auth, store)
    }

    // MARK: Header matrix (PROTOCOL §3–§5)

    @Test func apiKeyMessagesHeaders() async throws {
        let (auth, _) = provider(.apiKey("sk-ant-api03-key"), stub: StubHTTPClient(queue: []))
        let h = try await auth.authHeaders(for: .messages)
        #expect(h == [
            "anthropic-version": "2023-06-01",
            "Content-Type": "application/json",
            "User-Agent": ua,
            "x-api-key": "sk-ant-api03-key",
        ])
    }

    @Test func apiKeyCannotReachAccountEndpoints() async throws {
        let (auth, _) = provider(.apiKey("k"), stub: StubHTTPClient(queue: []))
        for endpoint in [Endpoint.sessionsList, .sessions, .oauthAccount] {
            await #expect(throws: APIError.notAvailableInMode) { try await auth.authHeaders(for: endpoint) }
        }
    }

    @Test func claudeAccountHeaderMatrix() async throws {
        let (auth, _) = provider(fixtureOAuthCredentials, stub: StubHTTPClient(queue: []))
        let base: [String: String] = [
            "anthropic-version": "2023-06-01",
            "Content-Type": "application/json",
            "User-Agent": ua,
            "Authorization": "Bearer sk-ant-oat01-old",
        ]
        var messages = base
        messages["anthropic-beta"] = "oauth-2025-04-20,claude-code-20250219"
        #expect(try await auth.authHeaders(for: .messages) == messages)

        var list = base
        list["anthropic-client-platform"] = "web_claude_ai"
        list["x-organization-uuid"] = "org-uuid-fixture"
        let listHeaders = try await auth.authHeaders(for: .sessionsList)
        #expect(listHeaders == list)
        #expect(listHeaders["anthropic-beta"] == nil, "bare list must not carry anthropic-beta")

        var sessions = list
        sessions["anthropic-beta"] = "ccr-byoc-2025-07-29"
        #expect(try await auth.authHeaders(for: .sessions) == sessions)

        var account = base
        account["anthropic-beta"] = "oauth-2025-04-20"
        #expect(try await auth.authHeaders(for: .oauthAccount) == account)
        #expect(try await auth.authHeaders(for: .messages)["x-api-key"] == nil)
    }

    @Test func sessionsNeedOrganization() async throws {
        var creds = fixtureOAuthCredentials
        creds.organizationUuid = nil
        let (auth, _) = provider(creds, stub: StubHTTPClient(queue: []))
        await #expect(throws: APIError.missingOrganization) { try await auth.authHeaders(for: .sessions) }
        _ = try await auth.authHeaders(for: .messages)
    }

    @Test func signedOutThrows() async throws {
        let (auth, _) = provider(nil, stub: StubHTTPClient(queue: []))
        #expect(try await auth.load() == .signedOut)
        await #expect(throws: APIError.signedOut) { try await auth.authHeaders(for: .messages) }
    }

    // MARK: Refresh

    @Test func expiredTokenIsRefreshedAndRotated() async throws {
        let clock = TestClock()
        let stub = StubHTTPClient(queue: [.init(status: 200, body: try Fixtures.fixture("oauth_refresh_response.json"))])
        var creds = fixtureOAuthCredentials
        creds.expiresAt = clock.nowMillis // now >= expiresAt ⇒ expired
        let (auth, store) = provider(creds, stub: stub, clock: clock)
        let h = try await auth.authHeaders(for: .sessions)
        #expect(h["Authorization"] == "Bearer sk-ant-oat01-fixture-access-2")
        let saved = try #require(try await store.load())
        #expect(saved.refreshToken == "sk-ant-ort01-fixture-refresh-2")
        #expect(saved.accessToken == "sk-ant-oat01-fixture-access-2")
        #expect(saved.expiresAt == clock.nowMillis + 28_800_000 - 60_000)
        #expect(saved.organizationUuid == "org-uuid-fixture")
        let refreshReq = try #require(await stub.requests.first)
        #expect(refreshReq.jsonBody?["refresh_token"] == .string("sk-ant-ort01-old"))

        // Not expired any more: no second refresh.
        _ = try await auth.authHeaders(for: .messages)
        #expect(await stub.requestCount == 1)
    }

    @Test func concurrentCallersShareOneRefresh() async throws {
        let clock = TestClock()
        let body = try Fixtures.fixture("oauth_refresh_response.json")
        let stub = StubHTTPClient(queue: [
            .init(status: 200, body: body, delayNanos: 100_000_000),
            .init(status: 500, text: "second refresh must not happen"),
        ])
        var creds = fixtureOAuthCredentials
        creds.expiresAt = 0
        let (auth, store) = provider(creds, stub: stub, clock: clock)
        let results = try await withThrowingTaskGroup(of: String?.self) { group in
            for i in 0..<12 {
                group.addTask { try await auth.authHeaders(for: i % 2 == 0 ? .messages : .sessions)["Authorization"] }
            }
            var all: [String?] = []
            for try await r in group { all.append(r) }
            return all
        }
        #expect(results.count == 12)
        #expect(results.allSatisfy { $0 == "Bearer sk-ant-oat01-fixture-access-2" })
        #expect(await stub.requestCount == 1)
        #expect(await store.saveCount == 1)
    }

    @Test func rejectedRefreshSignsOut() async throws {
        let stub = StubHTTPClient(queue: [.init(status: 400, text: #"{"error":"invalid_grant"}"#)])
        var creds = fixtureOAuthCredentials
        creds.expiresAt = 0
        let (auth, store) = provider(creds, stub: stub)
        await #expect(throws: APIError.signedOut) { try await auth.authHeaders(for: .messages) }
        #expect(try await store.load() == nil)
        #expect(await auth.state == .signedOut)
    }

    @Test func networkFailureDuringRefreshKeepsCredentials() async throws {
        let stub = StubHTTPClient(queue: [.init(status: 503, text: "unavailable")])
        var creds = fixtureOAuthCredentials
        creds.expiresAt = 0
        let (auth, store) = provider(creds, stub: stub)
        await #expect(throws: OAuthError.http(status: 503)) { try await auth.authHeaders(for: .messages) }
        #expect(try await store.load() == creds)
    }

    @Test func unauthorizedTriggersOneRefreshAndRetry() async throws {
        let refresh = try Fixtures.fixture("oauth_refresh_response.json")
        let stub = StubHTTPClient { req in
            if req.url.path.hasSuffix("/oauth/token") { return .init(status: 200, body: refresh) }
            if req.header("Authorization") == "Bearer sk-ant-oat01-old" {
                return .init(status: 401, text: #"{"type":"error","error":{"type":"authentication_error","message":"expired"}}"#)
            }
            return .init(status: 200, text: #"{"five_hour":{"utilization":42.0,"resets_at":"2026-10-08T12:00:00Z"},"seven_day":{"utilization":10}}"#)
        }
        let (auth, store) = provider(fixtureOAuthCredentials, stub: stub)
        let usage = try await UsageClient(http: stub, auth: auth).usage()
        #expect(usage.headlinePercent == 42)
        #expect(await stub.requestCount == 3)
        #expect(try await store.load()?.refreshToken == "sk-ant-ort01-fixture-refresh-2")
    }

    @Test func persistent401AfterRefreshSurfacesUnauthorized() async throws {
        let refresh = try Fixtures.fixture("oauth_refresh_response.json")
        let stub = StubHTTPClient { req in
            if req.url.path.hasSuffix("/oauth/token") { return .init(status: 200, body: refresh) }
            return .init(status: 401, text: "{}")
        }
        let (auth, _) = provider(fixtureOAuthCredentials, stub: stub)
        await #expect(throws: APIError.unauthorized) { try await UsageClient(http: stub, auth: auth).profile() }
        #expect(await stub.requestCount == 3) // call, refresh, one retry
    }

    @Test func apiKey401IsNotRetried() async throws {
        let stub = StubHTTPClient(queue: [.init(status: 401, text: "{}")])
        let (auth, _) = provider(.apiKey("bad"), stub: stub)
        let client = MessagesClient(http: stub, auth: auth)
        await #expect(throws: APIError.unauthorized) {
            _ = try await client.reply(turns: [ChatTurn(role: .user, text: "hi")])
        }
        #expect(await stub.requestCount == 1)
    }

    @Test func completeOAuthStoresCredentials() async throws {
        let clock = TestClock()
        let stub = StubHTTPClient(queue: [.init(status: 200, body: try Fixtures.fixture("oauth_token_response.json"))])
        let (auth, store) = provider(nil, stub: stub, clock: clock)
        let pkce = PKCE(verifier: "v", state: "st", createdAt: clock.now)
        await #expect(throws: OAuthError.stateMismatch) { try await auth.completeOAuth(pasted: "code#nope", pkce: pkce) }
        #expect(await stub.requestCount == 0)
        try await auth.completeOAuth(pasted: "code#st", pkce: pkce)
        #expect(await auth.state == .claudeAccount(email: "you@example.com"))
        #expect(try await store.load()?.organizationUuid == "org-uuid-fixture")
        await auth.signOut()
        #expect(try await store.load() == nil)
    }
}
