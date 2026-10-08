import Foundation

/// The App Group container shared by the app, the widget extension and the
/// App Intent. Only small, non-secret JSON lives here (widget snapshot,
/// pending Siri question). Secrets stay in the Keychain.
enum SharedContainer {
    static let appGroupID = "group.com.claudeforwatch"

    /// `nil` if the App Group entitlement is missing (e.g. unsigned builds).
    static var directory: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupID)
    }

    static let widgetKind = "ClaudeStatus"
    static let sessionsURL = URL(string: "claudeforwatch://sessions")!
    static let askURL = URL(string: "claudeforwatch://ask")!
}

extension Notification.Name {
    /// Posted in-process by `AskClaudeIntent` after it stores a question.
    static let pendingAskAvailable = Notification.Name("com.claudeforwatch.pendingAskAvailable")
}
