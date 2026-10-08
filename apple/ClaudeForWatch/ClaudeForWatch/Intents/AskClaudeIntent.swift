import AppIntents
import ClaudeWatchKit
import Foundation

/// "Ask Claude" via Siri / Shortcuts. Siri gives an intent only ~10–30 s, so
/// the intent stores the question in the App Group and opens the app, which
/// streams the answer on the Ask tab (docs/research/platform-notes.md).
struct AskClaudeIntent: AppIntent {
    static let title: LocalizedStringResource = "Ask Claude"
    static var description: IntentDescription? {
        IntentDescription("Ask Claude a question and read the answer on your watch.")
    }
    static let openAppWhenRun: Bool = true

    @Parameter(title: "Question", requestValueDialog: IntentDialog("What do you want to ask?"))
    var question: String

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let text = question.trimmingCharacters(in: .whitespacesAndNewlines)
        if !text.isEmpty, let dir = SharedContainer.directory {
            try? SharedJSONFile.write(
                PendingAsk(text: text, createdAt: ChatThread.nowMillis()),
                named: PendingAsk.fileName,
                in: dir
            )
            // App Intents run in the app's process on watchOS 9+: tell a running app now.
            NotificationCenter.default.post(name: .pendingAskAvailable, object: nil)
        }
        return .result(dialog: "Asking Claude…")
    }
}

struct ClaudeShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: AskClaudeIntent(),
            phrases: ["Ask \(.applicationName)"],
            shortTitle: "Ask Claude",
            systemImageName: "bubble.left.and.bubble.right"
        )
    }
}
