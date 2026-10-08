# Claude for Watch: Apple

A standalone, watch-only Apple Watch app (watchOS 10+) for chatting with
Claude and, in personal builds, steering your Claude Code sessions. It talks
directly to `api.anthropic.com`. There is no bridge server and no companion
iPhone app. The protocol is specified in [`../docs/PROTOCOL.md`](../docs/PROTOCOL.md)
and the product plan in [`../docs/PLAN.md`](../docs/PLAN.md).

```
apple/
  ClaudeWatchKit/      SwiftPM package, Foundation-only, tested on Linux and macOS
  ClaudeForWatch/      XcodeGen project: watchOS app + widget extension
```

## ClaudeWatchKit (shared logic)

The protocol logic lives here, with no UI and no Apple-only frameworks:

| Folder | What |
| --- | --- |
| `Auth/` | PKCE, `OAuthClient` (authorize URL, `<code>#<state>` parsing, JSON → form fallback, refresh), `AuthProvider` actor (per-endpoint headers, single-flight refresh), `TokenStore`, `Credentials` |
| `Net/` | `HTTPClient` protocol, `StubHTTPClient`, incremental `SSEParser` |
| `API/` | `MessagesClient` (streaming chat), `SessionsClient` (Claude Code sessions), `UsageClient`, `RoutinesClient`, `APIError` |
| `Models/` | Session and event DTOs, plus an order-preserving `JSONValue` |
| `Reduce/` | `TranscriptState` (events → transcript rows), `SessionListReducer` |
| `Store/` | `ChatThread`, `ThreadStore` (in-memory and JSON file), widget/Siri shared state |
| `QR/` | A pure-Swift QR encoder. Core Image is not available on watchOS. |

Run the tests (Swift 6 toolchain; on Linux, `swift-crypto` supplies SHA-256):

```sh
cd apple/ClaudeWatchKit
swift test
```

The tests load `spec/fixtures/*` and check the results against `spec/expected/*`, the same files the
Android core is tested against.

## Generate and build the watch app (needs a Mac with Xcode 16+)

```sh
brew install xcodegen
cd apple/ClaudeForWatch
xcodegen generate
open ClaudeForWatch.xcodeproj
```

1. Select the **ClaudeForWatch** target, then **Signing & Capabilities**. Choose your team
   (or set `DEVELOPMENT_TEAM` in `project.yml`). Do the same for
   **ClaudeForWatchWidgets**. Both targets use the App Group
   `group.com.claudeforwatch`; if it is already taken under your team, change
   it in `project.yml` and in `Shared/SharedContainer.swift`.
2. Pick the **ClaudeForWatch** scheme and an Apple Watch simulator
   (watchOS 10 or later), then Run. From the command line:

   ```sh
   xcodebuild -project ClaudeForWatch.xcodeproj -scheme ClaudeForWatch \
     -destination 'platform=watchOS Simulator,name=Apple Watch Series 10 (46mm)' build
   ```
3. To run on a real watch, pair it with your iPhone, enable Developer Mode on
   the watch, select it as the run destination and Run.

### Personal build (`PERSONAL_MODE`)

The **ClaudeForWatch-Personal** scheme builds the `Personal` configuration,
which adds the `PERSONAL_MODE` Swift compilation condition. Only that build
shows:

* "Sign in with Claude" (Claude Code's private OAuth),
* the **Sessions** tab (cloud and Remote Control sessions),
* the usage meter in Settings and on the widget.

Use it only on your own watch with your own account, and never distribute it.
Before enabling, the app shows the warning from PROTOCOL §1.2. Anthropic does not
support third-party use of this sign-in and may block or suspend accounts.
Default (Debug/Release) builds offer API-key sign-in only, and they sign out
any Claude-account credential they find.

## Using it

### Sign in with an API key (all builds)

1. On the watch, tap **Enter API key**.
2. On the paired iPhone, open the **Type on iPhone** notification (or the
   keyboard sheet), paste your `sk-ant-api03-…` key, and send.
3. The watch checks the key with a 1-token request, then stores it in the
   Keychain (`com.claudeforwatch.credentials`, this device only).

Since 7 Oct 2026, Max and Team plans include monthly API credits, so chatting
with a key from a linked Console organization costs those users nothing extra.

### Sign in with your Claude account (Personal build)

1. **Sign in with Claude**, read the warning, then **I understand**.
2. The watch shows a QR code of the authorize URL. Scan it with your phone,
   sign in on claude.ai and approve. The page then shows a code that looks like
   `abc…#xyz…`. Copy all of it.
3. On the watch, tap **Enter code**. On the iPhone, use **Type on iPhone**,
   paste the code and send.
4. The watch checks the `#state` suffix, exchanges the code, stores the tokens
   in the Keychain and fetches your profile. Tokens refresh silently. A code
   expires after 10 minutes; **New code** starts over.
5. **Open here** opens the login page in the watch's web view instead. It is a
   last resort: the page is often unusable at watch size.

### Everyday use

* **Ask** tab: tap the mic, dictate, read the streamed reply. Turn on **Read aloud**
  if you want it spoken. Each question is saved as a chat.
* **Chats** tab: local threads stored on the watch only (the 100 most recent).
* **Sessions** tab (Personal): sessions waiting for you come first (red badge),
  then running ones, then the most recent. Tap one for its transcript and use the
  bottom bar to dictate a reply. Quick replies sit at the end of the
  transcript. Long-press a row or tap **…** for Interrupt, Model,
  Permission mode and Archive. Permission prompts open full-screen with
  **Allow** / **Deny** and a haptic.
* **Siri**: "Ask Claude". Siri asks for the question, then opens the app,
  which answers on the Ask tab.
* **Widget**: add "Claude" to the Smart Stack (rectangular) or a corner
  complication. It shows how many sessions need you and your usage, and
  refreshes every 15 minutes or whenever the app writes new numbers.
* **Cloud routine** (optional, PROTOCOL §6): paste an API-triggered routine's
  `trig_…` ID and token in Settings. **New cloud session** (Sessions) or
  **Start cloud task** (Chats, API-key mode) then starts it with dictated text.

Streams run only while the app is on screen. When the app goes to the
background it cancels them; when you return, sessions resume from the last
sequence number. A chat reply that was cut off keeps its partial text and is
never re-sent automatically, because the request may already have been billed.

## Design notes and deviations

* **QR rendering:** the task asked for Core Image's `CIQRCodeGenerator`, but Core
  Image is not available on watchOS. `ClaudeWatchKit/QR/QRCode.swift` is a
  dependency-free encoder (byte mode, versions 1–40, all ECC levels,
  automatic mask). During development its output for all 40 versions × 4 ECC
  levels × 8 masks matched the `segno` reference library module for module,
  and the tests keep two of those matrices as golden values.
  `QRCodeView` draws it with `Canvas`, snapped to whole pixels. The
  authorize URL (about 390 characters) encodes as version 13 (69 × 69).
* **`anthropic-beta: oauth-2025-04-20` on `/api/oauth/usage` and `/profile`:**
  PROTOCOL.md lists no beta header for these. Claude Code sends this one, so the
  app does too.
* **"First string value" (§5.3)** uses document order. Foundation's JSON
  decoders lose key order, so session events are parsed with a small ordered
  JSON parser (`JSONValue.parse`). The same parser lets `updatedInput` be echoed
  back exactly as received.
* **AskUserQuestion:** the summary is the first question's text, because the
  input has no top-level string to summarise.
* **History window:** the last 20 turns are sent. Empty turns are dropped,
  consecutive same-role turns are merged, and the window never starts with an
  assistant turn or ends with one (no prefill). That means up to 19 alternating
  turns end up in the request.
* **SSE end of stream:** `SSEParser.flush()` delivers a final event that has no
  trailing blank line. WHATWG says to drop it.

## Not verified on hardware yet

Nothing in `ClaudeForWatch/` has been compiled with Xcode. It was written and
reviewed on Linux. Its model and view-model layer was type-checked under Swift 6
using stub modules for SwiftUI, WidgetKit, WatchKit and AVFoundation; the SwiftUI
views were only syntax-checked. To verify on a Mac and a watch:

- [ ] `xcodegen generate` produces a single-target watch-only app
      (`type: application`, `platform: watchOS`) with the widget extension embedded.
- [ ] Both targets build in Debug, Release and Personal with no Swift 6
      concurrency errors.
- [ ] `TextFieldLink` inside the `.bottomBar` toolbar and as the large Ask button
      (with `.buttonStyle(.plain)`) looks and works correctly.
- [ ] "Type on iPhone" appears for the API-key and code fields, and pasting
      works.
- [ ] A phone camera can scan the version-13 QR code on 41 mm and 45/46 mm watches.
- [ ] `Link` ("Open here") opens the authorize page in the watch's web view.
- [ ] `URLSession.bytes(for:)` streaming: Messages SSE, sessions SSE resume
      with `from_sequence_num` + `Last-Event-ID`, and behaviour after
      wrist-down and background (streams cancelled and resumed).
- [ ] Keychain persistence across launches and the
      `AfterFirstUnlockThisDeviceOnly` accessibility class.
- [ ] Claude-account flow against the live endpoints: token exchange (JSON or
      the form fallback), refresh rotation, `/v1/code/sessions` list and
      events, permission allow/deny, interrupt, set_model,
      set_permission_mode, archive, mark_read, presence.
- [ ] Answering an AskUserQuestion prompt with a plain user message (PROTOCOL
      §5.5) actually unblocks the worker.
- [ ] Haptics (`.notification` on a permission prompt).
- [ ] `AVSpeechSynthesizer` read-aloud plays through the watch speaker with no
      audio session setup.
- [ ] Siri "Ask Claude": the `INAlternativeAppNames` alias, `requestValueDialog`,
      opening the app, and the pending question being answered on the Ask tab.
- [ ] Widget: `accessoryRectangular` and `accessoryCorner` layouts,
      App Group snapshot reads, `widgetURL` deep links, and the 15-minute refresh.
- [ ] Touch targets of at least 34 pt everywhere, VoiceOver labels, and the
      vertical-page TabView with three tabs (two when signed in with an API key).
- [ ] App icon: the asset catalog has an empty 1024 pt slot that needs an image.
