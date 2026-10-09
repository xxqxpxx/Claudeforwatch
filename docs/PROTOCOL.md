# Claude for Watch — shared protocol spec

Both the watchOS (Swift) and Wear OS (Kotlin) clients implement exactly this.
Pure logic (PKCE, token refresh, SSE parsing, event reduction) lives in a
platform-free module on each side (`apple/ClaudeWatchKit`, `android/core`) and
is unit-tested against the fixtures in `spec/fixtures/` on Linux.

Everything in §3–§5 is **unofficial** (reverse-engineered from the official
clients). Treat every endpoint as "may change"; surface failures to the user
instead of retrying blindly.

## 1. Auth modes

```
enum AuthMode { apiKey, claudeAccount }
```

| | `apiKey` (default, supported) | `claudeAccount` (personal use, unsupported) |
| --- | --- | --- |
| Credential | Console API key `sk-ant-api03-…` | OAuth access token `sk-ant-oat01-…` + refresh token `sk-ant-ort01-…` |
| Chat (Messages API) | yes | yes (with Claude Code identity, §4) |
| Claude Code sessions | no | yes (scope `user:sessions:claude_code`) |
| Usage meter | no | yes (`/api/oauth/usage`) |
| Billing | key owner (Max/Team monthly API credits apply) | subscription limits |
| Policy | sanctioned | violates Anthropic's third-party developer policy; account risk; app must show the warning text in §1.2 before enabling |

### 1.1 Credential storage
* watchOS: Keychain, `kSecClassGenericPassword`, service `com.claudeforwatch.credentials`,
  `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, no iCloud sync.
* Wear OS: Jetpack DataStore (`auth.pb`) whose bytes are encrypted with an
  Android Keystore AES-256-GCM key alias `cfw_auth`; decrypt failure ⇒ wipe and
  ask to sign in again.
* Stored record (JSON on both platforms):
```json
{"mode":"claudeAccount","accessToken":"sk-ant-oat01-…","refreshToken":"sk-ant-ort01-…",
 "expiresAt":1760000000000,"scopes":["user:profile","user:inference","user:sessions:claude_code"],
 "organizationUuid":"…","accountEmail":"…","apiKey":null}
```

### 1.2 Required warning (shown once before enabling `claudeAccount`)
> Signing in with your Claude account uses the same private sign-in that
> Claude Code and the Claude app use. Anthropic does not support third-party
> apps using it and may block or suspend accounts that do. Use it only with
> your own account, at your own risk. The supported option is an API key.

### 1.3 Chat API key next to a Claude account
A `claudeAccount` record may also carry `apiKey`. Chat (§4) then uses the key
(`x-api-key`, plain `WATCH_SYSTEM_PROMPT`, billed to the key's Console org,
which Max and Team plans fund with monthly API credits) while sessions, usage
and §7 keep using the OAuth token. A token refresh keeps the key. Sending an
API key to a watch that is signed in with Claude adds it this way instead of
replacing the sign-in. Added after a live test where chat on the account
token returned 429.

## 2. OAuth (claudeAccount mode)

* Authorize: `https://claude.ai/oauth/authorize`
* Token: `https://platform.claude.com/v1/oauth/token`
* `client_id`: `9d1c250a-e61b-44d9-88ed-5944d1962f5e`
* Scopes (space-separated): `user:profile user:inference user:sessions:claude_code`
* PKCE: `code_verifier` = base64url(32 random bytes) (43 chars, no padding);
  `code_challenge` = base64url(SHA-256(verifier)); `state` = base64url(32 random bytes).
* Authorize URL query (in this order):
  `code=true&client_id=…&response_type=code&redirect_uri=<uri>&scope=<scopes>&code_challenge=…&code_challenge_method=S256&state=…`
* Redirect URI: always `https://platform.claude.com/oauth/code/callback`.
  (`http://localhost:<port>/callback` is accepted by the server but no watch
  can capture it: watchOS blocks low-level listeners (TN3135) and Wear OS has
  no browser.) After sign-in the page shows a code in the form
  `<code>#<state>`. The client strips everything from `#` and **must** compare
  the suffix with the stored `state`; a mismatch is an error.
* Delivering the authorize URL to a phone/laptop and the code back to the
  watch, with no companion app and no server:
  1. The watch renders the full authorize URL as a QR code (≈450 chars, fits
     a version-15 QR; render at the watch's native pixel width, high error
     correction off). Wear OS additionally offers "Open on phone"
     (`RemoteActivityHelper`) when a phone is paired; watchOS offers
     "Open here" (`ASWebAuthenticationSession`) as a last resort.
  2. The user signs in on the phone and sees the code.
  3. The user taps the watch's code field; both platforms then let the paired
     phone type into it (watchOS "Type on iPhone" sheet, Wear OS Gboard
     "use phone keyboard" notification) so the code can be pasted from the
     phone clipboard. Manual entry on the watch keyboard is the fallback.
  4. The PKCE verifier/state live in memory for 10 minutes; expired ⇒ restart.
* Token exchange: `POST` token URL, `Content-Type: application/json`, body
  `{"grant_type":"authorization_code","code":…,"state":…,"client_id":…,"redirect_uri":…,"code_verifier":…}`.
  If the response is 400 with `invalid_grant`, retry **once** with
  `application/x-www-form-urlencoded` and the same fields. Any other error is
  final.
* Response: `access_token`, `refresh_token`, `expires_in` (seconds),
  `organization.uuid`, `account.email_address`, `scope`.
  `expiresAt = now + expires_in*1000 - 60_000`.
* Refresh: `POST` token URL, headers `Content-Type: application/json`,
  `anthropic-beta: oauth-2025-04-20`, body
  `{"grant_type":"refresh_token","refresh_token":…,"client_id":…}`.
  Rotate: always persist the returned `refresh_token`. Refresh when
  `now >= expiresAt` or on any 401. A failed refresh (400/401) ⇒ signed-out state.
* Single-flight: concurrent requests share one refresh.
* Profile (optional): `GET https://api.anthropic.com/api/oauth/profile` →
  `{account:{email_address,…},organization:{uuid,name}}`.
* Usage (optional): `GET https://api.anthropic.com/api/oauth/usage` →
  `{five_hour:{utilization,resets_at},seven_day:{utilization,resets_at}}`
  (`utilization` is 0–100).

## 3. Common headers

```
anthropic-version: 2023-06-01
Content-Type: application/json
User-Agent: ClaudeForWatch/<version> (<platform>)
```
`apiKey`: `x-api-key: <key>`.
`claudeAccount`: `Authorization: Bearer <accessToken>` plus the per-endpoint
beta headers below.

## 4. Chat: Messages API

`POST https://api.anthropic.com/v1/messages`, always `"stream": true`.

Request:
```json
{"model":"claude-haiku-5-5","max_tokens":400,"stream":true,
 "system":"<SYSTEM>",
 "output_config":{"effort":"low"},
 "messages":[{"role":"user","content":"…"},{"role":"assistant","content":"…"},…]}
```
* Default model `claude-haiku-5-5`; user-selectable: `claude-sonnet-5-5`,
  `claude-opus-5-5`. Never send assistant prefill; never send `budget_tokens`.
* `<SYSTEM>` in `apiKey` mode is `WATCH_SYSTEM_PROMPT`:
  > You are Claude, answering on a smartwatch. Reply in plain text, no markdown,
  > no lists, no code fences. Be brief: one to three short sentences unless the
  > user asks for detail. If the user dictated text, tolerate transcription errors.
* `<SYSTEM>` in `claudeAccount` mode must begin with the Claude Code identity
  line, then a newline, then `WATCH_SYSTEM_PROMPT`:
  `You are Claude Code, Anthropic's official CLI for Claude.`
  and the request carries `anthropic-beta: oauth-2025-04-20,claude-code-20250219`.
* History sent = last 20 turns of the thread, trimmed from the front.
* SSE events to handle: `message_start`, `content_block_start`,
  `content_block_delta` (`delta.type == "text_delta"` → append `delta.text`;
  ignore `thinking_delta`), `content_block_stop`, `message_delta`
  (`delta.stop_reason`, `usage.output_tokens`), `message_stop`, `ping`,
  `error` (`error.type`, `error.message`). `stop_reason == "refusal"` renders
  the refusal notice; `"max_tokens"` appends "…".
* Errors: 401 ⇒ refresh (claudeAccount) or "check your key"; 429 ⇒ show
  the server's `error.message` plus the reset time from `retry-after`, else
  `anthropic-ratelimit-unified-reset` (epoch seconds), else the RFC 3339
  `anthropic-ratelimit-{requests,tokens}-reset`; on the account token also
  suggest adding a chat API key (§1.3). Failed requests are logged to logcat
  tag `CfwApi` with status, path, error type/message, `request-id` and the
  rate-limit headers (never credentials); 400 with message containing "only authorized for use with
  Claude Code" ⇒ show the §1.2 explanation; 529 ⇒ "Claude is overloaded".
* Never auto-retry a completion request (it may already have been billed).

Threads are stored locally:
```json
{"id":"uuid","title":"first 40 chars of first user message","createdAt":…,"updatedAt":…,
 "model":"claude-haiku-5-5","messages":[{"role":"user|assistant","text":"…","at":…,"stopReason":null}]}
```

## 5. Claude Code sessions (claudeAccount mode only)

Base `https://api.anthropic.com`. Headers in addition to §3:
```
Authorization: Bearer <accessToken>
anthropic-client-platform: web_claude_ai
x-organization-uuid: <organizationUuid>
anthropic-beta: ccr-byoc-2025-07-29      (NOT on GET /v1/code/sessions)
```
If a response is 403 whose body mentions trusted device, show
"This organization requires Trusted Devices; enroll from claude.ai" and stop.

### 5.1 List
`GET /v1/code/sessions` → `{data:[Session], next_cursor, resume_token}`.
```
Session { id, title, created_at, last_event_at, status: "active"|"archived",
          status_bucket, worker_status: "idle"|"running"|"requires_action",
          connection_status, environment_kind ("bridge" = Remote Control on the
          user's machine; anything else = cloud), unread: bool,
          config: { model }, external_metadata: { post_turn_summary?, pending_action? } }
```
Shape drift seen on a live account (Oct 2026): `external_metadata.post_turn_summary`
can be an **object** such as `{"needs_action":"…","summary":"…"}` instead of a
string, and `pending_action` can be a plain string. Clients keep both as raw
JSON, show `needs_action`, then `summary`/`text`/`title`, then the first string
value, and decode the list one session at a time so an undecodable session is
skipped rather than failing the screen (`spec/fixtures/sessions_list_drift.json`).

Sort for display: `requires_action` first, then `running`, then by
`last_event_at` desc. Hide `archived` unless the user asks. Poll every 20 s
while the list is on screen.

### 5.2 Transcript
`GET /v1/code/sessions/{id}/events?limit=60&sort_order=desc` →
`{data:[{sequence_num:"123", payload:{…}}]}` (reverse to chronological).
Then live: `GET /v1/code/sessions/{id}/events/stream?from_sequence_num=<last>`
with header `Last-Event-ID: <last>`. SSE frames:
```
event: client_event
id: 124
data: {"event_type":"…","sequence_num":"124","source":"…","payload":{…}}
```
Ignore `session_update`, `delivery_update`, `ephemeral_event`;
`catch_up_truncated` ⇒ refetch history. De-dupe by integer `sequence_num`.
On disconnect (watchOS suspends within seconds of wrist-down) reconnect from
the last sequence number when the view reappears; do not use extended runtime
sessions.

### 5.3 Payload reduction (what the UI shows)
```
payload.type == "user"       → if content has only tool_result blocks: hidden;
                               else bubble(role=user, text = joined text blocks)
payload.type == "assistant"  → for each content block:
                                 text      → bubble(role=assistant)
                                 tool_use  → row "⚙ <name>" + one-line summary of input
                                             (Bash: command; Edit/Write/Read: file_path;
                                              else first string value)
                                 thinking  → hidden
payload.type == "stream_event" → text_delta appended to the open assistant bubble
payload.type == "result"     → marks turn end; shows is_error banner if set
payload.type == "system"     → subtype init: "Session started (<model>)";
                               compact_boundary: "— context compacted —"
payload.type == "control_request" → see 5.5
payload.type == "control_response" / "tool_progress" / "tool_use_summary" /
  "rate_limit_event" / others → hidden
```
Header strip for a session shows `external_metadata.post_turn_summary` when
present (free summary of the last turn, ideal for the watch).

### 5.4 Send
`POST /v1/code/sessions/{id}/events`
```json
{"session_id":"<id>","events":[{"payload":{"type":"user","uuid":"<uuidv4>",
  "message":{"role":"user","content":[{"type":"text","text":"…"}]}}}]}
```
Slash commands are plain text (`/model sonnet`, `/effort high`, `/compact`).
Steering:
```json
{"payload":{"type":"control_request","request_id":"<uuidv4>","request":{"subtype":"interrupt"}}}
{"payload":{"type":"control_request","request_id":"<uuidv4>","request":{"subtype":"set_permission_mode","mode":"acceptEdits"}}}
{"payload":{"type":"control_request","request_id":"<uuidv4>","request":{"subtype":"set_model","model":"claude-sonnet-5-5"}}}
```
Quick-reply chips (one tap): "Continue", "Looks good, proceed", "Run the
tests", "Stop", "Explain what you're doing", "Commit and push".

### 5.5 Permission prompts and questions
Incoming `payload.type == "control_request"` with
`request.subtype == "can_use_tool"`:
```json
{"type":"control_request","request_id":"req_…","request":{"subtype":"can_use_tool",
 "tool_name":"Bash","input":{"command":"npm test"},"permission_suggestions":[…],
 "description":"…"}}
```
Render a full-screen card: tool name, one-line summary (§5.3), buttons
**Allow** / **Deny**, haptic on arrival. Reply:
```json
{"payload":{"type":"control_response","response":{"subtype":"success",
  "request_id":"req_…","response":{"behavior":"allow","updatedInput":<input unchanged>}}}}
```
Deny: `{"behavior":"deny","message":"User denied from watch"}`.
A `can_use_tool` whose `tool_name` is `AskUserQuestion` renders the
`input.questions[].options[]` as buttons; the answer is sent as a normal user
text message containing the chosen option label. The session list shows
`worker_status == requires_action` as a red badge; the complication/tile
shows the count.

### 5.6 Other
`POST /v1/code/sessions/{id}/mark_read` `{}` after the transcript is shown.
`POST /v1/code/sessions/{id}/archive` `{}` (swipe action; 200 or 409 = ok).
`POST /v1/code/sessions/{id}/client/presence` `{"client_id":"<installId>","connected_at":<ms>}`
on open, `{"client_id":…,"clear":true}` on close (best effort).

## 6. Start a cloud session from the watch (official routines API, optional)
If the user pastes a routine token (`sk-ant-oat01-…` shown in claude.ai when
creating an API-triggered routine) and its id (`trig_…`):
`POST https://api.anthropic.com/v1/claude_code/routines/{trig}/fire`,
`Authorization: Bearer <routineToken>`, body `{"text":"<dictated task>"}` →
`{claude_code_session_id, claude_code_session_url}`. In `claudeAccount` mode
the app then opens that session in the viewer.

## 7. claude.ai web chats (EXPERIMENTAL, personal builds)
Anthropic documents no API for claude.ai chat history. The watch tries the
routes the claude.ai web app uses, with the OAuth bearer token, on
`https://claude.ai` and then `https://api.anthropic.com`:
`GET /api/organizations/{org}/chat_conversations?limit=30` and
`GET /api/organizations/{org}/chat_conversations/{uuid}?tree=True&rendering_mode=messages`
(headers: `Authorization`, `anthropic-version`, `anthropic-beta: oauth-2025-04-20`,
`anthropic-client-platform: web_claude_ai`). These routes normally take a
browser cookie and sit behind Cloudflare, so a refusal is the expected
outcome; the app then shows what each host answered. When it works, opening a
chat copies its last 30 text messages into a local thread (`web-<uuid>`) that
can be continued on the watch. Nothing is written back to claude.ai.

## 8. Fixtures (spec/fixtures)
* `messages_stream.sse` — a full streamed Messages reply (text + stop).
* `messages_refusal.sse` — stop_reason refusal.
* `sessions_list_drift.json` — object-shaped `post_turn_summary` and one undecodable session.
* `sessions_list.json` — three sessions (requires_action, running cloud, idle bridge, one archived).
* `session_events_history.json` — `/events?limit` response with user/assistant/tool_use/result/system payloads.
* `session_events_stream.sse` — live frames incl. stream_event deltas, a `can_use_tool` control_request, a `result`, and a `session_update` to ignore.
* `oauth_token_response.json`, `oauth_refresh_response.json`.
Both platforms' unit tests must parse these to identical reduced models:
see `spec/expected/*.json`.
