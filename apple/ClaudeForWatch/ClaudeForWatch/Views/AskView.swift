import ClaudeWatchKit
import SwiftUI

/// One big mic button: dictate → send → streamed reply, optional read-aloud.
/// Also where Siri ("Ask Claude") and the widget land (PLAN §2).
struct AskView: View {
    @Environment(AppModel.self) private var app
    @State private var chat: ChatViewModel?

    var body: some View {
        @Bindable var settings = app.settings
        ScrollView {
            VStack(spacing: 10) {
                if let chat { AskReply(chat: chat) }

                TextFieldLink(prompt: Text("Ask Claude")) {
                    Image(systemName: "mic.fill")
                        .font(.system(size: 30, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 76, height: 76)
                        .background(Circle().fill(Color.accentColor))
                } onSubmit: { text in
                    ask(text)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Ask Claude")
                .disabled(chat?.isWorking == true)

                Toggle(isOn: $settings.readAloud) {
                    Label("Read aloud", systemImage: "speaker.wave.2")
                }
                .frame(minHeight: 34)
            }
        }
        .navigationTitle("Ask")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink { SettingsView() } label: { Image(systemName: "gearshape") }
                    .accessibilityLabel("Settings")
            }
        }
        .onAppear(perform: consumePendingAsk)
        .onChange(of: app.pendingAsk) { _, _ in consumePendingAsk() }
        .onDisappear { chat?.onDisappear() }
    }

    private func ask(_ text: String) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        chat?.onDisappear()
        let model = ChatViewModel(app: app) // each ask is saved as a new chat thread
        model.onAppear()
        chat = model
        Task { await model.send(text) }
    }

    private func consumePendingAsk() {
        guard let question = app.pendingAsk else { return }
        app.pendingAsk = nil
        ask(question)
    }
}

private struct AskReply: View {
    let chat: ChatViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let question = chat.thread.messages.first(where: { $0.role == .user })?.text {
                Text(question).font(.footnote).foregroundStyle(.secondary)
            }
            if let reply = chat.streamingText ?? chat.lastReply, !reply.isEmpty {
                Text(reply).font(.body)
            } else if chat.isWorking {
                ProgressView()
            }
            if let error = chat.errorMessage {
                Text(error).font(.footnote).foregroundStyle(.red)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
