import ClaudeWatchKit
import SwiftUI

/// Sessions tab (PERSONAL_MODE + Claude account). PROTOCOL §5.1.
struct SessionsListView: View {
    @Environment(AppModel.self) private var app
    @State private var model: SessionsListViewModel?
    @State private var openedSessionID: String?

    var body: some View {
        Group {
            if let model {
                SessionsList(model: model, openedSessionID: $openedSessionID)
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Sessions")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink { SettingsView() } label: { Image(systemName: "gearshape") }
                    .accessibilityLabel("Settings")
            }
        }
        .navigationDestination(for: String.self) { id in
            SessionTranscriptScreen(app: app, sessionID: id, session: model?.sessions.first { $0.id == id })
        }
        .navigationDestination(item: $openedSessionID) { id in
            SessionTranscriptScreen(app: app, sessionID: id, session: nil)
        }
        .onAppear {
            if model == nil { model = SessionsListViewModel(app: app) }
        }
    }
}

private struct SessionsList: View {
    @Environment(AppModel.self) private var app
    let model: SessionsListViewModel
    @Binding var openedSessionID: String?

    var body: some View {
        List {
            if app.settings.canStartCloudSessions {
                TextFieldLink(prompt: Text("Describe the task")) {
                    Label("New cloud session", systemImage: "plus.circle")
                        .frame(minHeight: 34)
                } onSubmit: { task in
                    Task {
                        if let id = await model.startCloudSession(task: task) { openedSessionID = id }
                    }
                }
                .disabled(model.isStarting)
            }
            if let error = model.errorMessage {
                Text(error).font(.footnote).foregroundStyle(.red)
            }
            ForEach(model.sessions) { session in
                NavigationLink(value: session.id) {
                    SessionRow(session: session)
                }
                .swipeActions {
                    Button(role: .destructive) {
                        Task { await model.archive(session) }
                    } label: {
                        Label("Archive", systemImage: "archivebox")
                    }
                }
            }
        }
        .overlay {
            if model.hasLoaded, model.sessions.isEmpty, model.errorMessage == nil {
                ContentUnavailableView(
                    "No sessions",
                    systemImage: "terminal",
                    description: Text("Start one on claude.ai/code or run claude /rc on your computer.")
                )
            }
        }
        .onAppear { model.onAppear() }
        .onDisappear { model.onDisappear() }
    }
}

struct SessionRow: View {
    let session: Session

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                Circle().fill(statusColor).frame(width: 8, height: 8)
                Text(session.displayTitle).font(.headline).lineLimit(1)
                Spacer(minLength: 0)
                if session.needsAction {
                    Image(systemName: "exclamationmark.circle.fill")
                        .foregroundStyle(.red)
                        .accessibilityLabel("Needs your approval")
                }
            }
            if let summary = session.externalMetadata?.postTurnSummary, !summary.isEmpty {
                Text(summary).font(.footnote).foregroundStyle(.secondary).lineLimit(2)
            }
            HStack(spacing: 4) {
                Image(systemName: session.kind == .remoteControl ? "laptopcomputer" : "cloud")
                Text(ClaudeModel.displayName(for: session.model))
            }
            .font(.caption2)
            .foregroundStyle(.secondary)
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }

    private var statusColor: Color {
        switch session.workerStatus {
        case .requiresAction?: return .red
        case .running?: return .green
        default: return .gray
        }
    }
}

/// Owns the view model for one session screen.
struct SessionTranscriptScreen: View {
    @State private var model: SessionTranscriptViewModel

    init(app: AppModel, sessionID: String, session: Session?) {
        _model = State(initialValue: SessionTranscriptViewModel(app: app, sessionID: sessionID, session: session))
    }

    var body: some View {
        TranscriptView(model: model)
    }
}
