import Foundation

/// Errors surfaced to the UI. `userMessage` is watch-sized copy.
public enum APIError: Error, Sendable, Equatable {
    case signedOut
    /// The endpoint needs a Claude account (sessions, usage) but an API key is in use.
    case notAvailableInMode
    /// The OAuth record has no organization UUID (sessions need `x-organization-uuid`).
    case missingOrganization
    /// 401 with an API key, or still 401 after a refresh.
    case unauthorized
    /// 429; `retryAfter` is the raw `retry-after` header.
    case rateLimited(retryAfter: String?)
    /// 529.
    case overloaded
    /// 400 "only authorized for use with Claude Code" (§4).
    case claudeCodeOnly
    /// 403 mentioning trusted devices (§5).
    case trustedDeviceRequired
    case notFound
    /// Any other HTTP error, with the API's `error.type`/`error.message` if present.
    case http(status: Int, type: String?, message: String?)
    /// A streamed `error` event (§4).
    case stream(type: String, message: String)
    case malformedResponse

    public var userMessage: String {
        switch self {
        case .signedOut: return "Signed out. Sign in again in Settings."
        case .notAvailableInMode: return "This needs a Claude account sign-in."
        case .missingOrganization: return "No organization on this account. Sign in again."
        case .unauthorized: return "Not authorized. Check your API key."
        case .rateLimited(let retryAfter):
            if let retryAfter, !retryAfter.isEmpty { return "Rate limited. Try again in \(retryAfter) s." }
            return "Rate limited. Try again shortly."
        case .overloaded: return "Claude is overloaded. Try again soon."
        case .claudeCodeOnly:
            return "Anthropic only allows this account sign-in for Claude Code. Use an API key instead."
        case .trustedDeviceRequired:
            return "This organization requires Trusted Devices; enroll from claude.ai"
        case .notFound: return "Not found."
        case .http(let status, _, let message):
            if let message, !message.isEmpty { return message }
            return "Request failed (\(status))."
        case .stream(_, let message): return message.isEmpty ? "The reply failed." : message
        case .malformedResponse: return "Unexpected response from the server."
        }
    }

    /// Maps a non-2xx response per docs/PROTOCOL.md §4/§5.
    public static func from(status: Int, headers: [String: String], body: Data) -> APIError {
        let json = try? JSONValue.parse(body)
        let type = json?["error"]?["type"]?.stringValue ?? json?["error"]?.stringValue
        let message = json?["error"]?["message"]?.stringValue ?? json?["message"]?.stringValue
        let lowerBody = String(decoding: body.prefix(8192), as: UTF8.self).lowercased()
        switch status {
        case 401:
            return .unauthorized
        case 403 where lowerBody.contains("trusted device") || lowerBody.contains("trusted_device"):
            return .trustedDeviceRequired
        case 404:
            return .notFound
        case 429:
            let retry = headers.first(where: { $0.key.caseInsensitiveCompare("retry-after") == .orderedSame })?.value
            return .rateLimited(retryAfter: retry)
        case 529:
            return .overloaded
        case 400 where lowerBody.contains("only authorized for use with claude code"):
            return .claudeCodeOnly
        default:
            if type == "overloaded_error" { return .overloaded }
            return .http(status: status, type: type, message: message)
        }
    }
}
