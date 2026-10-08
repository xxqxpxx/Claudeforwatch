import ClaudeWatchKit
import Foundation
import Observation

/// Sessions tab: list, 20 s polling while visible (PROTOCOL §5.1).
@Observable
@MainActor
final class SessionsListViewModel: StreamOwner {
    static let pollInterval: Duration = .seconds(20)

    private(set) var summary = SessionListSummary(visible: [], hidden: [], needsActionCount: 0)
    private(set) var isLoading = false
    private(set) var hasLoaded = false
    private(set) var isStarting = false
    var errorMessage: String?

    @ObservationIgnored private let app: AppModel
    @ObservationIgnored private var pollTask: Task<Void, Never>?
    @ObservationIgnored private var visible = false

    init(app: AppModel) {
        self.app = app
    }

    var sessions: [Session] { summary.visible }

    func onAppear() {
        visible = true
        app.lifecycle.register(self)
        startPolling()
    }

    func onDisappear() {
        visible = false
        stopPolling()
        app.lifecycle.unregister(self)
    }

    func pauseStreaming() { stopPolling() }

    func resumeStreaming() {
        if visible { startPolling() }
    }

    func refresh() async {
        guard !isLoading else { return }
        isLoading = true
        defer { isLoading = false; hasLoaded = true }
        do {
            let list = try await app.sessions.list()
            summary = SessionListReducer.reduce(list.data)
            app.updateNeedsAction(count: summary.needsActionCount)
            errorMessage = nil
        } catch {
            errorMessage = app.message(for: error)
        }
    }

    func archive(_ session: Session) async {
        do {
            try await app.sessions.archive(session.id)
            await refresh()
        } catch {
            errorMessage = app.message(for: error)
        }
    }

    /// Starts a cloud session via the user's routine (PROTOCOL §6). Returns the new session id.
    func startCloudSession(task: String) async -> String? {
        let text = task.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        isStarting = true
        defer { isStarting = false }
        do {
            let result = try await app.fireRoutine(task: text)
            await refresh()
            return result.claudeCodeSessionId
        } catch {
            errorMessage = (error as? RoutinesClient.RoutineError) != nil
                ? "Check the routine ID and token in Settings."
                : app.message(for: error)
            return nil
        }
    }

    private func startPolling() {
        guard app.lifecycle.isActive else { return }
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refresh()
                await self?.app.refreshUsage()
                try? await Task.sleep(for: SessionsListViewModel.pollInterval)
            }
        }
    }

    private func stopPolling() {
        pollTask?.cancel()
        pollTask = nil
    }
}
