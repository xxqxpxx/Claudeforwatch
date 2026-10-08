package com.claudeforwatch.core.reduce

import com.claudeforwatch.core.CoreJson
import com.claudeforwatch.core.model.ContentBlock
import com.claudeforwatch.core.model.ControlRequestBody
import com.claudeforwatch.core.model.SessionEventEnvelope
import com.claudeforwatch.core.model.SessionPayload
import com.claudeforwatch.core.net.SseEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the transcript screen shows (docs/PROTOCOL.md §5.3; spec/README.md). */
sealed interface TranscriptItem {
    val seq: Long

    /** Stable key for lazy lists: one payload can yield several items. */
    val key: String

    data class User(override val seq: Long, val text: String, override val key: String = "u$seq") : TranscriptItem
    data class Assistant(override val seq: Long, val text: String, override val key: String = "a$seq") : TranscriptItem
    data class Tool(override val seq: Long, val tool: String, val summary: String, override val key: String = "t$seq") : TranscriptItem
    data class System(override val seq: Long, val text: String, override val key: String = "s$seq") : TranscriptItem
    data class TurnEnd(override val seq: Long, val isError: Boolean, val subtype: String?, override val key: String = "r$seq") : TranscriptItem
    data class Permission(
        override val seq: Long,
        val requestId: String,
        val tool: String,
        val summary: String,
        val description: String?,
        val input: JsonObject,
        val questions: List<AskQuestion>,
        override val key: String = "p$seq",
    ) : TranscriptItem
}

/** One AskUserQuestion question (PROTOCOL §5.5): options render as buttons. */
data class AskQuestion(val question: String, val header: String?, val options: List<String>, val multiSelect: Boolean)

data class PendingPermission(
    val requestId: String,
    val tool: String,
    val summary: String,
    val description: String?,
    val input: JsonObject,
    val questions: List<AskQuestion>,
) {
    val isQuestion: Boolean get() = tool == "AskUserQuestion" && questions.isNotEmpty()
}

/** Immutable view handed to the UI. */
data class TranscriptSnapshot(
    val items: List<TranscriptItem>,
    val streamingText: String?,
    val pendingPermission: PendingPermission?,
    val lastSequence: Long?,
    val needsRefetch: Boolean,
)

/**
 * Reduces session events to [TranscriptItem]s. Not thread-safe: confine to one coroutine.
 *
 * Rules (spec/README.md): history is applied in ascending sequence order; frames whose SSE
 * `event` is not `client_event` are ignored; sequence numbers ≤ the last applied one are
 * dropped; `stream_event` text deltas build [streamingText], which the next `assistant` payload
 * replaces; tool_result-only user turns and thinking blocks are hidden; a `can_use_tool`
 * control_request adds a permission item and sets [pendingPermission] until a matching
 * control_response or a `result` arrives.
 */
class TranscriptState {
    private val _items = mutableListOf<TranscriptItem>()
    val items: List<TranscriptItem> get() = _items.toList()

    var lastSequence: Long? = null
        private set
    var streamingText: String? = null
        private set
    var pendingPermission: PendingPermission? = null
        private set

    /** Set when the server reports `catch_up_truncated`: refetch history (PROTOCOL §5.2). */
    var needsRefetch: Boolean = false
        private set

    private val _ignoredFrames = mutableListOf<String>()
    val ignoredFrames: List<String> get() = _ignoredFrames.toList()
    private val _droppedDuplicates = mutableListOf<Long>()
    val droppedDuplicates: List<Long> get() = _droppedDuplicates.toList()

    fun snapshot() = TranscriptSnapshot(items, streamingText, pendingPermission, lastSequence, needsRefetch)

    fun reset() {
        _items.clear(); lastSequence = null; streamingText = null; pendingPermission = null
        needsRefetch = false; _ignoredFrames.clear(); _droppedDuplicates.clear()
    }

    /** History from `GET …/events?sort_order=desc`: applied in ascending sequence order. */
    fun apply(history: List<SessionEventEnvelope>) {
        history.sortedBy { it.sequence ?: Long.MIN_VALUE }.forEach { apply(it) }
    }

    /** A raw SSE frame from `…/events/stream`. Returns true if it changed the transcript state. */
    fun apply(frame: SseEvent): Boolean {
        when (frame.event) {
            CLIENT_EVENT -> Unit
            CATCH_UP_TRUNCATED -> { needsRefetch = true; return true }
            else -> { _ignoredFrames += frame.event; return false }
        }
        val envelope = runCatching { SessionEventEnvelope.decode(frame.data) }.getOrNull() ?: return false
        if (envelope.eventType != null && envelope.eventType != CLIENT_EVENT) {
            _ignoredFrames += envelope.eventType
            return false
        }
        return apply(envelope)
    }

    fun apply(envelope: SessionEventEnvelope): Boolean {
        val seq = envelope.sequence
        val last = lastSequence
        if (seq != null && last != null && seq <= last) {
            _droppedDuplicates += seq
            return false
        }
        if (seq != null) lastSequence = seq
        val payload = envelope.payload ?: return true
        reduce(seq ?: (last ?: 0L), payload)
        return true
    }

    /** Call after sending a control_response so the card closes immediately. */
    fun resolvePermission(requestId: String) {
        if (pendingPermission?.requestId == requestId) pendingPermission = null
    }

    private fun reduce(seq: Long, payload: SessionPayload) {
        when (payload) {
            is SessionPayload.User -> {
                val blocks = payload.message?.content.orEmpty()
                val texts = blocks.filterIsInstance<ContentBlock.Text>().map { it.text }.filter { it.isNotBlank() }
                if (texts.isNotEmpty()) _items += TranscriptItem.User(seq, texts.joinToString("\n"))
                // tool_result-only (or empty) user payloads are hidden.
            }
            is SessionPayload.Assistant -> {
                streamingText = null // the final payload replaces the streamed text
                payload.message?.content.orEmpty().forEachIndexed { i, block ->
                    when (block) {
                        is ContentBlock.Text -> if (block.text.isNotBlank()) {
                            _items += TranscriptItem.Assistant(seq, block.text, key = "a$seq.$i")
                        }
                        is ContentBlock.ToolUse ->
                            _items += TranscriptItem.Tool(seq, block.name, ToolSummary.of(block.name, block.input), key = "t$seq.$i")
                        is ContentBlock.Thinking, is ContentBlock.ToolResult, is ContentBlock.Unknown -> Unit
                    }
                }
            }
            is SessionPayload.StreamEvent -> {
                val delta = payload.textDelta
                if (delta != null) streamingText = (streamingText ?: "") + delta
                else if (payload.streamEventType == "message_start") streamingText = null
            }
            is SessionPayload.Result -> {
                streamingText = null
                pendingPermission = null
                _items += TranscriptItem.TurnEnd(seq, payload.isError, payload.subtype)
            }
            is SessionPayload.System -> when (payload.subtype) {
                "init" -> _items += TranscriptItem.System(
                    seq, payload.model?.let { "Session started ($it)" } ?: "Session started",
                )
                "compact_boundary" -> _items += TranscriptItem.System(seq, "— context compacted —")
                else -> Unit
            }
            is SessionPayload.ControlRequest -> {
                val body = payload.request
                val requestId = payload.requestId
                if (body is ControlRequestBody.CanUseTool && requestId != null) {
                    val summary = ToolSummary.of(body.toolName, body.input)
                    val questions = if (body.toolName == "AskUserQuestion") parseQuestions(body.input) else emptyList()
                    _items += TranscriptItem.Permission(seq, requestId, body.toolName, summary, body.description, body.input, questions)
                    pendingPermission = PendingPermission(requestId, body.toolName, summary, body.description, body.input, questions)
                }
            }
            is SessionPayload.ControlResponse -> {
                val id = payload.requestId
                if (id == null || pendingPermission?.requestId == id) pendingPermission = null
            }
            is SessionPayload.Unknown -> Unit // tool_progress, tool_use_summary, rate_limit_event, …
        }
    }

    companion object {
        const val CLIENT_EVENT = "client_event"
        const val CATCH_UP_TRUNCATED = "catch_up_truncated"

        fun parseQuestions(input: JsonObject): List<AskQuestion> {
            val arr = input["questions"] as? JsonArray ?: return emptyList()
            return arr.mapNotNull { q ->
                val o = q as? JsonObject ?: return@mapNotNull null
                val options = (o["options"] as? JsonArray).orEmpty().mapNotNull { opt ->
                    when (opt) {
                        is JsonObject -> (opt["label"] as? JsonPrimitive)?.content
                        is JsonPrimitive -> opt.content
                        else -> null
                    }
                }
                AskQuestion(
                    question = (o["question"] as? JsonPrimitive)?.content ?: "",
                    header = (o["header"] as? JsonPrimitive)?.content,
                    options = options,
                    multiSelect = (o["multiSelect"] as? JsonPrimitive)?.content == "true",
                )
            }
        }

        /** Convenience: decodes a `…/events` response body and applies it. */
        fun fromHistoryJson(json: String): TranscriptState {
            val response = CoreJson.decodeFromString(com.claudeforwatch.core.model.SessionEventsResponse.serializer(), json)
            return TranscriptState().also { it.apply(response.data) }
        }
    }
}
