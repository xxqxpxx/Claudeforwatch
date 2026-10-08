import ClaudeWatchKit
import SwiftUI

/// Transcript shared by Claude Code sessions and local chats (docs/PLAN.md §2):
/// bubbles, tool rows, streaming text, bottom-bar composer, quick replies,
/// long-press controls, full-screen permission card.
struct TranscriptView<Model: TranscriptModel>: View {
    @Bindable var model: Model
    @State private var showControls = false
    @Environment(\.dismiss) private var dismiss
    private let bottomID = "transcript-bottom"

    var body: some View {
        ScrollViewReader { proxy in
            List {
                if let summary = model.headerSummary, !summary.isEmpty {
                    Text(summary)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .listRowBackground(Color.clear)
                }
                if model.items.isEmpty, !model.isWorking {
                    EmptyComposer { text in Task { await model.send(text) } }
                        .listRowBackground(Color.clear)
                }
                ForEach(model.visibleItems) { item in
                    TranscriptRow(
                        item: item,
                        isPending: item.requestId != nil && item.requestId == model.pendingPermission?.requestId
                    ) {
                        model.presentedPermission = model.pendingPermission
                    }
                    .listRowBackground(Color.clear)
                    .id(item.id)
                    .onLongPressGesture(minimumDuration: 0.5) { showControls = true }
                }
                if model.isWorking, model.items.last?.isStreaming != true {
                    ProgressView().frame(maxWidth: .infinity).listRowBackground(Color.clear)
                }
                if let error = model.errorMessage {
                    Text(error).font(.footnote).foregroundStyle(.red).listRowBackground(Color.clear)
                }
                if !model.quickReplies.isEmpty, !model.items.isEmpty {
                    QuickRepliesRow(replies: model.quickReplies) { text in
                        Task { await model.send(text) }
                    }
                    .listRowBackground(Color.clear)
                }
                Color.clear.frame(height: 1).id(bottomID).listRowBackground(Color.clear)
            }
            .onChange(of: model.items.count) { _, _ in scrollToBottom(proxy) }
            .onChange(of: model.items.last?.text) { _, _ in scrollToBottom(proxy) }
            .onAppear { scrollToBottom(proxy) }
        }
        .navigationTitle(model.title)
        .toolbar {
            ToolbarItemGroup(placement: .bottomBar) {
                Button {
                    showControls = true
                } label: {
                    Image(systemName: "ellipsis")
                }
                .accessibilityLabel(model.supportsSessionControls ? "Session controls" : "Chat options")
                Spacer()
                // Dictate / Scribble / Type on iPhone: the system text input.
                TextFieldLink(prompt: Text("Message")) {
                    Image(systemName: "mic.fill")
                        .accessibilityLabel("Dictate")
                } onSubmit: { text in
                    Task { await model.send(text) }
                }
            }
        }
        .sheet(isPresented: $showControls) {
            TranscriptControlsView(model: model)
        }
        .fullScreenCover(item: $model.presentedPermission) { permission in
            PermissionCardView(
                permission: permission,
                onDecision: { allow in Task { await model.answerPermission(allow: allow) } },
                onAnswer: { label in Task { await model.answerQuestion(label) } },
                onLater: { model.presentedPermission = nil }
            )
        }
        .onAppear { model.onAppear() }
        .onDisappear { model.onDisappear() }
        .onChange(of: model.isClosed) { _, closed in
            if closed { dismiss() }
        }
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy) {
        withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(bottomID, anchor: .bottom) }
    }
}

/// One transcript row (PROTOCOL §5.3).
struct TranscriptRow: View {
    let item: TranscriptItem
    let isPending: Bool
    let onReview: () -> Void

    var body: some View {
        switch item.kind {
        case .user:
            Bubble(text: item.text ?? "", isUser: true, isStreaming: false)
        case .assistant:
            Bubble(text: item.text ?? "", isUser: false, isStreaming: item.isStreaming)
        case .tool:
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Image(systemName: "gearshape.fill").imageScale(.small)
                Text(toolLine).font(.system(.footnote, design: .monospaced)).lineLimit(2)
            }
            .foregroundStyle(.secondary)
            .accessibilityLabel("Tool \(toolLine)")
        case .system:
            Text(item.text ?? "")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity)
        case .turnEnd:
            Label(item.text ?? "The turn ended with an error.", systemImage: "exclamationmark.triangle.fill")
                .font(.footnote)
                .foregroundStyle(.red)
        case .permission:
            VStack(alignment: .leading, spacing: 4) {
                Label("\(item.tool ?? "Tool") needs approval", systemImage: "hand.raised.fill")
                    .font(.footnote.bold())
                    .foregroundStyle(isPending ? .orange : .secondary)
                if let text = item.text, !text.isEmpty {
                    Text(text).font(.system(.caption2, design: .monospaced)).lineLimit(3)
                }
                if isPending {
                    Button("Review", action: onReview)
                        .frame(maxWidth: .infinity, minHeight: 34)
                        .tint(.orange)
                } else {
                    Text("Answered").font(.caption2).foregroundStyle(.secondary)
                }
            }
        }
    }

    private var toolLine: String {
        let tool = item.tool ?? "Tool"
        guard let text = item.text, !text.isEmpty else { return tool }
        return "\(tool): \(text)"
    }
}

private struct Bubble: View {
    let text: String
    let isUser: Bool
    let isStreaming: Bool

    var body: some View {
        HStack {
            if isUser { Spacer(minLength: 16) }
            Text(isStreaming ? text + " …" : text)
                .font(.body)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(isUser ? Color.accentColor.opacity(0.85) : Color.gray.opacity(0.25))
                )
                .frame(maxWidth: .infinity, alignment: isUser ? .trailing : .leading)
            if !isUser { Spacer(minLength: 0) }
        }
        .accessibilityLabel(isUser ? "You: \(text)" : "Claude: \(text)")
    }
}

/// One-tap replies (PROTOCOL §5.4).
private struct QuickRepliesRow: View {
    let replies: [String]
    let onTap: (String) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(replies, id: \.self) { reply in
                    Button(reply) { onTap(reply) }
                        .font(.footnote)
                        .buttonStyle(.bordered)
                        .frame(minHeight: 34)
                }
            }
        }
    }
}

/// Shown in an empty chat so a new chat "starts with the composer".
private struct EmptyComposer: View {
    let onSubmit: (String) -> Void

    var body: some View {
        TextFieldLink(prompt: Text("Ask anything")) {
            Label("Ask Claude", systemImage: "mic.fill")
                .frame(maxWidth: .infinity, minHeight: 44)
        } onSubmit: { text in
            onSubmit(text)
        }
    }
}

/// Long-press / "…" menu: Interrupt, Model, Permission mode, Archive (PLAN §2).
struct TranscriptControlsView<Model: TranscriptModel>: View {
    let model: Model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if model.supportsSessionControls {
                    Button(role: .destructive) {
                        run { await model.interrupt() }
                    } label: {
                        Label("Interrupt", systemImage: "stop.circle")
                    }
                }
                NavigationLink {
                    List(ClaudeModel.allCases) { option in
                        Button {
                            run { await model.setModel(option) }
                        } label: {
                            HStack {
                                Text(option.displayName)
                                Spacer()
                                if option.rawValue == model.currentModelID { Image(systemName: "checkmark") }
                            }
                        }
                    }
                    .navigationTitle("Model")
                } label: {
                    Label("Model", systemImage: "cpu")
                }
                if model.supportsSessionControls {
                    NavigationLink {
                        List(SessionsClient.PermissionMode.allCases) { mode in
                            Button(mode.displayName) { run { await model.setPermissionMode(mode) } }
                        }
                        .navigationTitle("Permissions")
                    } label: {
                        Label("Permission mode", systemImage: "lock.shield")
                    }
                    Button(role: .destructive) {
                        run { await model.archive() }
                    } label: {
                        Label("Archive", systemImage: "archivebox")
                    }
                }
            }
            .navigationTitle(model.supportsSessionControls ? "Session" : "Chat")
        }
    }

    private func run(_ action: @escaping @MainActor @Sendable () async -> Void) {
        dismiss()
        Task { await action() }
    }
}
