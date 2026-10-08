import Foundation

/// Which credential the app signs requests with. See docs/PROTOCOL.md §1.
public enum AuthMode: String, Codable, Sendable, CaseIterable {
    /// Console API key (`sk-ant-api03-…`). Supported, the default.
    case apiKey
    /// Claude Code OAuth tokens. Unofficial; personal builds only (PERSONAL_MODE).
    case claudeAccount
}

/// The stored credential record, docs/PROTOCOL.md §1.1. Persisted as JSON in
/// the Keychain (watchOS) / encrypted DataStore (Wear OS). Never log it.
public struct Credentials: Codable, Sendable, Equatable {
    public var mode: AuthMode
    public var accessToken: String?
    public var refreshToken: String?
    /// Epoch milliseconds after which the access token is treated as expired
    /// (already includes the 60 s safety margin from §2).
    public var expiresAt: Int64?
    public var scopes: [String]
    public var organizationUuid: String?
    public var accountEmail: String?
    public var apiKey: String?

    public init(
        mode: AuthMode,
        accessToken: String? = nil,
        refreshToken: String? = nil,
        expiresAt: Int64? = nil,
        scopes: [String] = [],
        organizationUuid: String? = nil,
        accountEmail: String? = nil,
        apiKey: String? = nil
    ) {
        self.mode = mode
        self.accessToken = accessToken
        self.refreshToken = refreshToken
        self.expiresAt = expiresAt
        self.scopes = scopes
        self.organizationUuid = organizationUuid
        self.accountEmail = accountEmail
        self.apiKey = apiKey
    }

    public static func apiKey(_ key: String) -> Credentials {
        Credentials(mode: .apiKey, apiKey: key)
    }

    enum CodingKeys: String, CodingKey {
        case mode, accessToken, refreshToken, expiresAt, scopes, organizationUuid, accountEmail, apiKey
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        mode = try c.decode(AuthMode.self, forKey: .mode)
        accessToken = try c.decodeIfPresent(String.self, forKey: .accessToken)
        refreshToken = try c.decodeIfPresent(String.self, forKey: .refreshToken)
        expiresAt = try c.decodeIfPresent(Int64.self, forKey: .expiresAt)
        scopes = try c.decodeIfPresent([String].self, forKey: .scopes) ?? []
        organizationUuid = try c.decodeIfPresent(String.self, forKey: .organizationUuid)
        accountEmail = try c.decodeIfPresent(String.self, forKey: .accountEmail)
        apiKey = try c.decodeIfPresent(String.self, forKey: .apiKey)
    }

    /// Encodes every key, `null` included, so both platforms store the same shape.
    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(mode, forKey: .mode)
        try c.encode(accessToken, forKey: .accessToken)
        try c.encode(refreshToken, forKey: .refreshToken)
        try c.encode(expiresAt, forKey: .expiresAt)
        try c.encode(scopes, forKey: .scopes)
        try c.encode(organizationUuid, forKey: .organizationUuid)
        try c.encode(accountEmail, forKey: .accountEmail)
        try c.encode(apiKey, forKey: .apiKey)
    }

    /// True when a claudeAccount token must be refreshed before use (§2: `now >= expiresAt`).
    public func isExpired(now: Date) -> Bool {
        guard mode == .claudeAccount else { return false }
        guard let expiresAt else { return true }
        return Int64(now.timeIntervalSince1970 * 1000) >= expiresAt
    }

    public var hasSessionsScope: Bool { scopes.contains("user:sessions:claude_code") }
}

extension Credentials: CustomStringConvertible, CustomDebugStringConvertible {
    /// Redacted on purpose: secrets must never reach logs.
    public var description: String {
        "Credentials(mode: \(mode.rawValue), account: \(accountEmail ?? "-"), expiresAt: \(expiresAt.map(String.init) ?? "-"))"
    }

    public var debugDescription: String { description }
}
