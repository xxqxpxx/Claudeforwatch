import Foundation

/// Starts a predefined cloud session through the official routines "fire"
/// endpoint (docs/PROTOCOL.md §6). Uses the per-routine token the user pasted,
/// not the app's credentials.
public struct RoutinesClient: Sendable {
    public struct FireResult: Sendable, Equatable, Decodable {
        public var claudeCodeSessionId: String
        public var claudeCodeSessionUrl: String?

        enum CodingKeys: String, CodingKey {
            case claudeCodeSessionId = "claude_code_session_id"
            case claudeCodeSessionUrl = "claude_code_session_url"
        }
    }

    public enum RoutineError: Error, Sendable, Equatable {
        case invalidTriggerID
        case missingToken
    }

    public let http: any HTTPClient
    public let baseURL: URL
    public let userAgent: String

    public init(http: any HTTPClient, baseURL: URL = URL(string: "https://api.anthropic.com")!, userAgent: String = ClientInfo.defaultUserAgent) {
        self.http = http
        self.baseURL = baseURL
        self.userAgent = userAgent
    }

    public func fireRequest(triggerID: String, token: String, text: String) throws -> HTTPRequest {
        let trig = triggerID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trig.hasPrefix("trig_"), trig.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "_" || $0 == "-" }) else {
            throw RoutineError.invalidTriggerID
        }
        let tok = token.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !tok.isEmpty else { throw RoutineError.missingToken }
        return HTTPRequest(
            method: .post,
            url: baseURL.appendingPathComponent("v1/claude_code/routines/\(trig)/fire"),
            headers: [
                "Authorization": "Bearer \(tok)",
                "anthropic-version": ClientInfo.anthropicVersion,
                "Content-Type": "application/json",
                "User-Agent": userAgent,
            ],
            body: JSONValue.object(["text": .string(text)]).serializedData()
        )
    }

    /// Not retried: each fire starts a session (30/h per routine).
    public func fire(triggerID: String, token: String, text: String) async throws -> FireResult {
        let response = try await http.data(for: try fireRequest(triggerID: triggerID, token: token, text: text))
        guard response.isSuccess else {
            throw APIError.from(status: response.status, headers: response.headers, body: response.body)
        }
        do { return try JSONDecoder().decode(FireResult.self, from: response.body) } catch { throw APIError.malformedResponse }
    }
}
