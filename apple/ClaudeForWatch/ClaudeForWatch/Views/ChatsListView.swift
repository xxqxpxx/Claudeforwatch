import ClaudeWatchKit
import SwiftUI

enum ChatRoute: Hashable {
    case new
    case thread(String)
}

/// Local quick-chat threads (stored on the watch only; PROTOCOL §4).
struct ChatsListView: View {
    @Environment(AppModel.self) private var app
    @State private var threads: [ChatThread] = []
    @State private var routineMessage: String?

    var body: some View {
        List {
            NavigationLink(value: ChatRoute.new) {
                Label("New chat", systemImage: "plus.bubble.fill")
                    .frame(minHeight: 34)
            }
            // apiKey mode has no Sessions tab, but a routine can still start a cloud task (§6).
            if app.settings.canStartCloudSessions, !app.showsSessions {
                TextFieldLink(prompt: Text("Describe the task")) {
                    Label("Start cloud task", systemImage: "cloud")
                        .frame(minHeight: 34)
                } onSubmit: { task in
                    Task { await startRoutine(task) }
                }
                if let routineMessage {
                    Text(routineMessage).font(.footnote).foregroundStyle(.secondary)
                }
            }
            ForEach(threads) { thread in
                NavigationLink(value: ChatRoute.thread(thread.id)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(thread.displayTitle).font(.headline).lineLimit(1)
                        if let preview = thread.lastMessagePreview {
                            Text(preview).font(.footnote).foregroundStyle(.secondary).lineLimit(2)
                        }
                    }
                }
                .swipeActions {
                    Button(role: .destructive) {
                        Task { await delete(thread) }
                    } label: {
                        Label("Delete", systemImage: "trash")
                    }
                }
            }
        }
        .navigationTitle("Chats")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink { SettingsView() } label: { Image(systemName: "gearshape") }
                    .accessibilityLabel("Settings")
            }
        }
        .navigationDestination(for: ChatRoute.self) { route in
            switch route {
            case .new:
                ChatScreen(app: app, thread: nil)
            case .thread(let id):
                ChatScreen(app: app, thread: threads.first { $0.id == id })
            }
        }
        .onAppear { Task { await reload() } } // also refreshes after leaving a chat
    }

    private func reload() async {
        threads = (try? await app.threads.list()) ?? []
    }

    private func delete(_ thread: ChatThread) async {
        try? await app.threads.delete(id: thread.id)
        await reload()
    }

    private func startRoutine(_ task: String) async {
        do {
            let result = try await app.fireRoutine(task: task)
            routineMessage = "Started \(result.claudeCodeSessionId). Follow it on claude.ai/code."
        } catch {
            routineMessage = app.message(for: error)
        }
    }
}

/// Owns the view model for one chat screen.
struct ChatScreen: View {
    @State private var model: ChatViewModel

    init(app: AppModel, thread: ChatThread?) {
        _model = State(initialValue: ChatViewModel(app: app, thread: thread))
    }

    var body: some View {
        TranscriptView(model: model)
    }
}
