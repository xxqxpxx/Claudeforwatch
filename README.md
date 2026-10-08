# Claude for Watch

Standalone Claude clients for **Apple Watch** (SwiftUI, watchOS 10+) and
**Wear OS** (Compose Material 3, Wear OS 3+). No bridge server, no third-party
service: the watch talks to `api.anthropic.com` directly.

* Dictate or type a question and get a streamed, watch-sized answer.
* Keep quick-chat threads on the watch.
* (Personal builds) sign in with your Claude account to see your Claude Code
  sessions, read what they are doing, steer them, and approve permission
  prompts from your wrist.

## Read this before using it

Anthropic's developer policy does not allow third-party apps to offer
claude.ai login or to use consumer-plan credentials. The supported way to use
Claude from a third-party app is an **API key**. Since October 2026, Max and
Team plans include monthly API credits, so an API key costs Max users nothing
extra for chat. Keys cannot reach Claude Code sessions.

The optional **Claude account** mode (`PERSONAL_MODE` build flag) uses the same
private sign-in and sessions API as the Claude mobile app. It is unsupported,
may stop working at any time, and Anthropic may act against accounts that use
it. It exists for a personal build of your own account only. Details and
sources: `docs/research/anthropic-auth-and-sessions.md`.

Your claude.ai web chats are not reachable from any API, so they are not in
the app.

## Repository

| Path | What |
| --- | --- |
| `docs/PLAN.md` | Architecture, product shape, milestones, risks |
| `docs/PROTOCOL.md` | The exact wire protocol both apps implement |
| `docs/research/` | Verified research with sources |
| `spec/` | API fixtures and expected reductions shared by both platforms' tests |
| `apple/ClaudeWatchKit` | Swift package: auth, SSE, Messages and sessions clients, reducers (tests run on Linux) |
| `apple/ClaudeForWatch` | watchOS app + widget extension (XcodeGen project) |
| `android/core` | Kotlin/JVM library with the same logic (tests run on the JVM) |
| `android/wear` | Wear OS app, tile and complication |

See `apple/README.md` and `android/README.md` for build and sign-in steps.
