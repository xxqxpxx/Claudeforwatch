import Foundation
import Testing
@testable import ClaudeWatchKit

@Suite struct OAuthTests {
    let pkce = PKCE(verifier: "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", state: "STATE_abc-123", createdAt: Date())

    @Test func authorizeURLHasParametersInSpecOrder() async {
        let client = OAuthClient(http: StubHTTPClient(queue: []))
        let url = client.authorizeURL(pkce: pkce).absoluteString
        #expect(url == "https://claude.ai/oauth/authorize?code=true"
            + "&client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e"
            + "&response_type=code"
            + "&redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback"
            + "&scope=user%3Aprofile%20user%3Ainference%20user%3Asessions%3Aclaude_code"
            + "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
            + "&code_challenge_method=S256"
            + "&state=STATE_abc-123")
        let names = URLComponents(string: url)!.queryItems!.map(\.name)
        #expect(names == ["code", "client_id", "response_type", "redirect_uri", "scope",
                          "code_challenge", "code_challenge_method", "state"])
        let scope = URLComponents(string: url)!.queryItems!.first { $0.name == "scope" }!.value
        #expect(scope == "user:profile user:inference user:sessions:claude_code")
        // Fits comfortably in a QR code.
        #expect(url.count < 500)
    }

    @Test func parseCodeAcceptsMatchingState() throws {
        #expect(try OAuthClient.parseCode("abc123#STATE_abc-123", expectedState: "STATE_abc-123") == "abc123")
        #expect(try OAuthClient.parseCode("  abc123#STATE_abc-123\n", expectedState: "STATE_abc-123") == "abc123")
    }

    @Test func parseCodeRejectsMismatchMissingAndEmpty() {
        #expect(throws: OAuthError.stateMismatch) {
            try OAuthClient.parseCode("abc123#OTHER", expectedState: "STATE_abc-123")
        }
        #expect(throws: OAuthError.missingState) {
            try OAuthClient.parseCode("abc123", expectedState: "STATE_abc-123")
        }
        #expect(throws: OAuthError.missingState) {
            try OAuthClient.parseCode("abc123#", expectedState: "STATE_abc-123")
        }
        #expect(throws: OAuthError.emptyCode) {
            try OAuthClient.parseCode("   ", expectedState: "STATE_abc-123")
        }
        #expect(throws: OAuthError.emptyCode) {
            try OAuthClient.parseCode("#STATE_abc-123", expectedState: "STATE_abc-123")
        }
    }

    @Test func exchangeSendsJSONBody() async throws {
        let token = try Fixtures.fixture("oauth_token_response.json")
        let stub = StubHTTPClient(queue: [.init(status: 200, body: token)])
        let client = OAuthClient(http: stub)
        let response = try await client.exchange(code: "the-code", pkce: pkce)
        #expect(response.accessToken == "sk-ant-oat01-fixture-access")
        #expect(response.organization?.uuid == "org-uuid-fixture")
        #expect(response.account?.emailAddress == "you@example.com")

        let requests = await stub.requests
        #expect(requests.count == 1)
        let r = requests[0]
        #expect(r.method == .post)
        #expect(r.url.absoluteString == "https://platform.claude.com/v1/oauth/token")
        #expect(r.header("Content-Type") == "application/json")
        let body = try #require(r.jsonBody?.objectValue)
        #expect(body.keys == ["grant_type", "code", "state", "client_id", "redirect_uri", "code_verifier"])
        #expect(body["grant_type"] == .string("authorization_code"))
        #expect(body["code"] == .string("the-code"))
        #expect(body["state"] == .string("STATE_abc-123"))
        #expect(body["client_id"] == .string("9d1c250a-e61b-44d9-88ed-5944d1962f5e"))
        #expect(body["redirect_uri"] == .string("https://platform.claude.com/oauth/code/callback"))
        #expect(body["code_verifier"] == .string(pkce.verifier))
    }

    @Test func exchangeFallsBackToFormOnInvalidGrant() async throws {
        let token = try Fixtures.fixture("oauth_token_response.json")
        let stub = StubHTTPClient(queue: [
            .init(status: 400, text: #"{"error":"invalid_grant","error_description":"bad"}"#),
            .init(status: 200, body: token),
        ])
        let response = try await OAuthClient(http: stub).exchange(code: "c0de", pkce: pkce)
        #expect(response.refreshToken == "sk-ant-ort01-fixture-refresh")
        let requests = await stub.requests
        #expect(requests.count == 2)
        #expect(requests[0].header("Content-Type") == "application/json")
        #expect(requests[1].header("Content-Type") == "application/x-www-form-urlencoded")
        let form = String(decoding: requests[1].body!, as: UTF8.self)
        #expect(form == "grant_type=authorization_code&code=c0de&state=STATE_abc-123"
            + "&client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e"
            + "&redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback"
            + "&code_verifier=\(pkce.verifier)")
    }

    @Test func exchangeFallsBackOnlyOnce() async throws {
        let stub = StubHTTPClient(queue: [
            .init(status: 400, text: #"{"error":"invalid_grant"}"#),
            .init(status: 400, text: #"{"error":"invalid_grant"}"#),
            .init(status: 200, text: "{}"),
        ])
        await #expect(throws: OAuthError.rejected(status: 400, error: "invalid_grant", description: nil)) {
            try await OAuthClient(http: stub).exchange(code: "c", pkce: pkce)
        }
        #expect(await stub.requestCount == 2)
    }

    @Test func otherErrorsAreFinal() async throws {
        let stub = StubHTTPClient(queue: [.init(status: 400, text: #"{"error":"invalid_request"}"#)])
        await #expect(throws: OAuthError.rejected(status: 400, error: "invalid_request", description: nil)) {
            try await OAuthClient(http: stub).exchange(code: "c", pkce: pkce)
        }
        #expect(await stub.requestCount == 1)

        let stub500 = StubHTTPClient(queue: [.init(status: 500, text: "oops")])
        await #expect(throws: OAuthError.http(status: 500)) {
            try await OAuthClient(http: stub500).exchange(code: "c", pkce: pkce)
        }
        #expect(await stub500.requestCount == 1)
    }

    @Test func expiredPKCEIsRejectedWithoutNetwork() async throws {
        let stub = StubHTTPClient(queue: [])
        let old = PKCE(verifier: "v", state: "s", createdAt: Date(timeIntervalSince1970: 0))
        await #expect(throws: OAuthError.expired) {
            try await OAuthClient(http: stub).exchange(code: "c", pkce: old, now: Date(timeIntervalSince1970: 601))
        }
        #expect(await stub.requestCount == 0)
    }

    @Test func refreshRequestShape() async throws {
        let stub = StubHTTPClient(queue: [.init(status: 200, body: try Fixtures.fixture("oauth_refresh_response.json"))])
        let r = try await OAuthClient(http: stub).refresh(refreshToken: "sk-ant-ort01-old")
        #expect(r.refreshToken == "sk-ant-ort01-fixture-refresh-2")
        let req = try #require(await stub.requests.first)
        #expect(req.header("anthropic-beta") == "oauth-2025-04-20")
        #expect(req.header("Content-Type") == "application/json")
        let body = try #require(req.jsonBody?.objectValue)
        #expect(body.keys == ["grant_type", "refresh_token", "client_id"])
        #expect(body["grant_type"] == .string("refresh_token"))
        #expect(body["refresh_token"] == .string("sk-ant-ort01-old"))
    }

    @Test func credentialsFromTokenResponse() throws {
        let token = try JSONDecoder().decode(TokenResponse.self, from: try Fixtures.fixture("oauth_token_response.json"))
        let now = Date(timeIntervalSince1970: 1_760_000_000)
        let c = token.credentials(now: now)
        #expect(c.mode == .claudeAccount)
        let expectedExpiry: Int64 = 1_760_000_000_000 + 28_800_000 - 60_000
        #expect(c.expiresAt == expectedExpiry)
        #expect(c.scopes == ["user:profile", "user:inference", "user:sessions:claude_code"])
        #expect(c.organizationUuid == "org-uuid-fixture")
        #expect(c.accountEmail == "you@example.com")
        #expect(c.apiKey == nil)
    }

    @Test func refreshKeepsOrganizationAndEmail() throws {
        let token = try JSONDecoder().decode(TokenResponse.self, from: try Fixtures.fixture("oauth_refresh_response.json"))
        let c = token.credentials(now: Date(timeIntervalSince1970: 1_760_000_000), previous: fixtureOAuthCredentials)
        #expect(c.accessToken == "sk-ant-oat01-fixture-access-2")
        #expect(c.refreshToken == "sk-ant-ort01-fixture-refresh-2")
        #expect(c.organizationUuid == "org-uuid-fixture")
        #expect(c.accountEmail == "you@example.com")
    }

    @Test func credentialsRecordRoundTripsWithNulls() throws {
        let data = try JSONEncoder().encode(Credentials.apiKey("sk-ant-api03-x"))
        let json = try JSONValue.parse(data)
        #expect(json["accessToken"] == .null)
        #expect(json["apiKey"] == .string("sk-ant-api03-x"))
        #expect(json["mode"] == .string("apiKey"))
        let back = try JSONDecoder().decode(Credentials.self, from: data)
        #expect(back == Credentials.apiKey("sk-ant-api03-x"))

        // The exact record from PROTOCOL §1.1 decodes.
        let spec = #"{"mode":"claudeAccount","accessToken":"sk-ant-oat01-a","refreshToken":"sk-ant-ort01-b","expiresAt":1760000000000,"scopes":["user:profile","user:inference","user:sessions:claude_code"],"organizationUuid":"o","accountEmail":"e","apiKey":null}"#
        let c = try JSONDecoder().decode(Credentials.self, from: Data(spec.utf8))
        #expect(c.expiresAt == 1_760_000_000_000)
        #expect(c.hasSessionsScope)
    }
}
