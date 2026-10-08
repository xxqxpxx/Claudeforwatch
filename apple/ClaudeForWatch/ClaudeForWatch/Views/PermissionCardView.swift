import ClaudeWatchKit
import SwiftUI

/// Full-screen card for a `can_use_tool` prompt (PROTOCOL §5.5). The haptic
/// plays when the prompt arrives (view model), not when the card is reopened.
struct PermissionCardView: View {
    let permission: PendingPermission
    let onDecision: (Bool) -> Void
    let onAnswer: (String) -> Void
    let onLater: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                if permission.isQuestion {
                    question
                } else {
                    toolRequest
                }
                Button("Later", action: onLater)
                    .frame(maxWidth: .infinity, minHeight: 34)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var toolRequest: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Allow \(permission.tool)?", systemImage: "hand.raised.fill")
                .font(.headline)
                .foregroundStyle(.orange)
            if !permission.summary.isEmpty {
                Text(permission.summary)
                    .font(.system(.footnote, design: .monospaced))
                    .lineLimit(6)
            }
            if let description = permission.description, !description.isEmpty {
                Text(description).font(.footnote).foregroundStyle(.secondary)
            }
            Button {
                onDecision(true)
            } label: {
                Label("Allow", systemImage: "checkmark").frame(maxWidth: .infinity, minHeight: 34)
            }
            .tint(.green)
            Button(role: .destructive) {
                onDecision(false)
            } label: {
                Label("Deny", systemImage: "xmark").frame(maxWidth: .infinity, minHeight: 34)
            }
        }
    }

    /// AskUserQuestion: options become buttons; the label is sent as a user message.
    private var question: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Claude asks", systemImage: "questionmark.bubble.fill")
                .font(.headline)
            ForEach(Array(permission.questions.enumerated()), id: \.offset) { _, q in
                Text(q.question).font(.footnote)
                ForEach(q.options, id: \.self) { option in
                    Button {
                        onAnswer(option)
                    } label: {
                        Text(option).frame(maxWidth: .infinity, minHeight: 34)
                    }
                }
            }
            if permission.questions.isEmpty {
                Text(permission.summary).font(.footnote)
            }
        }
    }
}
