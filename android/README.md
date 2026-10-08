# Claude for Watch — Android / Wear OS

Standalone Wear OS client. Talks directly to `api.anthropic.com` (no bridge server).
Protocol: `../docs/PROTOCOL.md`. Plan: `../docs/PLAN.md`.

```
android/
  core/   Kotlin/JVM library, no Android imports: PKCE + OAuth, AuthProvider (single-flight
          refresh, per-endpoint headers), SSE parser, Messages / Sessions / Usage / Routines
          clients, DTOs, transcript + session-list reducers, chat thread store.
          Tested on the JVM against ../spec/fixtures and ../spec/expected.
  wear/   Wear OS app (Compose for Wear OS Material 3): OkHttp transport, Keystore-encrypted
          DataStore, speech / RemoteInput text entry, QR sign-in, tile, complication,
          Ongoing Activity.
```

## Versions (what actually resolves and compiles here)

| Component | Version | Note |
| --- | --- | --- |
| Gradle (wrapper) | 9.8.1 | |
| Android Gradle Plugin | 9.4.1 | built-in Kotlin; `org.jetbrains.kotlin.android` is not applied |
| Kotlin (+ compose, serialization plugins) | 2.4.20 | |
| JDK | 21 to run Gradle; bytecode target 17 | |
| compileSdk / targetSdk / minSdk | 36 / 35 / 30 | |
| Compose BOM | 2026.06.01 (Compose UI 1.11.4) | **stepped down** from 2026.09.00 |
| Wear Compose Material 3 / Foundation / Navigation | 1.6.2 | **stepped down** from 1.7.1 |
| activity-compose | 1.13.0 | |
| lifecycle (viewmodel-compose, runtime-compose) | 2.10.0 | **stepped down** from 2.11.0 |
| core-ktx | 1.18.0 | **stepped down** from 1.19.x |
| OkHttp + okhttp-sse | 5.4.0 | **stepped down** from 5.5.0 |
| kotlinx-coroutines | 1.11.0 | |
| kotlinx-serialization-json | 1.11.0 | |
| DataStore (preferences + core) | 1.2.1 | |
| wear-input / wear-remote-interactions / wear-ongoing / wear | 1.2.0 / 1.2.0 / 1.1.0 / 1.4.0 | |
| tiles / protolayout(-material3) | 1.6.2 / 1.4.2 | |
| watchface-complications-data-source-ktx | 1.3.0 | |
| zxing core | 3.5.4 | |
| JUnit (core tests) | 6.1.3 (Jupiter) + kotlin-test | |

Why the step-downs: Wear Compose 1.7.x, Compose 1.12 (BOM ≥ 2026.08), core 1.19,
lifecycle 2.11 and OkHttp 5.5 declare `minCompileSdk = 37` in their AAR metadata, but the
plan fixes compileSdk at 36 and only `platforms;android-36` is installed. Each library was
moved to its newest release that still compiles against android-36. To move up later: install
`platforms;android-37`, set `compileSdk = 37` in `wear/build.gradle.kts` (targetSdk can stay
35) and bump the five versions in `gradle/libs.versions.toml`.

Repositories: `google()`, Google's public mirror of Maven Central
(`maven-central.storage-download.googleapis.com`), then `mavenCentral()`. The mirror is
there only because repo1 returned HTTP 429 to this build machine; it serves the same
artifacts and can be removed from `settings.gradle.kts` if you don't need it.

## Build

```sh
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties   # gitignored
./gradlew :core:test                    # JVM tests against ../spec
./gradlew :wear:assembleDebug           # both flavors: storeDebug + personalDebug
./gradlew :wear:assemblePersonalDebug   # only the personal build
./gradlew :wear:lintStoreDebug
```

APKs land in `wear/build/outputs/apk/<flavor>/<buildType>/`.

### Flavors and `PERSONAL_MODE`

| Flavor | applicationId | `BuildConfig.PERSONAL_MODE` | Sign-in options |
| --- | --- | --- | --- |
| `store` | `com.claudeforwatch` | `false` | API key only (the only build that may be distributed) |
| `personal` | `com.claudeforwatch.personal` | `true` | API key, or "Sign in with Claude" (sessions, usage) |

The personal flavor uses Anthropic's private Claude Code OAuth client and sessions API.
That is unsupported and against Anthropic's policy for third-party apps (PROTOCOL §1, §1.2);
the app shows the §1.2 warning before the flow starts. Build it for your own account only and
never publish it. Both flavors can be installed side by side.

## Install on an emulator or a watch

Emulator: create a "Wear OS Large Round" AVD (API 34 or 35, Google Play image so the system
speech recognizer and keyboard exist), start it, then:

```sh
adb install -r wear/build/outputs/apk/personal/debug/wear-personal-debug.apk
```

Galaxy Watch / Pixel Watch over Wi-Fi:

1. On the watch: Settings → About watch → Software → tap *Software version* 5× (Developer
   options), then Developer options → *ADB debugging* and *Wireless debugging* on.
2. Wireless debugging → *Pair new device*: note the pairing code, IP and port.
3. On the computer: `adb pair <ip>:<pair-port>` (enter the code), then
   `adb connect <ip>:<port>` (port shown on the Wireless debugging screen).
4. `adb -s <ip>:<port> install -r wear/build/outputs/apk/personal/debug/wear-personal-debug.apk`

Add the tile from the watch's tile carousel (long-press a tile → +), and the complication
from a watch face's edit screen ("Claude", SHORT_TEXT).

## Sign-in walkthrough

**API key (both flavors).** Home → *Sign in* → *Use an API key*. The system input sheet
opens; on a paired phone a "use phone keyboard" notification (Gboard) appears, so you can
paste a key copied on the phone. The watch validates the key with a 1-token request
(`claude-haiku-5-5`) before storing it encrypted.

**Claude account (personal flavor).**

1. Home → *Sign in* → *Sign in with Claude* → read the warning → *I understand*.
2. The watch shows a QR code of the authorize URL (`claude.ai/oauth/authorize?...`, PROTOCOL §2).
   Scan it with the phone camera, **or** tap the QR → *Open on phone* (uses
   `RemoteActivityHelper`; needs the phone to be paired through the Wear OS / Galaxy
   Wearable app).
3. Sign in on the phone. The callback page shows a code like `abc…#xyz…`. Copy all of it.
4. On the watch tap *Enter code* (edge button). Paste the code with the phone keyboard
   notification, or type it with the watch keyboard as a fallback.
5. The watch checks that the `#state` suffix matches, exchanges the code (JSON, then form
   on `invalid_grant`), stores the tokens encrypted and shows *Sessions*.

The PKCE verifier/state are valid for 10 minutes; after that *New code* restarts the flow.
Tokens refresh silently (one shared refresh for concurrent requests; the rotated refresh token
is persisted first). A rejected refresh signs you out. *Settings → Sign out* removes the
credentials, routine token, chats and tile/complication state.

## How the app behaves

* **Ask**: big mic → system dictation (`ACTION_RECOGNIZE_SPEECH`; falls back to the RemoteInput
  sheet if no recognizer) → streamed reply (Messages API, `max_tokens` 400, effort low, last 20
  turns) → optional read-aloud (TextToSpeech). Each question starts a new chat thread you can
  continue from *Chats*. An Ongoing Activity chip is shown while a reply streams (needs the
  notification permission, requested once on API 33+).
* **Sessions** (personal + Claude account): list polled every 20 s while visible, needs-action
  first; transcript loads 60 events then streams from the last sequence number. Streams stop
  in `onStop` and resume from `lastSequence` in `onStart`. The EdgeButton opens Dictate /
  Type / Quick replies; long-press (or tap the title row) for Interrupt / Model / Permission
  mode / Archive. Permission prompts open a full-screen Allow / Deny card with a haptic.
* **Tile**: needs-action count + "Ask Claude" (launches `MainActivity` with
  `com.claudeforwatch.OPEN=ask`). **Complication**: SHORT_TEXT count, else 5-hour usage %, else
  "Ask". Both read a DataStore snapshot the app updates when the list or usage refreshes.
* **Routine** (optional, PROTOCOL §6): set a routine id + token in Settings; *Run routine* /
  *New cloud task* dictates the task and fires it.
* Secrets (credentials, routine token) are stored as AES-256-GCM blobs (Keystore key alias
  `cfw_auth`) in DataStore files `auth.pb` / `routine.pb`; an undecryptable blob is wiped.
  Backups are disabled. Nothing logs headers or bodies.

## Not verified on hardware yet

Everything below compiles and the protocol logic is unit-tested, but none of it has run on a
watch or emulator in this session:

* Any real network call: Messages streaming through OkHttp on a watch (BT proxy / Wi-Fi / LTE),
  401 → refresh → retry, 429 `retry-after` copy, 529 handling.
* The OAuth flow end to end: the QR being scannable at 56 % of a round screen, *Open on phone*
  via `RemoteActivityHelper` (Pixel Watch vs Galaxy Watch), pasting the code through the Gboard
  phone-keyboard notification, the JSON → form fallback against the live token endpoint.
* The private sessions API: list / history / stream with `from_sequence_num` + `Last-Event-ID`,
  presence, mark_read, archive, interrupt, set_model, set_permission_mode, permission
  allow/deny, and whether streams are closed by the server periodically (the app reconnects
  with backoff).
* AskUserQuestion answers: implemented per PROTOCOL §5.5 as a plain user message with the
  option label (the card is resolved locally). Claude Code may instead expect a
  `control_response` (allow with `updatedInput.answers`); check against a live session.
* System speech recognizer and RemoteInput result extras on Galaxy Watch (the triple-fallback
  reader covers RemoteInput, `EXTRA_RESULTS` and `EXTRA_TEXT`).
* TextToSpeech availability (some watches ship without a TTS engine).
* Keystore key creation / decrypt-failure wipe after backup-restore or factory reset.
* Tile and complication rendering, refresh requests, and the tap targets they launch.
* Ongoing Activity chip appearance and the POST_NOTIFICATIONS prompt.
* Layout on small round (≈ 192 dp) and square screens, large font scale, rotary scrolling.
* Release (R8-minified) build behaviour of kotlinx.serialization at runtime.
