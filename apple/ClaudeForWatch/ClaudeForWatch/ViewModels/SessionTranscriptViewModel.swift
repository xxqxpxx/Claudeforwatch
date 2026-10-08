import ClaudeWatchKit
import Foundation
import Observation

/// One Claude Code session: history + live SSE, send, steer, permissions.
/// UNOFFICIAL API (docs/PROTOCOL.md §5); claudeAccount + PERSONAL_MODE only.
@Observable
@MainActor
final class SessionTranscriptViewModel: TranscriptModel, StreamOwner {
    let sessionID: String
    private(set) var session: Session?
    private(set) var state = TranscriptState()
    private(set) var isLoading = false
    private(set) var isSending = false
    private(set) var isClosed = false
    var errorMessage: String?
    var presentedPermission: PendingPermission?

    @ObservationIgnored private let app: AppModel
    @ObservationIgnored private var streamTask: Task<Void, Never>?
    @ObservationIgnored private var visible = false
    @ObservationIgnored private var shownRequestIDs: Set<String> = []

    init(app: AppModel, sessionID: String, session: Session? = nil) {
        self.app = app
        self.sessionID = sessionID
        self.session = session
    }

    // MARK: TranscriptModel

    var title: String { session?.displayTitle ?? "Session" }
    var headerSummary: String? { session?.externalMetadata?.postTurnSummary }
    var items: [TranscriptItem] { state.items }
    var isWorking: Bool { isSending || state.isTurnInProgress || state.streamingText != nil }
    var quickReplies: [String] { SessionsClient.quickReplies }
    var pendingPermission: PendingPermission? { state.pendingPermission }
    var supportsSessionControls: Bool { true }
    var currentModelID: String? { state.model ?? session?.model }

    func onAppear() {
        visible = true
        app.lifecycle.register(self)
        Task { await load() }
        presence(connected: true)
    }

    func onDisappear() {
        visible = false
        stopStream()
        app.lifecycle.unregister(self)
        presence(connected: false)
    }

    func pauseStreaming() { stopStream() }

    func resumeStreaming() {
        guard visible else { return }
        Task { await load() }
    }

    // MARK: Loading and streaming (§5.2)

    func load() async {
        guard !isLoading else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            if state.lastSequence == nil || state.needsHistoryRefetch {
                if state.needsHistoryRefetch { state.reset() }
                apply(history: try await app.sessions.history(id: sessionID))
            }
            if let fresh = try? await app.sessions.get(id: sessionID) { session = fresh }
            errorMessage = nil
            startStream()
            try? await app.sessions.markRead(sessionID)
        } catch {
            errorMessage = app.message(for: error)
        }
    }

    private func apply(history: [EventFrame]) {
        state.apply(history: history)
        surfacePermissionIfNeeded()
    }

    private func startStream() {
        guard visible, app.lifecycle.isActive else { return }
        streamTask?.cancel()
        let client = app.sessions
        let id = sessionID
        streamTask = Task { [weak self] in
            var delay: Duration = .seconds(1)
            while !Task.isCancelled {
                // nil for a brand-new session with no history: stream from the start.
                guard let from = self.map({ $0.state.lastSequence }) else { return }
                do {
                    for try await sse in client.events(id: id, fromSequence: from) {
                        guard let self else { return }
                        self.state.apply(sse: sse)
                        self.surfacePermissionIfNeeded()
                        delay = .seconds(1)
                        if self.state.needsHistoryRefetch {
                            Task { await self.load() }
                            return
                        }
                    }
                } catch is CancellationError {
                    return
                } catch let error as APIError where [.signedOut, .trustedDeviceRequired, .notFound].contains(error) {
                    self?.errorMessage = self?.app.message(for: error)
                    return
                } catch {
                    // Dropped connection: reconnect from the last sequence number.
                }
                try? await Task.sleep(for: delay)
                delay = min(delay * 2, .seconds(30))
            }
        }
    }

    private func stopStream() {
        streamTask?.cancel()
        streamTask = nil
    }

    private func surfacePermissionIfNeeded() {
        guard let pending = state.pendingPermission else {
            presentedPermission = nil
            return
        }
        guard !shownRequestIDs.contains(pending.requestId) else { return }
        shownRequestIDs.insert(pending.requestId)
        presentedPermission = pending
        Haptics.attention()
    }

    private func presence(connected: Bool) {
        let client = app.sessions
        let id = sessionID
        let installID = app.settings.installID
        Task { try? await client.presence(id, clientID: installID, connected: connected) } // best effort
    }

    // MARK: Actions (§5.4–§5.6)

    func send(_ text: String) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        await perform { try await $0.send(text: trimmed, to: $1) }
    }

    func interrupt() async {
        await perform { try await $0.interrupt($1) }
    }

    func setModel(_ model: ClaudeModel) async {
        await perform { try await $0.setModel(model.rawValue, for: $1) }
    }

    func setPermissionMode(_ mode: SessionsClient.PermissionMode) async {
        await perform { try await $0.setPermissionMode(mode, for: $1) }
    }

    func archive() async {
        await perform { try await $0.archive($1) }
        if errorMessage == nil { isClosed = true }
    }

    func answerPermission(allow: Bool) async {
        guard let permission = state.pendingPermission else { return }
        presentedPermission = nil
        await perform { try await $0.respond(to: permission, allow: allow, in: $1) }
        if errorMessage == nil {
            state.resolvePermission(requestId: permission.requestId)
            if allow { Haptics.success() } else { Haptics.tap() }
        }
    }

    /// AskUserQuestion: the chosen label goes back as a normal user message (§5.5).
    func answerQuestion(_ label: String) async {
        guard let permission = state.pendingPermission else { return }
        presentedPermission = nil
        await perform { try await $0.send(text: label, to: $1) }
        if errorMessage == nil { state.resolvePermission(requestId: permission.requestId) }
    }

    /// Runs a write once (never auto-retried) and surfaces failures.
    private func perform(_ action: @Sendable (SessionsClient, String) async throws -> Void) async {
        isSending = true
        defer { isSending = false }
        do {
            try await action(app.sessions, sessionID)
            errorMessage = nil
        } catch {
            errorMessage = app.message(for: error)
            Haptics.failure()
        }
    }
}
