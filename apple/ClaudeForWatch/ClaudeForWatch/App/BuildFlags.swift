import Foundation

enum BuildFlags {
    /// `PERSONAL_MODE` is set only by the `Personal` build configuration.
    /// It unlocks the unofficial Claude-account sign-in, Sessions and usage.
    #if PERSONAL_MODE
    static let personalMode = true
    #else
    static let personalMode = false
    #endif

    static var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1.0"
    }
}

/// docs/PROTOCOL.md §1.2, shown once before enabling claudeAccount mode.
let accountSignInWarning = """
Signing in with your Claude account uses the same private sign-in that \
Claude Code and the Claude app use. Anthropic does not support third-party \
apps using it and may block or suspend accounts that do. Use it only with \
your own account, at your own risk. The supported option is an API key.
"""
