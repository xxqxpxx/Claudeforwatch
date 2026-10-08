# Research: Anthropic auth, sessions and chat access (as of 2026-10-08)

This file records what we verified about how a watch client can talk to Claude
with no bridge server. Everything marked **unofficial** comes from
reverse-engineered client behaviour and can change without notice.

## 1. Policy (official)

Source: https://code.claude.com/docs/en/legal-and-compliance ("Authentication
and credential use"), https://code.claude.com/docs/en/agent-sdk/overview,
https://support.claude.com/en/articles/15036540-use-the-claude-agent-sdk-with-your-claude-plan

* OAuth (claude.ai login) is "intended exclusively for purchasers of Claude
  Free, Pro, Max, Team, and Enterprise subscription plans and is designed to
  support ordinary use of Claude Code and other native Anthropic applications."
* "Anthropic does not permit third-party developers to offer Claude.ai login
  into their own applications, or to route requests through Free, Pro, or Max
  plan credentials on behalf of their users. Moreover, developers may not
  collect, store, or intermediate Claude.ai credentials or session tokens —
  sign-in to a Claude account must complete through Anthropic's own flow."
* Agent SDK page: "Unless previously approved, Anthropic does not allow third
  party developers to offer claude.ai login or rate limits for their products."
* Help-center timeline: Jan 2026 enforcement against third-party OAuth clients;
  Apr 4 2026 block of third-party agents on subscriptions; May 13 2026 reversal
  with an "Agent SDK credit"; **Jun 15 2026 that change was paused** ("For now,
  nothing has changed: Agent SDK, `claude -p`, and third-party app usage still
  draw from your subscription limits"); Oct 7 2026 note: Max and Team plans now
  include monthly API credits covering the Agent SDK, `claude -p`, the Claude
  API and Managed Agents, and "you can still use the Agent SDK, `claude -p`,
  and third-party apps with your subscription limits."

Consequence for this project (see PLAN.md "Decision: auth modes"):

* Distributing an app to the public that signs people into claude.ai is not
  permitted without Anthropic's approval. API-key sign-in is the sanctioned
  path for a published app.
* A personal, self-built client where *you* sign in to *your own* account via
  Anthropic's own browser flow is the same posture as OpenCode/OpenClaw-style
  tools that Anthropic currently tolerates and meters against subscription
  limits. It is still "unofficial and unsupported" and may break or be
  enforced against at any time. The app must make this explicit.

## 2. Claude Code OAuth flow (unofficial, stable since 2025)

Sources: https://flopsstuff.github.io/coqu/claude-oauth/,
https://akashmohan.com/writings/claude-code-oauth,
https://github.com/ThatCrispyToast/claude-rc-api/blob/main/API_REFERENCE.md,
https://code.claude.com/docs/en/authentication (official, confirms the
localhost callback server and the "paste code" fallback).

| Item | Value |
| --- | --- |
| Authorize URL | `https://claude.ai/oauth/authorize` (claude.ai accounts) or `https://platform.claude.com/oauth/authorize` (Console accounts) |
| Token URL | `https://platform.claude.com/v1/oauth/token` (older write-ups say `console.anthropic.com`, which redirects) |
| client_id | `9d1c250a-e61b-44d9-88ed-5944d1962f5e` (Claude Code public client, no secret) |
| Scopes | `user:profile user:inference user:sessions:claude_code user:mcp_servers` (older: `org:create_api_key`, `user:file_upload`) |
| Redirect URIs accepted | `https://platform.claude.com/oauth/code/callback` (shows the code on screen for manual paste) and `http://localhost:<port>/callback` (Claude Code's local callback server) |
| Authorize params | `code=true`, `client_id`, `response_type=code`, `redirect_uri`, `scope`, `code_challenge`, `code_challenge_method=S256`, `state` |
| PKCE | verifier = base64url(32 random bytes); challenge = base64url(SHA256(verifier)); state = base64url(32 random bytes) |
| Code format (manual page) | 92 chars: `<48-char code>#<state>`; strip everything after `#`, verify state matches |
| Token exchange | POST, JSON body (coqu reports form-encoded; Claude Code sends JSON; implement JSON first, form fallback): `grant_type=authorization_code`, `code`, `state`, `client_id`, `redirect_uri`, `code_verifier` |
| Token response | `token_type=Bearer`, `access_token` (`sk-ant-oat01-…`), `refresh_token` (`sk-ant-ort01-…`), `expires_in=28800` (8 h), `scope`, `organization{uuid,name}`, `account{uuid,email_address}` |
| Refresh | POST same token URL: `grant_type=refresh_token`, `refresh_token`, `client_id`, header `anthropic-beta: oauth-2025-04-20`; refresh tokens rotate, store the newest |
| `claude setup-token` | official 1-year token; "can only make model requests, so it can't establish Remote Control sessions" → not usable for the sessions feature |

## 3. Messages API with an OAuth token (unofficial)

* `POST https://api.anthropic.com/v1/messages`
* Headers: `Authorization: Bearer <access_token>`, `anthropic-version: 2023-06-01`,
  `anthropic-beta: oauth-2025-04-20,claude-code-20250219`, `Content-Type: application/json`
* The server only accepts the request when the system prompt begins with
  `You are Claude Code, Anthropic's official CLI for Claude.` (own instructions
  may follow). This is the identity gate for subscription inference.
* Streaming uses the standard Messages SSE format (`message_start`,
  `content_block_delta` with `text_delta`, `message_delta`, `message_stop`).
* With an **API key** instead: `x-api-key: <key>`, no beta header, any system prompt.

## 4. Claude Code sessions API (unofficial; what claude.ai/code and the Claude mobile app use)

Source: https://github.com/ThatCrispyToast/claude-rc-api (MIT) API_REFERENCE.md.
Official docs confirm the model: cloud sessions and Remote Control share one
session list and "the same Remote Control session infrastructure"
(https://code.claude.com/docs/en/claude-code-on-the-web,
https://code.claude.com/docs/en/remote-control). Session IDs are `session_…`
(cloud) or `cse_…` (remote control).

* Requires scope `user:sessions:claude_code`; API keys are refused.
* Base: `https://api.anthropic.com`
* Headers: `Authorization: Bearer <token>`, `anthropic-version: 2023-06-01`,
  `anthropic-client-platform: web_claude_ai` (accepted set: `ios`, `android`,
  `web_claude_ai`, `desktop_app`; anything else makes the worker ignore you),
  `x-organization-uuid: <org uuid from token response>`,
  `anthropic-beta: ccr-byoc-2025-07-29` on `/v1/code/*` except the bare list,
  optional `X-Trusted-Device-Token` when the org enables Trusted Devices.
* `GET /v1/code/sessions` → `{data:[{id,title,created_at,last_event_at,status,
  status_bucket,worker_status(idle|running|requires_action),connection_status,
  environment_kind(bridge=remote control),config{model},unread,
  external_metadata{post_turn_summary,pending_action}}],next_cursor,resume_token}`
* `GET /v1/code/sessions/{id}`; `PUT` `{title}`; `POST …/archive` `{}`;
  `POST …/mark_read`; `POST …/client/presence` `{client_id,connected_at}`.
* `GET /v1/code/sessions/{id}/events?limit=N&sort_order=desc` → `{data:[{sequence_num,payload}]}`
* `GET /v1/code/sessions/{id}/events/stream?from_sequence_num=N` SSE; frames
  `event: client_event`, `id: <seq>`, `data: {event_type,sequence_num,source,payload}`;
  reconnect with `from_sequence_num` + `Last-Event-ID`; de-dupe by sequence_num
  (arrives as a string).
* `POST /v1/code/sessions/{id}/events` body
  `{"session_id": id, "events": [{"payload": {...}}]}` where a user turn is
  Claude Code stream-json: `{"type":"user","message":{"role":"user","content":[{"type":"text","text":"…"}]}}`
  (`image` base64 blocks also accepted). Slash commands (`/model sonnet`,
  `/effort high`, `/compact`) are sent as plain user text.
* Steering: payload `{"type":"control_request","request_id":…,"request":{"subtype":"interrupt"}}`
  also `set_model {model}`, `set_permission_mode {mode}`.
* Permission prompts arrive as `control_request` with `subtype: can_use_tool`;
  the client answers with a `control_response` payload (allow/deny) carrying the
  same `request_id`. `worker_status == requires_action` and
  `external_metadata.pending_action` flag a waiting prompt in the list.
* Payload types to render: `system`(init/compact_boundary), `user`, `assistant`
  (content blocks: text, tool_use, thinking), `result` (turn end: `subtype`,
  `is_error`, `usage`), `stream_event` (partial deltas), `tool_progress`,
  `tool_use_summary`, `rate_limit_event`.
* Creating a *cloud* session from a controller is not documented in the
  reverse-engineered reference; `claude --cloud` does it from the CLI. Treat
  "new cloud session from the watch" as a stretch goal to be discovered by
  observing claude.ai/code network traffic.

## 5. claude.ai chat conversations

* No official API exposes claude.ai web/mobile chat history to third parties.
  Only Enterprise organizations get the read-only Compliance API
  (https://platform.claude.com/docs/en/manage-claude/compliance-sessions), and
  it covers Cowork sessions, not personal chats.
* The browser-internal `claude.ai/api/organizations/{org}/chat_conversations`
  endpoints are cookie-authenticated, Cloudflare-protected, and explicitly
  outside what the OAuth token can reach. Not viable from a watch.

Decision: "conversations" on the watch means (a) Claude Code sessions (cloud
and Remote Control) which we can list, read and steer, and (b) the app's own
lightweight chat threads stored on the watch, backed by the Messages API.

## 6. Other official surfaces considered

* **Managed Agents** (`/v1/sessions`, `anthropic-beta: managed-agents-2026-04-01`,
  API key): hosted agent sessions with SSE events. Viable as a future "API-key
  mode" for coding sessions, billed to the key owner. Not in v1.
* **Push notifications**: Remote Control pushes go only to the official Claude
  mobile app. A standalone watch app cannot receive them without a server, so
  v1 polls/streams while in the foreground and uses complications for status.

## 7. Additions from the second research pass (verified)

* **Monthly API credits for subscribers (official, Oct 7 2026)**: Max 5x $100/mo,
  Max 20x $200/mo, Team $20 per Standard seat + $100 per Premium seat (pooled,
  cap $500). Pro, Free and Enterprise are not eligible. Claimed by linking a
  Console organization at claude.ai Settings > Billing after 7 days on the
  plan. Covers the Claude API, Managed Agents and the Agent SDK; no rollover.
  Source: https://platform.claude.com/docs/en/about-claude/api-credits-for-subscribers.
  → This is the sanctioned way to "use your Claude subscription" from a
  third-party watch app: an API key in the linked Console org.
* **Enforcement detail**: calling `/v1/messages` with a subscription OAuth
  token and without the Claude Code identity headers returns
  `400 invalid_request_error: "This credential is only authorized for use with
  Claude Code and cannot be used for other API requests."` Spoofing the
  identity (beta `claude-code-20250219` + "You are Claude Code…" system
  prompt) is exactly what Anthropic acted against in Jan–Feb 2026, with account
  bans reported (The Register, 2026-02-20). Policy risk for a distributed app:
  high. Personal use of your own account: still a Consumer ToS violation.
* **Routines fire endpoint (official, experimental)**:
  `POST https://api.anthropic.com/v1/claude_code/routines/{trig_…}/fire`,
  `Authorization: Bearer <per-routine token>`, `anthropic-version: 2023-06-01`,
  body `{"text": "…"}` → `{claude_code_session_id, claude_code_session_url}`.
  Trigger-only, no read access, 30/h per routine. A watch can legitimately
  start a predefined cloud session this way. Source:
  https://platform.claude.com/docs/en/api/claude-code/routines-fire.
* **Usage endpoint (unofficial)**: `GET https://api.anthropic.com/api/oauth/usage`
  with the OAuth token returns `five_hour` / `seven_day` `{utilization, resets_at}`;
  `GET /api/oauth/profile` returns account + organization. Useful for a
  "usage" complication in Claude-account mode.
* **Audio input is not supported by the Messages API** (no audio content
  block in 2026). Voice must be transcribed on-device (SFSpeechRecognizer on
  watchOS, SpeechRecognizer/RemoteInput on Wear OS) and sent as text.
* **Model choice for the watch**: `claude-haiku-5-5` ($0.10/$0.50 per MTok,
  1M context) with `output_config: {effort: "low"}` and small `max_tokens` for
  fast replies; handle `stop_reason: "refusal"`. Assistant prefill is rejected
  on all 5.x models.
* **Managed Agents** (`/v1/sessions`, SSE stream with `event_deltas[]=agent.message`)
  work with an API key and are covered by the subscriber credits: a compliant
  way to run persistent agent sessions from the watch, though they run in an
  Anthropic sandbox rather than on the user's machine.
* The `GET /v1/code/sessions` controller API is private; token exchange body
  may need `application/x-www-form-urlencoded` (one source) rather than JSON
  (two sources). The shared client tries JSON and falls back to form encoding.
