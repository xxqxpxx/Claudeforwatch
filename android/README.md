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
          Ongoing Activity, Data Layer provisioning listener.
  phone/  Minimal Android phone companion (Compose Material 3): signs in / takes an API key on
          the phone and sends it to the watch over the Wear Data Layer. Stores nothing.
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
| play-services-wearable (wear + phone) | 20.0.1 | newest on Google Maven; compiles against android-36 |
| androidx.browser (phone, Custom Tabs) | 1.10.0 | |
| Compose Material 3 (phone) | 1.4.0 | from the Compose BOM above |
| kotlinx-coroutines-play-services (phone) | 1.11.0 | `Task.await()` |
| phone minSdk / targetSdk / compileSdk | 26 / 35 / 36 | |
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
./gradlew :phone:assemblePersonalDebug  # phone companion (same flavors as the watch)
```

APKs land in `wear/build/outputs/apk/<flavor>/<buildType>/` and `phone/build/outputs/apk/<flavor>/<buildType>/`.

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

## Provisioning from your phone

The easiest way to get credentials onto the watch if you have an **Android** phone: the small
companion app in `phone/` does the sign-in on the phone and sends the result to the watch over
the Wear Data Layer (Bluetooth / Wi-Fi through the Wear OS app). No computer, no ADB, no typing on
the watch.

1. Build and install the phone APK with the **same flavor** as the watch app
   (`phone-personal-*.apk` for `wear-personal-*.apk`, `phone-store-*.apk` for `wear-store-*.apk`):
   ```sh
   ./gradlew :phone:assemblePersonalDebug :wear:assemblePersonalDebug
   adb -s <phone> install -r phone/build/outputs/apk/personal/debug/phone-personal-debug.apk
   adb -s <watch> install -r wear/build/outputs/apk/personal/debug/wear-personal-debug.apk
   ```
2. Make sure the watch is paired with the phone in the Wear OS app (or Galaxy Wearable). The
   phone app's *Watches* row lists the connected watches.
3. Personal flavor: read the warning, tick *I understand*, tap **Sign in with Claude and send to
   watch**, sign in in the browser tab that opens, and return to the app. Done: each watch replies
   `ok: signed in as …` (also shown as a toast on the watch). If the browser can't come back to
   the app (it never reaches `http://localhost`), tap *Paste the code instead*, open the code
   page, and paste the `code#state` it shows.
   Any flavor: paste a Console key into **Send an API key to the watch** and tap *Send to watch*.

How it works: the phone runs PKCE (PROTOCOL §2) with a `http://localhost:<port>/callback`
redirect caught by a one-shot socket on 127.0.0.1 (the paste fallback uses the
platform.claude.com redirect with the same verifier/state, like `watch-login.py`), exchanges the
code, fills the org/email from `/api/oauth/profile` if missing, and sends the PROTOCOL §1.1 record
(base64) as `{"kind":"credentials","value":…}` on `/claudeforwatch/provision`; an API key goes as
`{"kind":"api_key","value":…}`. The watch's `ProvisionListenerService` applies it with the same
code as the ADB receiver and answers on `/claudeforwatch/provision/result`. As with the script,
this is a token pair separate from any other login. The phone keeps everything in memory only
and never logs or shows tokens.

Requirements and limits:

* **Both APKs must have the same application ID and be signed with the same key.** The Data
  Layer only delivers messages between apps that match on both; that is also its security
  boundary, which is why the listener is safe to ship in every flavor (store builds accept
  `api_key` only; Claude-account records need the personal build, as with ADB). Debug builds
  made on one machine share the debug key; for release builds sign both with your own key.
* **iPhone users can't use this**: a Wear OS watch paired with an iPhone isn't possible, and the
  Data Layer needs an Android phone. Use `scripts/watch-login.py` over ADB instead (below).
  (Apple Watch users don't need either: see the end of the next section.)

## Getting credentials onto the watch

A watch has no clipboard, and the phone-keyboard handoff above needs a paired phone with
Gboard. With ADB (USB on an emulator, Wireless debugging on a watch, see *Install*) you can push
credentials from the computer instead. No server and no phone app are involved.

The app has a `ProvisionReceiver` for this (action `com.claudeforwatch.PROVISION`). It is
declared **only in the `personal` flavor and in debug builds**: it is exported without a
permission, so any app on the watch could send it and overwrite the stored credentials (it can't
read them). The distributable `store` release APK doesn't contain it. The broadcast prints
`Broadcast completed: result=0, data="ok: signed in as …"` on success,
`result=1, data="error: …"` on failure, and `result=0` with no `data` if the receiver isn't in
the installed build or the package name is wrong. The watch also shows a toast and buzzes, and an
open sign-in screen moves on by itself.

**1. Script (preferred).** `../scripts/watch-login.py` (Python 3.8+, stdlib only) does the
whole Claude sign-in in the computer's browser and pushes the result:

```sh
python3 scripts/watch-login.py                              # sign in, push to the only adb device
python3 scripts/watch-login.py --serial 192.168.1.20:41235  # pick a device
python3 scripts/watch-login.py --code 'abc…#xyz…'           # finish the QR sign-in the watch is showing
python3 scripts/watch-login.py --api-key -                  # API key, typed hidden
python3 scripts/watch-login.py --print-only                 # just print the base64 record
python3 scripts/watch-login.py --self-test
```

It runs PKCE (PROTOCOL §2) with a `http://localhost:<port>/callback` redirect, exchanges the
code, and sends the PROTOCOL §1.1 record as `--es credentials_b64`. If the browser can't reach
localhost (SSH, another machine), open the second URL it prints and paste the `code#state` that
platform.claude.com shows. That login is a **separate token pair** from Claude Code's own login
on the computer, so the watch's refresh-token rotation never logs the `claude` CLI out. It uses
Claude Code's OAuth client: personal use only (§1.2). `--package` defaults to
`com.claudeforwatch.personal`; use `--package com.claudeforwatch` for a store *debug* build
(API keys only there).

**2. Raw `adb` (no script).** Exactly one of `api_key`, `oauth_code`, `credentials_b64`:

```sh
adb shell am broadcast -a com.claudeforwatch.PROVISION --include-stopped-packages \
  -n com.claudeforwatch.personal/com.claudeforwatch.provision.ProvisionReceiver \
  --es api_key sk-ant-api03-…

# Finish the QR sign-in the watch is showing (within its 10 minutes); quote it for the # sign:
adb shell am broadcast -a com.claudeforwatch.PROVISION --include-stopped-packages \
  -n com.claudeforwatch.personal/com.claudeforwatch.provision.ProvisionReceiver \
  --es oauth_code "'abc…#xyz…'"
```

`oauth_code` uses the PKCE verifier the watch saved when it drew the QR (kept in a small
DataStore for 10 minutes, so it survives the app being closed). `credentials_b64` takes a base64
PROTOCOL §1.1 record; a missing `expiresAt` becomes now + 8 h − 60 s, an expired token is
refreshed once, and a missing `organizationUuid` is filled from `/api/oauth/profile`. The
argument is visible in `ps` on the computer while `adb` runs.

**3. Zero-code fallback: let adb type.** Open the watch's code (or API key) field so the
keyboard is up, then:

```sh
adb shell input text 'abc…\#xyz…'
```

Escape `#` as `\#` and type spaces as `%s` (`input text` treats a bare `%s` as a space).

Apple Watch users don't need any of this: the code field opens the system "Type on iPhone"
sheet, which accepts a paste from the iPhone clipboard.

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
* The phone companion end to end: Data Layer delivery and the watch's reply on a real paired
  phone + watch, `connectedNodes` naming, Chrome Custom Tab redirecting to `http://localhost`
  (and the paste fallback when it doesn't), the live token exchange from the phone, and the
  phone UI on small screens / large font scale.
