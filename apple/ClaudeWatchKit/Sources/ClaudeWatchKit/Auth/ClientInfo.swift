import Foundation

/// Values shared by every request (docs/PROTOCOL.md §3).
public enum ClientInfo {
    public static let anthropicVersion = "2023-06-01"
    public static let appVersion = "0.1.0"

    public static var defaultPlatform: String {
        #if os(watchOS)
        return "watchOS"
        #elseif os(iOS)
        return "iOS"
        #elseif os(macOS)
        return "macOS"
        #else
        return "Linux"
        #endif
    }

    /// `ClaudeForWatch/<version> (<platform>)`
    public static func userAgent(version: String = appVersion, platform: String = defaultPlatform) -> String {
        "ClaudeForWatch/\(version) (\(platform))"
    }

    public static var defaultUserAgent: String { userAgent() }
}

/// `anthropic-beta` values used by the unofficial endpoints.
public enum AnthropicBeta {
    /// OAuth bearer tokens (refresh, usage/profile, Messages). §2, §4.
    public static let oauth = "oauth-2025-04-20"
    /// Claude Code identity for subscription inference. §4.
    public static let claudeCode = "claude-code-20250219"
    /// Claude Code sessions ("CCR BYOC") API, except the bare list. §5.
    public static let sessions = "ccr-byoc-2025-07-29"
}
