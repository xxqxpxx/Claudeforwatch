import ClaudeWatchKit
import SwiftUI

@main
struct ClaudeForWatchApp: App {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .task { await model.start() }
                .onOpenURL { model.handle(url: $0) }
        }
        // Streams run only while active: cancel on background, resume on active.
        .onChange(of: scenePhase) { _, phase in
            model.scenePhaseChanged(phase)
        }
    }
}

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        Group {
            if !model.isLoaded {
                ProgressView()
            } else if model.authState == .signedOut {
                NavigationStack { SignInView() }
            } else {
                TabView(selection: $model.selectedTab) {
                    if model.showsSessions {
                        NavigationStack { SessionsListView() }
                            .tag(AppModel.Tab.sessions)
                    }
                    NavigationStack { ChatsListView() }
                        .tag(AppModel.Tab.chats)
                    NavigationStack { AskView() }
                        .tag(AppModel.Tab.ask)
                }
                .tabViewStyle(.verticalPage)
            }
        }
    }
}
