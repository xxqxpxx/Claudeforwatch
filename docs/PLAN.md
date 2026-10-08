# Claude for Watch — plan

Standalone Apple Watch and Wear OS clients for Claude. No bridge server, no
third-party service, nothing between the watch and `api.anthropic.com`.
Sign in once, see your Claude Code sessions and your own quick chats, and
talk to Claude by dictation or typing.

Research behind every decision: `docs/research/` and `docs/PROTOCOL.md`.

## 1. What is and isn't possible (the honest version)

| Goal from the brief | Status | How |
| --- | --- | --- |
| Log in with your Claude account | **Possible, unsupported by Anthropic** | Claude Code's own OAuth (PKCE) run from the watch; QR on the watch, sign in on the phone, enter the code with the phone keyboard. Anthropic's developer policy forbids third-party apps from offering claude.ai login; this is for a personal build of your own account, and the app says so before enabling it. |
| See your Claude Code sessions (cloud and Remote Control) and talk to them | **Possible, unsupported** | The same private sessions API the Claude mobile app uses (`/v1/code/sessions`). List, read, stream, send, interrupt, approve permission prompts. Remote Control sessions need `claude /rc` running on your machine (that is Anthropic's feature, not a bridge of ours). Cloud sessions need nothing. |
| See your claude.ai chat conversations | **Not possible** | No API exists for consumer chats (only an Enterprise compliance API). The app has its own quick-chat threads instead, stored on the watch. |
| Chat with Claude by voice or text | **Possible, supported** | Messages API with streaming, `claude-haiku-5-5` by default. Voice is on-device system dictation (the Messages API takes no audio, and watchOS has no Speech framework). |
| Supported sign-in | **Possible** | Anthropic Console API key. Since 7 Oct 2026 Max and Team plans include monthly API credits ($100/$200), so a key costs Max users nothing extra for chat. Keys cannot reach Claude Code sessions. |
| Push notifications when a session needs you | **Not without a server** | Deferred. The app shows "needs action" badges, polls while open, and exposes a complication/tile with the count. |

Decision: ship two auth modes behind one `AuthProvider` abstraction.
`apiKey` is the default and the only one a public App Store / Play build
should enable. `claudeAccount` is a build-time flag (`PERSONAL_MODE`) that
unlocks sessions and usage, with the warning text from PROTOCOL §1.2.

## 2. Product shape

Three tabs (vertical pages on watchOS, swipe-dismissable stack on Wear OS):

1. **Sessions** (claudeAccount only) — list sorted needs-action → running →
   recent. Row: title, status dot, one-line `post_turn_summary`, model.
   Tap → transcript. Red badge when a permission prompt waits.
2. **Chats** — local quick-chat threads. "New chat" starts with the composer.
3. **Ask** — one big mic button. Dictate, send, read the streamed reply, with
   optional read-aloud. This is also what Siri ("Ask Claude") and the
   tile/complication open.

Transcript screen (sessions and chats share it): bubbles, tool rows ("⚙ Bash:
npm test"), streaming assistant text, a bottom composer with **Dictate**,
**Type** and **Quick replies** ("Continue", "Looks good, proceed", "Run the
tests", "Stop", "Commit and push"). Long press → Interrupt / Set model /
Permission mode / Archive.

Permission card: full-screen, haptic, tool name + one-line summary, Allow /
Deny. AskUserQuestion options render as buttons.

Settings: auth mode and sign-in, default model, read-aloud, effort, usage
meter (claudeAccount), routine token for "Start cloud session" (optional,
PROTOCOL §6), about + policy notice.

## 3. Sign-in flows

**API key** (both platforms): Settings → "Use an API key" → text field. The
phone keyboard sheet lets the user paste the key from the phone. The app
validates with a 1-token Messages call and stores it (Keychain / encrypted
DataStore).

**Claude account** (PERSONAL_MODE): Settings → "Sign in with Claude" → warning
→ watch shows a QR code of the authorize URL (+ "Open on phone" on Wear OS,
"Open here" on watchOS as a fallback) → user signs in on the phone, copies the
code → taps "Enter code" on the watch → phone keyboard sheet → paste → the
watch verifies `state`, exchanges the code, stores tokens, fetches profile.
Tokens refresh silently (single-flight). Sign-out clears everything.

## 4. Architecture

```
spec/                       fixtures + expected reductions shared by both platforms
apple/
  ClaudeWatchKit/           SwiftPM, Foundation-only, tested on Linux
    Sources/ClaudeWatchKit/ Auth (PKCE, OAuthClient, TokenStore protocol, AuthProvider)
                            API (HTTPClient protocol, MessagesClient, SessionsClient, SSEParser)
                            Models (Codable DTOs), Reduce (SessionEvent → TranscriptItem)
                            Store (ChatThread models, ThreadStore protocol)
    Tests/                  Swift Testing against spec/fixtures
  ClaudeForWatch/           XcodeGen project.yml → watch-only app (watchOS 10+), SwiftUI
                            KeychainTokenStore, URLSessionHTTPClient (bytes(for:) SSE),
                            Views, ViewModels (@Observable), AskClaudeIntent + AppShortcuts,
                            QR renderer (CoreImage), widget extension (accessoryRectangular/Corner)
android/
  core/                     Kotlin/JVM library, no Android deps, tested on Linux
                            (same packages as ClaudeWatchKit; kotlinx.serialization, coroutines)
  wear/                     Wear OS app (minSdk 30, target 35, compileSdk 36), Compose M3 1.7.x
                            OkHttp SSE adapter, Keystore-encrypted DataStore, RemoteInput +
                            ACTION_RECOGNIZE_SPEECH input, QR (zxing-core), TileService,
                            complication data source, OngoingActivity while streaming
```

Rules both platforms follow:
* All protocol logic in the platform-free module; the app adds only
  transport, storage and UI adapters. Fixture tests must pass on Linux.
* Streams run only while the screen is active; on background, cancel and
  persist; on return, resume from the last `sequence_num` (sessions) or
  re-render the stored thread (chats). No extended runtime sessions, no
  WebSockets, no foreground services for streaming.
* Never auto-retry a completion request. Refresh tokens once on 401.
* Secrets only in Keychain / Keystore-encrypted DataStore; never logged.
* Watch-sized output: system prompt forces short plain prose; transcripts
  show the free `post_turn_summary` first.

## 5. Milestones

| # | Milestone | Done when |
| --- | --- | --- |
| M0 | Spec + fixtures (this plan, PROTOCOL.md, `spec/`) | committed |
| M1 | Shared cores: PKCE, OAuth, SSE parser, Messages stream, sessions DTOs, event reducer | `swift test` and `gradle :core:test` green on Linux against fixtures |
| M2 | Chat MVP on both watches: API-key sign-in, Ask tab, threads, streaming, dictation/typing, read-aloud | runs on simulator/emulator; manual test with a real key |
| M3 | Claude-account mode: QR sign-in, sessions list, transcript stream, send, quick replies, permission cards, interrupt | manual test against a live cloud session and a `/rc` session |
| M4 | Wrist extras: Siri "Ask Claude", complication + tile with needs-action count and usage, Double Tap / Action button to dictate, Ongoing Activity | installed on hardware |
| M5 | Polish: offline cache (SwiftData / Room), archive, model picker, settings, error copy, App Store / Play assets | TestFlight / internal testing |

This session delivers M0, M1 and the full M2–M3 code for both platforms
(compiled where the toolchain allows: Android app and both cores on Linux;
the watchOS app needs Xcode on a Mac to build).

## 6. Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Anthropic changes or blocks the private OAuth / sessions API | Claude-account mode stops working | Isolated behind `AuthProvider` + `SessionsClient`; apiKey chat unaffected; clear in-app error copy |
| Account enforcement for third-party OAuth use | user's account suspended | PERSONAL_MODE off by default; warning before enabling; no distribution of that build |
| Trusted Devices enabled on the org | sessions API returns 403 | detect and explain; cannot enroll a watch |
| claude.ai login page unusable in the on-watch browser | can't sign in on the watch itself | QR → phone is the primary path; on-watch is a fallback only |
| watchOS suspends the app seconds after wrist-down | streams drop mid-reply | resume from `sequence_num`; chat replies are short; partial text persisted |
| Entering long secrets on a watch | poor UX | phone keyboard sheet on both platforms; QR for URLs |
| App Store / Play review (standalone app, policy notice) | rejection | ship apiKey-only build publicly; keep dark icon off black (watch guideline) |
| Future: ClaudeForFoundationModels App Attest (watchOS 27 beta) | opportunity | add `.appAttest` chat provider when watchOS 27 ships; removes key entry for chat entirely |

## 7. Out of scope for v1
Images/files, MCP connectors, creating cloud sessions from the controller
API (undocumented), push notifications, iPhone companion app, Live Activities,
offline STT.
