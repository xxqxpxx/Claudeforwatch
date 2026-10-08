import ClaudeWatchKit
import SwiftUI

/// First screen when signed out. API key is the default, supported path.
struct SignInView: View {
    @Environment(AppModel.self) private var app
    @State private var isWorking = false
    @State private var error: String?

    var body: some View {
        ScrollView {
            VStack(spacing: 10) {
                Text("Use an Anthropic Console API key. Tap below, choose Type on iPhone, and paste the key.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                TextFieldLink(prompt: Text("sk-ant-api03-…")) {
                    Label("Enter API key", systemImage: "key.fill")
                        .frame(maxWidth: .infinity, minHeight: 34)
                } onSubmit: { key in
                    Task { await submit(key) }
                }
                .disabled(isWorking)

                if isWorking { ProgressView() }
                if let error {
                    Text(error).font(.footnote).foregroundStyle(.red).multilineTextAlignment(.center)
                }

                if BuildFlags.personalMode {
                    Divider()
                    NavigationLink {
                        ClaudeAccountSignInView()
                    } label: {
                        Label("Sign in with Claude", systemImage: "person.crop.circle")
                            .frame(maxWidth: .infinity, minHeight: 34)
                    }
                }
            }
        }
        .navigationTitle("Sign in")
    }

    private func submit(_ key: String) async {
        guard !key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        isWorking = true
        defer { isWorking = false }
        do {
            try await app.signIn(apiKey: key)
            error = nil
        } catch {
            self.error = app.message(for: error)
        }
    }
}

/// PERSONAL_MODE only: unofficial Claude-account sign-in (PROTOCOL §1.2, §2).
/// QR of the authorize URL → sign in on the phone → paste `<code>#<state>`
/// back through the watch's text input ("Type on iPhone").
struct ClaudeAccountSignInView: View {
    @Environment(AppModel.self) private var app
    @State private var pkce: PKCE?
    @State private var qrCode: QRCode?
    @State private var isWorking = false
    @State private var error: String?

    var body: some View {
        ScrollView {
            VStack(spacing: 10) {
                if app.settings.accountWarningAccepted {
                    signInSteps
                } else {
                    warning
                }
            }
        }
        .navigationTitle("Claude account")
        .onAppear(perform: ensureFreshPKCE)
    }

    private var warning: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Unsupported", systemImage: "exclamationmark.triangle.fill")
                .font(.headline)
                .foregroundStyle(.orange)
            Text(accountSignInWarning).font(.footnote)
            Button {
                app.settings.accountWarningAccepted = true
                ensureFreshPKCE()
            } label: {
                Text("I understand").frame(maxWidth: .infinity, minHeight: 34)
            }
        }
    }

    @ViewBuilder
    private var signInSteps: some View {
        if let pkce {
            let url = app.auth.oauth.authorizeURL(pkce: pkce)
            Text("1. Scan with your phone and sign in.")
                .font(.footnote)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let qrCode {
                QRCodeView(code: qrCode).frame(maxWidth: .infinity)
            }
            Text("2. Copy the code, tap Enter code, choose Type on iPhone and paste.")
                .font(.footnote)
                .frame(maxWidth: .infinity, alignment: .leading)
            TextFieldLink(prompt: Text("Paste code")) {
                Label("Enter code", systemImage: "keyboard")
                    .frame(maxWidth: .infinity, minHeight: 34)
            } onSubmit: { pasted in
                Task { await submit(pasted) }
            }
            .disabled(isWorking)
            if isWorking { ProgressView() }
            if let error {
                Text(error).font(.footnote).foregroundStyle(.red)
            }
            // Fallback: the login page in the watch's web view. Often unusable
            // (heavy JS); the QR path is primary.
            Link(destination: url) {
                Label("Open here", systemImage: "safari").frame(maxWidth: .infinity, minHeight: 34)
            }
            Button("New code") { regenerate() }
                .font(.footnote)
                .frame(minHeight: 34)
        }
    }

    private func ensureFreshPKCE() {
        if pkce?.isExpired() ?? true { regenerate() }
    }

    /// New verifier/state (valid 10 minutes) and its QR code, encoded once.
    private func regenerate() {
        let fresh = PKCE.generate()
        pkce = fresh
        qrCode = try? QRCode.encode(app.auth.oauth.authorizeURL(pkce: fresh).absoluteString, errorCorrection: .low)
    }

    private func submit(_ pasted: String) async {
        guard let current = pkce else { return }
        if current.isExpired() {
            regenerate()
            error = AppModel.message(for: .expired)
            return
        }
        isWorking = true
        defer { isWorking = false }
        do {
            try await app.completeClaudeSignIn(pasted: pasted, pkce: current)
            error = nil
        } catch {
            self.error = app.message(for: error)
            Haptics.failure()
        }
    }
}
