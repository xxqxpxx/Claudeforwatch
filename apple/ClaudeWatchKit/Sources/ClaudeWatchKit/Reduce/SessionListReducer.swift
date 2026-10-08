import Foundation

public struct SessionListSummary: Sendable, Equatable {
    public var visible: [Session]
    public var hidden: [Session]
    public var needsActionCount: Int

    public init(visible: [Session] = [], hidden: [Session] = [], needsActionCount: Int = 0) {
        self.visible = visible
        self.hidden = hidden
        self.needsActionCount = needsActionCount
    }
}

/// Sorting and filtering for the Sessions tab (docs/PROTOCOL.md §5.1, spec/README.md).
public enum SessionListReducer {
    /// Drops archived sessions (unless `showArchived`), orders
    /// `requires_action` → `running` → `last_event_at` desc, counts needs-action.
    public static func reduce(_ sessions: [Session], showArchived: Bool = false) -> SessionListSummary {
        let hidden = showArchived ? [] : sessions.filter(\.isArchived)
        let shown = showArchived ? sessions : sessions.filter { !$0.isArchived }
        return SessionListSummary(
            visible: sort(shown),
            hidden: hidden,
            needsActionCount: sessions.filter { !$0.isArchived && $0.needsAction }.count
        )
    }

    public static func sort(_ sessions: [Session]) -> [Session] {
        sessions.sorted { a, b in
            let ra = rank(a), rb = rank(b)
            if ra != rb { return ra < rb }
            let da = a.lastEventDate, db = b.lastEventDate
            switch (da, db) {
            case let (x?, y?) where x != y: return x > y
            case (.some, nil): return true
            case (nil, .some): return false
            default:
                if a.lastEventAt != b.lastEventAt { return (a.lastEventAt ?? "") > (b.lastEventAt ?? "") }
                return a.id < b.id
            }
        }
    }

    public static func kind(of session: Session) -> SessionKind { session.kind }

    static func rank(_ s: Session) -> Int {
        switch s.workerStatus {
        case .requiresAction?: return 0
        case .running?: return 1
        default: return 2
        }
    }
}
