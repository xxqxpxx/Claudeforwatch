# Platform notes (condensed from the research agents, 2026-10-08)

## watchOS (target watchOS 10+, Xcode 26)
* Watch-only App Store apps are supported (`WKWatchOnly`). Single-target SwiftUI app + widget extension.
* **No Speech framework on watchOS** (watchOS 26 release notes). Voice = system dictation via `TextFieldLink` (watchOS 9+) / `TextField`; returns final text only; dictation caps at ~2 min. QuickType keyboard only on Series 7+. When a text field is focused the paired iPhone offers "Type on iPhone", which allows pasting from the phone clipboard.
* **No third-party pasteboard on watchOS.** Long secrets must come through the iPhone keyboard sheet or a QR-driven flow.
* `ASWebAuthenticationSession` exists (6.2+, SwiftUI `webAuthenticationSession` 9.4+) and renders on the watch, but JS-heavy login pages often blank out; `presentationContextProvider` is unavailable on watchOS. Use only as a fallback to display the authorize page.
* **Low-level networking is blocked** (TN3135): no `NWListener`/`NWConnection`, no WebSockets, no `URLSessionStreamTask`. `URLSession.bytes(for:)` (watchOS 8+) works for SSE in the foreground. Default sessions stop when the app is inactive; the frontmost app gets ~2 min after wrist-down, then suspends. `WKExtendedRuntimeSession` types do not fit a chat app (App Review risk). `waitsForConnectivity = true`, generous `timeoutIntervalForRequest`.
* Keychain (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`), not shared with the iPhone. SwiftData is watchOS 10+. App Group only for the widget extension.
* App Intents on watchOS 9+ run in-process; Siri gives ~10–30 s. Pattern: "Ask Claude" shortcut with a `String` parameter (`requestValueDialog`), return a short dialog and `openAppWhenRun` for the streamed answer. Phrases must be spoken exactly on the watch.
* WidgetKit families: `accessoryRectangular` (Smart Stack), `accessoryCircular`, `accessoryInline`, `accessoryCorner` (watchOS 9+). RelevanceKit is watchOS 26+. No standalone Live Activities. Push needs an APNs server (deferred).
* Double Tap: `.handGestureShortcut(.primaryAction)` (watchOS 11+). Haptics: `WKInterfaceDevice.current().play(.notification)`.
* Navigation: `NavigationStack`, `TabView` + `.tabViewStyle(.verticalPage)` (10+), `.toolbar { ToolbarItem(placement: .bottomBar) }` (10+). Touch targets ≥ 34 pt; never `ignoresSafeArea` on containers holding buttons.
* Memory budget is tens of MB: stream and discard.
* Linux cannot compile SwiftUI/WatchKit; keep protocol logic in a Foundation-only SwiftPM package (`#if canImport(FoundationNetworking)`, `swift-crypto` for SHA-256 on Linux) and test it with `swift test`.
* ClaudeForFoundationModels (Anthropic, Apache-2.0): watchOS 27 beta only; `.appAttest(clientID:)` issues 1-hour workspace tokens with no key on device, Messages API only. Future chat provider.

## Wear OS (minSdk 30, targetSdk 35, compileSdk 36)
* Standalone manifest: `<uses-feature android:name="android.hardware.type.watch"/>`, `<meta-data android:name="com.google.android.wearable.standalone" android:value="true"/>`, `<uses-library android:name="com.google.android.wearable" android:required="true"/>`.
* **No browser and no WebView on Wear OS.** `RemoteAuthClient` and `RemoteActivityHelper` (androidx.wear:wear-phone-interactions 1.1.1 / wear-remote-interactions 1.2.0) both need a paired phone; check `availabilityStatus`. Play guideline WO-P6: no typed passwords on the watch (a pasted code/key is fine).
* Input: mic → `Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)` with `EXTRA_LANGUAGE_MODEL=LANGUAGE_MODEL_FREE_FORM` via `rememberLauncherForActivityResult`; keyboard → `RemoteInputIntentHelper` (androidx.wear:wear-input 1.2.0) system sheet (dictation/keyboard/emoji); read `RemoteInput.getResultsFromIntent`. Direct `SpeechRecognizer` is unreliable on Galaxy watches. Add `<queries>` for `android.speech.action.RECOGNIZE_SPEECH`.
* UI: Wear Compose Material 3 **1.7.1** (`androidx.wear.compose:compose-material3`, `compose-foundation`, `compose-navigation`): `AppScaffold`, `ScreenScaffold`, `TransformingLazyColumn`, `EdgeButton`, `SwipeDismissableNavHost`, rotary built in; `rememberAmbientModeManager`. Do not mix with M2 `compose-material`. Touch targets ≥ 48 dp.
* Tiles: `androidx.wear.tiles:tiles:1.6.2` + `androidx.wear.protolayout:protolayout-material3:1.4.2`, `Material3TileService`, `launchAction` to an exported activity. Complications: `androidx.wear.watchface:watchface-complications-data-source-ktx:1.3.0` (`ShortTextComplicationData` + tap PendingIntent). Ongoing Activity: `androidx.wear:wear-ongoing:1.1.0` while a reply streams.
* Network: default network (proxied via phone over BT, else Wi-Fi/LTE); don't force Wi-Fi. OkHttp **5.5.0** + `okhttp-sse` (`EventSources.createFactory`), `readTimeout(0)` on streams. No persistent sockets in background; no foreground service for streaming (6 h/24 h dataSync budget on API 35+).
* Storage: `androidx.security:security-crypto` is deprecated. Use DataStore (`androidx.datastore:datastore-preferences:1.2.1` or proto) with bytes encrypted by an Android Keystore AES-GCM key (alias `cfw_auth`); on decrypt failure wipe and re-login. Room 2.8.4 (KSP 2.3.12) for cached threads/sessions.
* Build: AGP 9.4.0 (needs Gradle ≥ 9.6, JDK 17, built-in Kotlin: do **not** apply `org.jetbrains.kotlin.android`; keep `org.jetbrains.kotlin.plugin.compose`), Kotlin 2.4.20, Compose BOM 2026.09.00, activity-compose 1.13.0, kotlinx-coroutines 1.11.0, kotlinx-serialization. If a version pair fails to resolve here, fall back one minor version and record it in README.
* Assistant: no "Hey Google, ask Claude" for third parties (App Actions unsupported on Wear; AppFunctions/Gemini is allowlisted preview). Entry points: launcher, tile button, complication, Ongoing Activity chip.
* QR: `com.google.zxing:core:3.5.3` to encode the authorize URL as a bitmap.

## Prior art worth borrowing
* Q007 (MIT): minimal `URLSession` Messages client, Keychain service, `AskAIIntent` + `AppShortcutsProvider` ("Ask \(.applicationName)"), WatchConnectivity key sync (not used here).
* shobhit99/claude-watch (MIT): SSE delegate + `\n\n` block parser, `Last-Event-ID` resume, haptics manager, `accessoryRectangular` complication (put it in a real widget extension with App Group state).
* Staberman fork: hit-testing fix (no `ignoresSafeArea` on containers, 34 pt buttons); measured SSE lifetimes 7–100 s after wrist-down.
* Claude-pw (Wear): streaming `SpeechRecognizer` with confirm-before-send, `TileService` with approve/deny via trampoline activity, Keystore token store with corrupt-file recovery, `RemoteAuthClient` + PKCE.
* agent-watch (Wear): `RemoteInputIntentHelper` input with triple-fallback result reader (RemoteInput → `RecognizerIntent.EXTRA_RESULTS` → `EXTRA_TEXT`).
* AskWatch: Double-Tap / Action Button start dictation; complication shows provider + daily count; "no middleman servers" positioning.
* Every app hard-codes a watch-sized system prompt and small `max_tokens`; never auto-retry a paid call.
