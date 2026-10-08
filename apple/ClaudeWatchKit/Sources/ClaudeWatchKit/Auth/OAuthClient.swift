import Foundation

/// Endpoints and identifiers of the Claude Code OAuth client.
///
/// UNOFFICIAL: this is Claude Code's public OAuth client (docs/PROTOCOL.md §2,
/// docs/research/anthropic-auth-and-sessions.md §2). Anthropic does not
/// support third-party use; only compiled into PERSONAL_MODE builds' UI.
public struct OAuthConfig: Sendable, Equatable {
    public var authorizeURL: URL
    public var tokenURL: URL
    public var clientID: String
    public var redirectURI: String
    public var scopes: [String]

    public init(authorizeURL: URL, tokenURL: URL, clientID: String, redirectURI: String, scopes: [String]) {
        self.authorizeURL = authorizeURL
        self.tokenURL = tokenURL
        self.clientID = clientID
        self.redirectURI = redirectURI
        self.scopes = scopes
    }

    public static let claudeCode = OAuthConfig(
        authorizeURL: URL(string: "https://claude.ai/oauth/authorize")!,
        tokenURL: URL(string: "https://platform.claude.com/v1/oauth/token")!,
        clientID: "9d1c250a-e61b-44d9-88ed-5944d1962f5e",
        // The only redirect a watch can use: the page shows `<code>#<state>`
        // for manual paste (§2). Localhost callbacks are impossible on a watch.
        redirectURI: "https://platform.claude.com/oauth/code/callback",
        scopes: ["user:profile", "user:inference", "user:sessions:claude_code"]
    )

    public var scopeString: String { scopes.joined(separator: " ") }
}

public enum OAuthError: Error, Sendable, Equatable {
    /// The pasted text was empty.
    case emptyCode
    /// The pasted code had no `#<state>` suffix.
    case missingState
    /// The `#<state>` suffix did not match the state we generated.
    case stateMismatch
    /// The PKCE verifier is older than 10 minutes; restart sign-in.
    case expired
    /// The token endpoint rejected the grant (400/401 with an OAuth error).
    case rejected(status: Int, error: String?, description: String?)
    /// Any other HTTP failure.
    case http(status: Int)
    /// The response was not the documented JSON.
    case malformedResponse
}

/// Token endpoint response (`spec/fixtures/oauth_token_response.json`).
public struct TokenResponse: Decodable, Sendable, Equatable {
    public struct Organization: Decodable, Sendable, Equatable {
        public var uuid: String?
        public var name: String?
    }

    public struct Account: Decodable, Sendable, Equatable {
        public var uuid: String?
        public var emailAddress: String?

        enum CodingKeys: String, CodingKey {
            case uuid
            case emailAddress = "email_address"
        }
    }

    public var tokenType: String?
    public var accessToken: String
    public var refreshToken: String?
    public var expiresIn: Int
    public var scope: String?
    public var organization: Organization?
    public var account: Account?

    enum CodingKeys: String, CodingKey {
        case tokenType = "token_type"
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
        case expiresIn = "expires_in"
        case scope, organization, account
    }

    /// Builds the stored record. `expiresAt = now + expires_in*1000 - 60_000` (§2).
    /// Fields the refresh response omits (organization, email, and the refresh
    /// token if not rotated) are carried over from `previous`.
    public func credentials(now: Date, previous: Credentials? = nil) -> Credentials {
        let nowMs = Int64((now.timeIntervalSince1970 * 1000).rounded())
        let scopes = scope.map { $0.split(separator: " ").map(String.init) } ?? previous?.scopes ?? []
        return Credentials(
            mode: .claudeAccount,
            accessToken: accessToken,
            refreshToken: refreshToken ?? previous?.refreshToken,
            expiresAt: nowMs + Int64(expiresIn) * 1000 - 60_000,
            scopes: scopes,
            organizationUuid: organization?.uuid ?? previous?.organizationUuid,
            accountEmail: account?.emailAddress ?? previous?.accountEmail,
            apiKey: nil
        )
    }
}

/// Claude Code OAuth (PKCE, manual code paste). docs/PROTOCOL.md §2.
public struct OAuthClient: Sendable {
    public let config: OAuthConfig
    public let http: any HTTPClient
    public let userAgent: String

    public init(http: any HTTPClient, config: OAuthConfig = .claudeCode, userAgent: String = ClientInfo.defaultUserAgent) {
        self.http = http
        self.config = config
        self.userAgent = userAgent
    }

    /// The authorize URL, parameters in the exact order of §2. This is what the
    /// watch renders as a QR code for the phone to open.
    public func authorizeURL(pkce: PKCE) -> URL {
        let params: [(String, String)] = [
            ("code", "true"),
            ("client_id", config.clientID),
            ("response_type", "code"),
            ("redirect_uri", config.redirectURI),
            ("scope", config.scopeString),
            ("code_challenge", pkce.challenge),
            ("code_challenge_method", "S256"),
            ("state", pkce.state),
        ]
        let query = params.map { "\($0.0)=\($0.1.strictlyPercentEncoded)" }.joined(separator: "&")
        return URL(string: config.authorizeURL.absoluteString + "?" + query)!
    }

    /// Parses the `<code>#<state>` text the callback page shows. Whitespace
    /// around the paste is ignored. The state suffix is mandatory and must
    /// equal `expectedState` (§2: a mismatch is an error).
    public static func parseCode(_ pasted: String, expectedState: String) throws -> String {
        let trimmed = pasted.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw OAuthError.emptyCode }
        guard let hash = trimmed.firstIndex(of: "#") else { throw OAuthError.missingState }
        let code = String(trimmed[..<hash]).trimmingCharacters(in: .whitespaces)
        let state = String(trimmed[trimmed.index(after: hash)...]).trimmingCharacters(in: .whitespaces)
        guard !code.isEmpty else { throw OAuthError.emptyCode }
        guard !state.isEmpty else { throw OAuthError.missingState }
        guard constantTimeEquals(state, expectedState) else { throw OAuthError.stateMismatch }
        return code
    }

    /// Exchanges an authorization code. JSON body first; if the server answers
    /// 400 `invalid_grant`, retries exactly once form-encoded (§2: sources
    /// disagree on the encoding). Any other error is final.
    public func exchange(code: String, pkce: PKCE, now: Date = Date()) async throws -> TokenResponse {
        guard !pkce.isExpired(now: now) else { throw OAuthError.expired }
        let fields: [(String, String)] = [
            ("grant_type", "authorization_code"),
            ("code", code),
            ("state", pkce.state),
            ("client_id", config.clientID),
            ("redirect_uri", config.redirectURI),
            ("code_verifier", pkce.verifier),
        ]
        let jsonResponse = try await http.data(for: tokenRequest(fields: fields, form: false, extraHeaders: [:]))
        if jsonResponse.isSuccess { return try decodeToken(jsonResponse.body) }
        if jsonResponse.status == 400, oauthErrorCode(jsonResponse.body) == "invalid_grant" {
            let formResponse = try await http.data(for: tokenRequest(fields: fields, form: true, extraHeaders: [:]))
            if formResponse.isSuccess { return try decodeToken(formResponse.body) }
            throw failure(formResponse)
        }
        throw failure(jsonResponse)
    }

    /// Refreshes the access token (§2). The caller must persist the returned
    /// `refresh_token` (rotation).
    public func refresh(refreshToken: String) async throws -> TokenResponse {
        let fields: [(String, String)] = [
            ("grant_type", "refresh_token"),
            ("refresh_token", refreshToken),
            ("client_id", config.clientID),
        ]
        let response = try await http.data(for: tokenRequest(
            fields: fields, form: false, extraHeaders: ["anthropic-beta": AnthropicBeta.oauth]
        ))
        if response.isSuccess { return try decodeToken(response.body) }
        throw failure(response)
    }

    // MARK: Helpers

    func tokenRequest(fields: [(String, String)], form: Bool, extraHeaders: [String: String]) -> HTTPRequest {
        var headers = extraHeaders
        headers["User-Agent"] = userAgent
        headers["Accept"] = "application/json"
        let body: Data
        if form {
            headers["Content-Type"] = "application/x-www-form-urlencoded"
            body = Data(fields.map { "\($0.0.strictlyPercentEncoded)=\($0.1.strictlyPercentEncoded)" }
                .joined(separator: "&").utf8)
        } else {
            headers["Content-Type"] = "application/json"
            body = JSONValue.object(JSONObject(fields.map { (key: $0.0, value: JSONValue.string($0.1)) }))
                .serializedData()
        }
        return HTTPRequest(method: .post, url: config.tokenURL, headers: headers, body: body)
    }

    func decodeToken(_ body: Data) throws -> TokenResponse {
        do {
            return try JSONDecoder().decode(TokenResponse.self, from: body)
        } catch {
            throw OAuthError.malformedResponse
        }
    }

    func oauthErrorCode(_ body: Data) -> String? {
        guard let json = try? JSONValue.parse(body) else { return nil }
        // `{"error":"invalid_grant"}` (RFC 6749) or `{"error":{"type":"invalid_grant"}}`.
        if let s = json["error"]?.stringValue { return s }
        return json["error"]?["type"]?.stringValue
    }

    func failure(_ response: HTTPResponse) -> OAuthError {
        if response.status == 400 || response.status == 401 {
            let json = try? JSONValue.parse(response.body)
            let description = json?["error_description"]?.stringValue ?? json?["error"]?["message"]?.stringValue
            return .rejected(status: response.status, error: oauthErrorCode(response.body), description: description)
        }
        return .http(status: response.status)
    }

    private static func constantTimeEquals(_ a: String, _ b: String) -> Bool {
        let x = Array(a.utf8), y = Array(b.utf8)
        guard x.count == y.count else { return false }
        var diff: UInt8 = 0
        for i in x.indices { diff |= x[i] ^ y[i] }
        return diff == 0
    }
}
