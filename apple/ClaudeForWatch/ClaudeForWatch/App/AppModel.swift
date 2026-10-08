import ClaudeWatchKit
import Foundation
import Observation
import SwiftUI
import WidgetKit

/// Root state: auth, shared clients, settings, tab selection, widget snapshot.
@Observable
@MainActor
final class AppModel {
    enum Tab: Hashable { case sessions, chats, ask }

    let settings = AppSettings()
    let lifecycle = StreamLifecycle()
    let http: any HTTPClient
    let auth: AuthProvider
    let messages: MessagesClient
    let sessions: SessionsClient
    let usageClient: UsageClient
    let routines: RoutinesClient
    let threads: any ThreadStore

    private(set) var authState: AuthState = .signedOut
    private(set) var isLoaded = false
    private(set) var usage: Usage?
    private(set) var needsActionCount = 0
    var selectedTab: Tab = .chats
    /// A question from Siri / the widget waiting for the Ask tab.
    var pendingAsk: String?
    /// Bumped when a session should be opened (e.g. after a routine fired).
    var openSessionID: String?

    @ObservationIgnored private var askObserver: (any NSObjectProtocol)?
    @ObservationIgnored private var lastUsageFetch: Date?

    init() {
        let http = URLSessionHTTPClient()
        let userAgent = ClientInfo.userAgent(version: BuildFlags.appVersion, platform: "watchOS")
        let auth = AuthProvider(
            store: KeychainTokenStore(),
            oauth: OAuthClient(http: http, userAgent: userAgent),
            userAgent: userAgent
        )
        self.http = http
        self.auth = auth
        messages = MessagesClient(http: http, auth: auth)
        sessions = SessionsClient(http: http, auth: auth)
        usageClient = UsageClient(http: http, auth: auth)
        routines = RoutinesClient(http: http, userAgent: userAgent)
        let documents = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        threads = JSONFileThreadStore(directory: documents.appendingPathComponent("Threads"))
    }

    // MARK: Derived state

    var mode: AuthMode? { authState.mode }

    /// Sessions exist only in personal builds signed in with a Claude account.
    var showsSessions: Bool { BuildFlags.personalMode && mode == .claudeAccount }

    // MARK: Lifecycle

    func start() async {
        guard !isLoaded else { return }
        do {
            authState = try await auth.load()
        } catch {
            authState = .signedOut
        }
        // A non-personal build never acts on a Claude-account credential.
        if !BuildFlags.personalMode, authState.mode == .claudeAccount {
            await auth.signOut()
            authState = .signedOut
        }
        selectedTab = showsSessions ? .sessions : .chats
        isLoaded = true
        askObserver = NotificationCenter.default.addObserver(
            forName: .pendingAskAvailable, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.consumePendingAsk() }
        }
        consumePendingAsk()
        await refreshUsage()
        writeWidgetSnapshot()
    }

    func scenePhaseChanged(_ phase: ScenePhase) {
        switch phase {
        case .active:
            lifecycle.resumeAll()
            consumePendingAsk()
        case .background:
            lifecycle.pauseAll()
            Speech.shared.stop()
        default:
            break // .inactive (wrist down, always-on): keep going until background
        }
    }

    func handle(url: URL) {
        guard url.scheme == "claudeforwatch" else { return }
        switch url.host {
        case "sessions" where showsSessions: selectedTab = .sessions
        case "ask": selectedTab = .ask
        default: selectedTab = showsSessions ? .sessions : .chats
        }
    }

    func consumePendingAsk() {
        guard let dir = SharedContainer.directory,
              let ask = SharedJSONFile.take(PendingAsk.self, named: PendingAsk.fileName, in: dir),
              ask.isFresh(), authState != .signedOut else { return }
        pendingAsk = ask.text
        selectedTab = .ask
    }

    // MARK: Auth

    /// Validates the key with a 1-token call before storing it (docs/PLAN.md §3).
    func signIn(apiKey: String) async throws {
        let key = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        try await MessagesClient.validate(apiKey: key, http: http)
        try await auth.signIn(apiKey: key)
        authState = await auth.state
        selectedTab = .chats
        writeWidgetSnapshot()
    }

    func completeClaudeSignIn(pasted: String, pkce: PKCE) async throws {
        guard BuildFlags.personalMode else { return }
        try await auth.completeOAuth(pasted: pasted, pkce: pkce)
        if let profile = try? await usageClient.profile() {
            try? await auth.updateProfile(email: profile.account?.emailAddress, organizationUuid: profile.organization?.uuid)
        }
        authState = await auth.state
        selectedTab = .sessions
        await refreshUsage(force: true)
        writeWidgetSnapshot()
    }

    func signOut() async {
        Speech.shared.stop()
        await auth.signOut()
        settings.clearAccountData()
        authState = .signedOut
        usage = nil
        lastUsageFetch = nil
        needsActionCount = 0
        selectedTab = .chats
        writeWidgetSnapshot()
    }

    /// User-facing copy for an error; drops to the signed-out state when the
    /// credentials are gone (failed refresh).
    func message(for error: any Error) -> String {
        if let api = error as? APIError {
            if api == .signedOut {
                Task { await self.syncAuthState() }
            }
            return api.userMessage
        }
        if let oauth = error as? OAuthError { return Self.message(for: oauth) }
        if error is CancellationError { return "Cancelled." }
        if let url = error as? URLError {
            switch url.code {
            case .notConnectedToInternet, .networkConnectionLost: return "No connection."
            case .timedOut: return "The connection timed out."
            default: return "Network error."
            }
        }
        return "Something went wrong."
    }

    static func message(for error: OAuthError) -> String {
        switch error {
        case .emptyCode: return "Paste the code shown after signing in."
        case .missingState, .stateMismatch: return "That code is from a different sign-in. Scan the new QR code."
        case .expired: return "Sign-in expired. Scan the new QR code."
        case .rejected: return "Claude rejected the code. Try again."
        case .http(let status): return "Sign-in failed (\(status))."
        case .malformedResponse: return "Unexpected sign-in response."
        }
    }

    func syncAuthState() async {
        authState = await auth.state
        if authState == .signedOut {
            usage = nil
            needsActionCount = 0
            writeWidgetSnapshot()
        }
    }

    // MARK: Routines (PROTOCOL §6, official API)

    /// Starts a predefined cloud session with the user's routine token. Never retried.
    func fireRoutine(task: String) async throws -> RoutinesClient.FireResult {
        guard let token = settings.routineToken else { throw RoutinesClient.RoutineError.missingToken }
        return try await routines.fire(triggerID: settings.routineTriggerID, token: token, text: task)
    }

    // MARK: Usage and widget

    /// Usage changes slowly; fetch at most every 5 minutes unless forced.
    func refreshUsage(force: Bool = false) async {
        guard showsSessions else { usage = nil; return }
        if !force, let last = lastUsageFetch, Date().timeIntervalSince(last) < 5 * 60 { return }
        lastUsageFetch = Date()
        if let fresh = try? await usageClient.usage() { usage = fresh }
        writeWidgetSnapshot()
    }

    func updateNeedsAction(count: Int) {
        guard count != needsActionCount else { return }
        needsActionCount = count
        writeWidgetSnapshot()
    }

    func writeWidgetSnapshot() {
        guard let dir = SharedContainer.directory else { return }
        let snapshot = WidgetSnapshot(
            needsActionCount: showsSessions ? needsActionCount : nil,
            usagePercent: usage?.headlinePercent,
            signedIn: authState != .signedOut,
            updatedAt: ChatThread.nowMillis()
        )
        if SharedJSONFile.read(WidgetSnapshot.self, named: WidgetSnapshot.fileName, in: dir).map({
            $0.needsActionCount == snapshot.needsActionCount && $0.usagePercent == snapshot.usagePercent
                && $0.signedIn == snapshot.signedIn
        }) == true { return }
        try? SharedJSONFile.write(snapshot, named: WidgetSnapshot.fileName, in: dir)
        WidgetCenter.shared.reloadTimelines(ofKind: SharedContainer.widgetKind)
    }
}
