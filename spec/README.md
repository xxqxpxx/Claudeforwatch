# spec

`fixtures/` are byte-exact samples of what the APIs return (see docs/PROTOCOL.md).
`expected/` describe the reduced view models both platform cores must produce.

Reduction rules under test:
* Messages SSE: concatenate `text_delta`s; capture `stop_reason`, `usage.output_tokens`, `model`; `error` events surface `error.type`/`error.message`; `ping` ignored.
* Sessions list: drop `archived`; order `requires_action`, then `running`, then `last_event_at` desc; `environment_kind == "bridge"` ⇒ `remoteControl`, else `cloud`; `needsActionCount` = number of `requires_action`.
* Session events: history arrives `desc` and is reversed; reduce per PROTOCOL §5.3. A `stream_event` text delta appends to an in-progress assistant text that is **replaced** by the final `assistant` payload with the same content (so the final list has no duplicate). Frames whose `event` is not `client_event` are ignored. Duplicate `sequence_num` frames are dropped. `tool_result`-only user payloads and `thinking` blocks are hidden. `control_request` with `can_use_tool` becomes a `permission` item and sets `pendingPermission` until a `control_response` is sent or a `result` arrives.
