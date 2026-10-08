import ClaudeWatchKit
import Foundation
import Observation

/// What `TranscriptView` needs; implemented by session and chat view models
/// so both screens share one view (docs/PLAN.md §2).
@MainActor
protocol TranscriptModel: AnyObject, Observable, Sendable {
    var title: String { get }
    /// `post_turn_summary` for sessions (PROTOCOL §5.3).
    var headerSummary: String? { get }
    var items: [TranscriptItem] { get }
    var isWorking: Bool { get }
    var errorMessage: String? { get set }
    var quickReplies: [String] { get }
    var pendingPermission: PendingPermission? { get }
    /// The permission currently shown full-screen (nil once dismissed).
    var presentedPermission: PendingPermission? { get set }
    var supportsSessionControls: Bool { get }
    var currentModelID: String? { get }
    /// Set after archiving so the view pops itself.
    var isClosed: Bool { get }

    func onAppear()
    func onDisappear()
    func send(_ text: String) async
    func setModel(_ model: ClaudeModel) async
    func interrupt() async
    func setPermissionMode(_ mode: SessionsClient.PermissionMode) async
    func archive() async
    func answerPermission(allow: Bool) async
    func answerQuestion(_ label: String) async
}

extension TranscriptModel {
    var quickReplies: [String] { [] }
    var pendingPermission: PendingPermission? { nil }
    var supportsSessionControls: Bool { false }
    var isClosed: Bool { false }
    func interrupt() async {}
    func setPermissionMode(_ mode: SessionsClient.PermissionMode) async {}
    func archive() async {}
    func answerPermission(allow: Bool) async {}
    func answerQuestion(_ label: String) async {}

    /// Rows worth drawing: successful turn ends are invisible.
    var visibleItems: [TranscriptItem] {
        items.filter { !($0.kind == .turnEnd && $0.isError != true) }
    }
}
