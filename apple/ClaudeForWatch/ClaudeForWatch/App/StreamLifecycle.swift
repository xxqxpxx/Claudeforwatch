import Foundation

/// Something that holds a network stream or a polling loop.
@MainActor
protocol StreamOwner: AnyObject {
    /// App went to the background: cancel streams, persist partial state.
    func pauseStreaming()
    /// App is active again: reconnect if still on screen.
    func resumeStreaming()
}

/// Fans scene-phase changes out to whichever screens currently hold streams.
/// Streams run only while the app is active (docs/PLAN.md §4); no extended
/// runtime sessions.
@MainActor
final class StreamLifecycle {
    private struct WeakOwner {
        weak var value: (any StreamOwner)?
    }

    private var owners: [ObjectIdentifier: WeakOwner] = [:]
    private(set) var isActive = true

    func register(_ owner: any StreamOwner) {
        owners[ObjectIdentifier(owner)] = WeakOwner(value: owner)
    }

    func unregister(_ owner: any StreamOwner) {
        owners[ObjectIdentifier(owner)] = nil
    }

    func pauseAll() {
        guard isActive else { return }
        isActive = false
        for owner in liveOwners() { owner.pauseStreaming() }
    }

    func resumeAll() {
        guard !isActive else { return }
        isActive = true
        for owner in liveOwners() { owner.resumeStreaming() }
    }

    private func liveOwners() -> [any StreamOwner] {
        owners = owners.filter { $0.value.value != nil }
        return owners.values.compactMap(\.value)
    }
}
