import Foundation

/// Subscription usage (`GET /api/oauth/usage`, docs/PROTOCOL.md §2). UNOFFICIAL.
public struct Usage: Sendable, Equatable, Codable {
    public struct Window: Sendable, Equatable, Codable {
        /// 0–100.
        public var utilization: Double?
        public var resetsAt: String?

        enum CodingKeys: String, CodingKey {
            case utilization
            case resetsAt = "resets_at"
        }
    }

    public var fiveHour: Window?
    public var sevenDay: Window?

    enum CodingKeys: String, CodingKey {
        case fiveHour = "five_hour"
        case sevenDay = "seven_day"
    }

    /// The figure the watch shows: the tighter of the two windows, 0–100.
    public var headlinePercent: Int? {
        let values = [fiveHour?.utilization, sevenDay?.utilization].compactMap { $0 }
        guard let max = values.max() else { return nil }
        return Int(max.rounded())
    }
}

/// `GET /api/oauth/profile` (§2). UNOFFICIAL.
public struct Profile: Sendable, Equatable, Codable {
    public struct Account: Sendable, Equatable, Codable {
        public var uuid: String?
        public var emailAddress: String?
        public var displayName: String?

        enum CodingKeys: String, CodingKey {
            case uuid
            case emailAddress = "email_address"
            case displayName = "display_name"
        }
    }

    public struct Organization: Sendable, Equatable, Codable {
        public var uuid: String?
        public var name: String?
    }

    public var account: Account?
    public var organization: Organization?
}

public struct UsageClient: Sendable {
    public let transport: AuthorizedTransport
    public let baseURL: URL

    public init(http: any HTTPClient, auth: AuthProvider, baseURL: URL = URL(string: "https://api.anthropic.com")!) {
        self.transport = AuthorizedTransport(http: http, auth: auth)
        self.baseURL = baseURL
    }

    public func usage() async throws -> Usage {
        let r = try await transport.send(.oauthAccount, method: .get, url: baseURL.appendingPathComponent("api/oauth/usage"))
        do { return try JSONDecoder().decode(Usage.self, from: r.body) } catch { throw APIError.malformedResponse }
    }

    public func profile() async throws -> Profile {
        let r = try await transport.send(.oauthAccount, method: .get, url: baseURL.appendingPathComponent("api/oauth/profile"))
        do { return try JSONDecoder().decode(Profile.self, from: r.body) } catch { throw APIError.malformedResponse }
    }
}
