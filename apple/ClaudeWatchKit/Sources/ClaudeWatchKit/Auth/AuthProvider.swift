import Foundation

/// The request families whose headers differ (docs/PROTOCOL.md §3–§6).
public enum Endpoint: Sendable, Equatable, CaseIterable {
    /// `POST /v1/messages` (§4).
    case messages
    /// Bare `GET /v1/code/sessions` (§5.1): no `anthropic-beta`.
    case sessionsList
    /// Every other `/v1/code/sessions…` call (§5): `ccr-byoc` beta.
    case sessions
    /// `GET /api/oauth/usage` and `/api/oauth/profile` (§2).
    case oauthAccount
}

/// Signed-in state as seen by the UI.
public enum AuthState: Sendable, Equatable {
    case signedOut
    case apiKey
    case claudeAccount(email: String?)

    public var mode: AuthMode? {
        switch self {
        case .signedOut: return nil
        case .apiKey: return .apiKey
        case .claudeAccount: return .claudeAccount
        }
    }
}

/// Owns the credentials, produces per-endpoint auth headers, and refreshes
/// OAuth tokens with single-flight semantics: concurrent callers that find the
/// token expired (or hit a 401) share one refresh request.
public actor AuthProvider {
    public nonisolated let store: any TokenStore
    public nonisolated let oauth: OAuthClient
    public nonisolated let userAgent: String
    private let now: @Sendable () -> Date

    public private(set) var credentials: Credentials?
    private var loaded = false
    private var refreshTask: Task<Credentials, any Error>?

    public init(
        store: any TokenStore,
        oauth: OAuthClient,
        userAgent: String = ClientInfo.defaultUserAgent,
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.store = store
        self.oauth = oauth
        self.userAgent = userAgent
        self.now = now
    }

    // MARK: State

    /// Current mode. Only meaningful after `load()` (every request path loads first).
    public var mode: AuthMode? { credentials?.mode }

    public var state: AuthState {
        switch credentials?.mode {
        case .none: return .signedOut
        case .apiKey: return .apiKey
        case .claudeAccount: return .claudeAccount(email: credentials?.accountEmail)
        }
    }

    /// Loads stored credentials once. Safe to call repeatedly.
    @discardableResult
    public func load() async throws -> AuthState {
        if !loaded {
            credentials = try await store.load()
            loaded = true
        }
        return state
    }

    public func signIn(apiKey: String) async throws {
        let key = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let creds = Credentials.apiKey(key)
        try await store.save(creds)
        credentials = creds
        loaded = true
    }

    /// Completes the Claude-account flow: verifies `state`, exchanges the code,
    /// stores the tokens. `pasted` is the raw `<code>#<state>` text.
    public func completeOAuth(pasted: String, pkce: PKCE) async throws {
        let code = try OAuthClient.parseCode(pasted, expectedState: pkce.state)
        let token = try await oauth.exchange(code: code, pkce: pkce, now: now())
        let creds = token.credentials(now: now())
        try await store.save(creds)
        credentials = creds
        loaded = true
    }

    /// Stores profile details fetched after sign-in.
    public func updateProfile(email: String?, organizationUuid: String?) async throws {
        guard var creds = credentials, creds.mode == .claudeAccount else { return }
        if let email { creds.accountEmail = email }
        if let organizationUuid { creds.organizationUuid = organizationUuid }
        try await store.save(creds)
        credentials = creds
    }

    public func signOut() async {
        refreshTask?.cancel()
        refreshTask = nil
        credentials = nil
        loaded = true
        try? await store.clear()
    }

    // MARK: Headers

    /// The exact headers for `endpoint` in the current mode (§3–§5), refreshing
    /// an expired OAuth token first.
    public func authHeaders(for endpoint: Endpoint) async throws -> [String: String] {
        try await load()
        guard let creds = credentials else { throw APIError.signedOut }
        switch creds.mode {
        case .apiKey:
            guard endpoint == .messages else { throw APIError.notAvailableInMode }
            guard let key = creds.apiKey, !key.isEmpty else { throw APIError.signedOut }
            return Self.headers(for: endpoint, mode: .apiKey, secret: key, organizationUuid: nil, userAgent: userAgent)
        case .claudeAccount:
            let fresh = try await validCredentials()
            guard let token = fresh.accessToken else { throw APIError.signedOut }
            if endpoint == .sessions || endpoint == .sessionsList {
                guard fresh.organizationUuid != nil else { throw APIError.missingOrganization }
            }
            return Self.headers(
                for: endpoint, mode: .claudeAccount, secret: token,
                organizationUuid: fresh.organizationUuid, userAgent: userAgent
            )
        }
    }

    /// Pure header construction, exposed for tests and for parity with Android.
    public static func headers(
        for endpoint: Endpoint,
        mode: AuthMode,
        secret: String,
        organizationUuid: String?,
        userAgent: String
    ) -> [String: String] {
        var h: [String: String] = [
            "anthropic-version": ClientInfo.anthropicVersion,
            "Content-Type": "application/json",
            "User-Agent": userAgent,
        ]
        switch mode {
        case .apiKey:
            h["x-api-key"] = secret
        case .claudeAccount:
            h["Authorization"] = "Bearer \(secret)"
            switch endpoint {
            case .messages:
                // §4: subscription inference is gated on the Claude Code identity.
                h["anthropic-beta"] = "\(AnthropicBeta.oauth),\(AnthropicBeta.claudeCode)"
            case .sessionsList:
                // §5.1: the bare list is sent WITHOUT anthropic-beta.
                h["anthropic-client-platform"] = "web_claude_ai"
                if let organizationUuid { h["x-organization-uuid"] = organizationUuid }
            case .sessions:
                h["anthropic-client-platform"] = "web_claude_ai"
                if let organizationUuid { h["x-organization-uuid"] = organizationUuid }
                h["anthropic-beta"] = AnthropicBeta.sessions
            case .oauthAccount:
                // Not specified in PROTOCOL.md; Claude Code sends the OAuth beta here.
                h["anthropic-beta"] = AnthropicBeta.oauth
            }
        }
        return h
    }

    // MARK: Refresh

    /// Call after a 401. Refreshes unless another caller already replaced the
    /// rejected token, then returns. Throws `APIError.signedOut` if the refresh
    /// token is rejected, or `APIError.unauthorized` in apiKey mode.
    public func handleUnauthorized(rejectedAuthorization: String?) async throws {
        guard let creds = credentials else { throw APIError.signedOut }
        guard creds.mode == .claudeAccount else { throw APIError.unauthorized }
        if let rejected = rejectedAuthorization, let current = creds.accessToken, rejected != "Bearer \(current)" {
            return // already refreshed by someone else
        }
        _ = try await refreshShared()
    }

    private func validCredentials() async throws -> Credentials {
        if let task = refreshTask { return try await task.value }
        guard let creds = credentials else { throw APIError.signedOut }
        if creds.isExpired(now: now()) { return try await refreshShared() }
        return creds
    }

    private func refreshShared() async throws -> Credentials {
        if let task = refreshTask { return try await task.value }
        guard let creds = credentials, let refreshToken = creds.refreshToken else {
            await signOut()
            throw APIError.signedOut
        }
        let task = Task<Credentials, any Error> { try await self.performRefresh(refreshToken: refreshToken, previous: creds) }
        refreshTask = task
        defer { if refreshTask == task { refreshTask = nil } }
        return try await task.value
    }

    private func performRefresh(refreshToken: String, previous: Credentials) async throws -> Credentials {
        do {
            let token = try await oauth.refresh(refreshToken: refreshToken)
            let updated = token.credentials(now: now(), previous: previous)
            // Persist first: the old refresh token is now spent (rotation).
            try await store.save(updated)
            credentials = updated
            return updated
        } catch let error as OAuthError {
            if case .rejected = error {
                // §2: a failed refresh (400/401) means signed out.
                await clearAfterFailedRefresh()
                throw APIError.signedOut
            }
            throw error
        }
    }

    private func clearAfterFailedRefresh() async {
        credentials = nil
        try? await store.clear()
    }
}
