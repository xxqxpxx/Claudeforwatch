import ClaudeWatchKit
import Foundation
import Observation

/// Non-secret preferences (UserDefaults). Secrets live in the Keychain.
@Observable
@MainActor
final class AppSettings {
    private enum Key {
        static let model = "model"
        static let effort = "effort"
        static let readAloud = "readAloud"
        static let routineTriggerID = "routineTriggerID"
        static let warningAccepted = "accountWarningAccepted"
        static let installID = "installID"
    }

    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let routineTokens = RoutineTokenStore()

    var model: ClaudeModel { didSet { defaults.set(model.rawValue, forKey: Key.model) } }
    var effort: Effort { didSet { defaults.set(effort.rawValue, forKey: Key.effort) } }
    var readAloud: Bool { didSet { defaults.set(readAloud, forKey: Key.readAloud) } }
    var routineTriggerID: String { didSet { defaults.set(routineTriggerID, forKey: Key.routineTriggerID) } }
    var accountWarningAccepted: Bool { didSet { defaults.set(accountWarningAccepted, forKey: Key.warningAccepted) } }
    /// Whether a routine token is stored (the token itself stays in the Keychain).
    private(set) var hasRoutineToken: Bool
    /// Stable per-install id for session presence (PROTOCOL §5.6).
    let installID: String

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        model = defaults.string(forKey: Key.model).flatMap(ClaudeModel.init(rawValue:)) ?? .default
        effort = defaults.string(forKey: Key.effort).flatMap(Effort.init(rawValue:)) ?? .default
        readAloud = defaults.bool(forKey: Key.readAloud)
        routineTriggerID = defaults.string(forKey: Key.routineTriggerID) ?? ""
        accountWarningAccepted = defaults.bool(forKey: Key.warningAccepted)
        if let id = defaults.string(forKey: Key.installID) {
            installID = id
        } else {
            let id = UUID().uuidString.lowercased()
            defaults.set(id, forKey: Key.installID)
            installID = id
        }
        hasRoutineToken = routineTokens.load() != nil
    }

    var routineToken: String? { routineTokens.load() }

    func setRoutineToken(_ token: String?) {
        let trimmed = token?.trimmingCharacters(in: .whitespacesAndNewlines)
        routineTokens.save(trimmed)
        hasRoutineToken = !(trimmed ?? "").isEmpty
    }

    var canStartCloudSessions: Bool { hasRoutineToken && routineTriggerID.hasPrefix("trig_") }

    func clearAccountData() {
        setRoutineToken(nil)
        routineTriggerID = ""
    }
}
