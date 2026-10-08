import ClaudeWatchKit
import Foundation
import Observation

/// A local quick-chat thread backed by the Messages API (PROTOCOL §4).
@Observable
@MainActor
final class ChatViewModel: TranscriptModel, StreamOwner {
    private(set) var thread: ChatThread
    private(set) var streamingText: String?
    private(set) var isWorking = false
    var errorMessage: String?
    var presentedPermission: PendingPermission?

    @ObservationIgnored private let app: AppModel
    @ObservationIgnored private var replyTask: Task<Void, Never>?
    /// Identifies the in-flight reply so a cancelled task can't finalize a newer one.
    @ObservationIgnored private var replyID = UUID()

    init(app: AppModel, thread: ChatThread? = nil) {
        self.app = app
        self.thread = thread ?? ChatThread(createdAt: ChatThread.nowMillis(), model: app.settings.model.rawValue)
    }

    var title: String { thread.displayTitle }
    var headerSummary: String? { nil }
    var currentModelID: String? { thread.model }
    var lastReply: String? { thread.messages.last(where: { $0.role == .assistant })?.text }

    var items: [TranscriptItem] {
        var out = thread.messages.enumerated().map { index, message in
            TranscriptItem(
                id: "m\(index)", seq: index,
                kind: message.role == .user ? .user : .assistant,
                text: message.text
            )
        }
        if let streamingText {
            out.append(TranscriptItem(
                id: "streaming", seq: out.count, kind: .assistant, text: streamingText, isStreaming: true
            ))
        }
        return out
    }

    func onAppear() { app.lifecycle.register(self) }

    func onDisappear() {
        // Streams only run while the screen is visible; keep what arrived so far.
        cancelReply()
        app.lifecycle.unregister(self)
    }

    func pauseStreaming() { cancelReply() }
    func resumeStreaming() {} // never re-sent: it may already have been billed

    func send(_ text: String) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !isWorking else { return }
        errorMessage = nil
        Speech.shared.stop()
        thread.appendUser(trimmed, at: ChatThread.nowMillis())
        await save()
        startReply()
    }

    func setModel(_ model: ClaudeModel) async {
        thread.model = model.rawValue
        app.settings.model = model
        if !thread.messages.isEmpty { await save() }
    }

    // MARK: Streaming

    private func startReply() {
        let turns = thread.turns
        let model = ClaudeModel(rawValue: thread.model) ?? app.settings.model
        let effort = app.settings.effort
        let client = app.messages
        streamingText = ""
        isWorking = true
        let id = UUID()
        replyID = id
        replyTask = Task { [weak self] in
            var reduction = MessagesReduction()
            var failure: (any Error)?
            do {
                for try await event in client.stream(turns: turns, model: model, effort: effort) {
                    reduction.apply(event)
                    if self?.replyID == id { self?.streamingText = reduction.text }
                }
            } catch {
                failure = error
            }
            self?.finishReply(reduction, error: failure, id: id)
        }
    }

    private func finishReply(_ reduction: MessagesReduction, error: (any Error)?, id: UUID) {
        // Already finalized by cancelReply(), or superseded by a newer reply.
        guard replyTask != nil, replyID == id else { return }
        replyTask = nil
        isWorking = false
        streamingText = nil
        if let type = reduction.errorType {
            errorMessage = type == "overloaded_error"
                ? APIError.overloaded.userMessage
                : APIError.stream(type: type, message: reduction.errorMessage ?? "").userMessage
        } else if let error {
            errorMessage = app.message(for: error)
        }
        let text = reduction.displayText
        let hasReply = !reduction.text.isEmpty || reduction.stopReason == "refusal"
        if hasReply {
            thread.appendAssistant(text, stopReason: reduction.stopReason, at: ChatThread.nowMillis())
            if app.settings.readAloud { Speech.shared.speak(text) }
            Haptics.success()
        } else if errorMessage != nil {
            Haptics.failure()
        }
        Task { await save() }
    }

    /// Stops the stream and keeps the partial text (PLAN §6: partial text persisted).
    private func cancelReply() {
        guard let task = replyTask else { return }
        replyTask = nil
        replyID = UUID()
        isWorking = false
        task.cancel()
        if let partial = streamingText, !partial.isEmpty {
            thread.appendAssistant(partial + "…", stopReason: "cancelled", at: ChatThread.nowMillis())
        }
        streamingText = nil
        Task { await save() }
    }

    private func save() async {
        do {
            try await app.threads.save(thread)
        } catch {
            errorMessage = "Couldn't save this chat."
        }
    }
}
