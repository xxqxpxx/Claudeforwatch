import ClaudeWatchKit
import SwiftUI

struct SettingsView: View {
    @Environment(AppModel.self) private var app
    @State private var confirmSignOut = false

    var body: some View {
        @Bindable var settings = app.settings
        List {
            Section("Account") {
                LabeledContent("Mode", value: modeName)
                if case .claudeAccount(let email?) = app.authState {
                    Text(email).font(.footnote).foregroundStyle(.secondary)
                }
                Button("Sign out", role: .destructive) { confirmSignOut = true }
                    .frame(minHeight: 34)
            }

            Section("Chat") {
                Picker("Model", selection: $settings.model) {
                    ForEach(ClaudeModel.allCases) { Text($0.displayName).tag($0) }
                }
                Picker("Effort", selection: $settings.effort) {
                    ForEach(Effort.allCases) { Text($0.rawValue.capitalized).tag($0) }
                }
                Toggle("Read aloud", isOn: $settings.readAloud)
            }

            if app.showsSessions {
                Section("Usage") {
                    if let usage = app.usage {
                        UsageMeter(title: "5 hours", window: usage.fiveHour)
                        UsageMeter(title: "7 days", window: usage.sevenDay)
                    } else {
                        Text("Unavailable").foregroundStyle(.secondary)
                    }
                }
            }

            RoutineSection()

            Section("About") {
                Text(policyNotice).font(.footnote)
                LabeledContent("Version", value: BuildFlags.appVersion)
            }
        }
        .navigationTitle("Settings")
        .task { await app.refreshUsage() }
        .confirmationDialog("Sign out?", isPresented: $confirmSignOut) {
            Button("Sign out", role: .destructive) { Task { await app.signOut() } }
        } message: {
            Text("Removes your credentials from this watch.")
        }
    }

    private var modeName: String {
        switch app.authState {
        case .apiKey: return "API key"
        case .claudeAccount: return "Claude account"
        case .signedOut: return "Signed out"
        }
    }

    private var policyNotice: String {
        switch app.authState {
        case .claudeAccount:
            return accountSignInWarning
        default:
            return "Not affiliated with Anthropic. Chats use your API key and are billed to its Console organization; Max and Team monthly API credits apply. Chats are stored only on this watch."
        }
    }
}

private struct UsageMeter: View {
    let title: String
    let window: Usage.Window?

    var body: some View {
        let percent = min(max(window?.utilization ?? 0, 0), 100)
        Gauge(value: percent, in: 0...100) {
            Text(title)
        } currentValueLabel: {
            Text("\(Int(percent.rounded()))%")
        }
        .gaugeStyle(.linearCapacity)
        .tint(percent >= 90 ? .red : percent >= 70 ? .orange : .green)
    }
}

/// Optional routine token + trigger id for "Start cloud session" (PROTOCOL §6).
private struct RoutineSection: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        Section {
            TextFieldLink(prompt: Text("trig_…")) {
                LabeledContent("Routine ID", value: app.settings.routineTriggerID.isEmpty ? "Not set" : app.settings.routineTriggerID)
            } onSubmit: { value in
                app.settings.routineTriggerID = value.trimmingCharacters(in: .whitespacesAndNewlines)
            }
            TextFieldLink(prompt: Text("Routine token")) {
                LabeledContent("Token", value: app.settings.hasRoutineToken ? "Saved" : "Not set")
            } onSubmit: { value in
                app.settings.setRoutineToken(value)
            }
            if app.settings.hasRoutineToken {
                Button("Remove routine", role: .destructive) {
                    app.settings.setRoutineToken(nil)
                    app.settings.routineTriggerID = ""
                }
            }
        } header: {
            Text("Cloud routine")
        } footer: {
            Text("Create an API-triggered routine on claude.ai, then paste its ID and token here to start cloud sessions from the watch.")
        }
    }
}
