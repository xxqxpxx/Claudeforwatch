package com.claudeforwatch.core.api

import com.claudeforwatch.core.CoreJson
import com.claudeforwatch.core.auth.AuthMode
import com.claudeforwatch.core.auth.Endpoint
import com.claudeforwatch.core.model.ContentBlockDeltaEvent
import com.claudeforwatch.core.model.MessageDeltaEvent
import com.claudeforwatch.core.model.MessageStartEvent
import com.claudeforwatch.core.model.StreamErrorEvent
import com.claudeforwatch.core.net.SseEvent
import com.claudeforwatch.core.net.sseEvents
import com.claudeforwatch.core.store.ChatMessage
import com.claudeforwatch.core.store.ChatRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** docs/PROTOCOL.md §4 system prompt for every chat request. */
const val WATCH_SYSTEM_PROMPT =
    "You are Claude, answering on a smartwatch. Reply in plain text, no markdown, no lists, no code fences. " +
        "Be brief: one to three short sentences unless the user asks for detail. " +
        "If the user dictated text, tolerate transcription errors."

/** PROTOCOL §4: claudeAccount chat must begin with this identity line (unofficial gate). */
const val CLAUDE_CODE_IDENTITY_LINE = "You are Claude Code, Anthropic's official CLI for Claude."

enum class ClaudeModel(val id: String, val label: String) {
    Haiku55("claude-haiku-5-5", "Haiku 5.5"),
    Sonnet55("claude-sonnet-5-5", "Sonnet 5.5"),
    Opus55("claude-opus-5-5", "Opus 5.5");

    companion object {
        val Default = Haiku55
        fun fromId(id: String?): ClaudeModel = entries.firstOrNull { it.id == id } ?: Default
    }
}

enum class Effort(val wire: String) {
    Low("low"), Medium("medium"), High("high");

    companion object {
        val Default = Low
        fun fromWire(value: String?): Effort = entries.firstOrNull { it.wire == value } ?: Default
    }
}

sealed interface MessagesStreamEvent {
    data class Started(val model: String?) : MessagesStreamEvent
    data class TextDelta(val text: String) : MessagesStreamEvent
    data class Stop(val stopReason: String?, val outputTokens: Int?) : MessagesStreamEvent
    data class Error(val type: String?, val message: String?) : MessagesStreamEvent
}

/** The reduced view of one streamed reply (spec/expected/messages_*.json). */
data class MessagesReduction(
    val text: String = "",
    val stopReason: String? = null,
    val outputTokens: Int? = null,
    val model: String? = null,
    val errorType: String? = null,
    val errorMessage: String? = null,
) {
    fun apply(event: MessagesStreamEvent): MessagesReduction = when (event) {
        is MessagesStreamEvent.Started -> copy(model = event.model ?: model)
        is MessagesStreamEvent.TextDelta -> copy(text = text + event.text)
        is MessagesStreamEvent.Stop -> copy(stopReason = event.stopReason ?: stopReason, outputTokens = event.outputTokens ?: outputTokens)
        is MessagesStreamEvent.Error -> copy(errorType = event.type, errorMessage = event.message)
    }

    val isRefusal: Boolean get() = stopReason == "refusal"

    /** Text as the watch shows it: "…" appended on max_tokens (PROTOCOL §4). */
    val displayText: String get() = if (stopReason == "max_tokens") "$text…" else text

    companion object {
        const val REFUSAL_NOTICE = "Claude declined to answer this one."
        fun of(events: List<MessagesStreamEvent>) = events.fold(MessagesReduction()) { acc, e -> acc.apply(e) }
    }
}

/**
 * Streaming chat over the official Messages API (PROTOCOL §4). Never retries a completion
 * except the single post-refresh retry on 401 (an unauthorized request is not billed).
 */
class MessagesClient(private val transport: ApiTransport, private val baseUrl: String = ApiTransport.API_BASE) {

    fun stream(
        history: List<ChatMessage>,
        model: ClaudeModel = ClaudeModel.Default,
        effort: Effort = Effort.Default,
    ): Flow<MessagesStreamEvent> = flow {
        val mode = transport.auth.validCredentials().mode
        val body = buildRequestBody(history, model, effort, mode).toString()
        val response = transport.stream(Endpoint.Messages, "POST", "$baseUrl/v1/messages", body)
        response.body.sseEvents().collect { sse -> decode(sse)?.let { emit(it) } }
    }

    /** 1-token validation call for a freshly entered API key (PLAN §3). Throws on failure. */
    suspend fun validate() {
        val body = buildJsonObject {
            put("model", ClaudeModel.Default.id)
            put("max_tokens", 1)
            putJsonArray("messages") { add(buildJsonObject { put("role", "user"); put("content", "hi") }) }
        }.toString()
        transport.send(Endpoint.Messages, "POST", "$baseUrl/v1/messages", body)
    }

    companion object {
        const val MAX_TOKENS = 400
        const val MAX_TURNS = 20

        fun systemPrompt(mode: AuthMode): String = when (mode) {
            AuthMode.ApiKey -> WATCH_SYSTEM_PROMPT
            AuthMode.ClaudeAccount -> "$CLAUDE_CODE_IDENTITY_LINE\n$WATCH_SYSTEM_PROMPT"
        }

        /** Last [MAX_TURNS] non-empty turns, trimmed from the front, starting with a user turn, no prefill. */
        fun trimHistory(history: List<ChatMessage>): List<ChatMessage> {
            var turns = history.filter { it.text.isNotBlank() }
            while (turns.isNotEmpty() && turns.last().role == ChatRole.Assistant) turns = turns.dropLast(1)
            turns = turns.takeLast(MAX_TURNS)
            while (turns.isNotEmpty() && turns.first().role == ChatRole.Assistant) turns = turns.drop(1)
            return turns
        }

        fun buildRequestBody(history: List<ChatMessage>, model: ClaudeModel, effort: Effort, mode: AuthMode): JsonObject =
            buildJsonObject {
                put("model", model.id)
                put("max_tokens", MAX_TOKENS)
                put("stream", true)
                put("system", systemPrompt(mode))
                putJsonObject("output_config") { put("effort", effort.wire) }
                putJsonArray("messages") {
                    trimHistory(history).forEach { m ->
                        add(buildJsonObject {
                            put("role", if (m.role == ChatRole.User) "user" else "assistant")
                            put("content", m.text)
                        })
                    }
                }
            }

        /** Maps one SSE event to a stream event; ping/start/stop bookkeeping returns null. */
        fun decode(sse: SseEvent): MessagesStreamEvent? {
            val obj = runCatching { CoreJson.parseToJsonElement(sse.data) as? JsonObject }.getOrNull() ?: return null
            val type = (obj["type"] as? JsonPrimitive)?.content ?: sse.event
            return when (type) {
                "message_start" -> MessagesStreamEvent.Started(
                    CoreJson.decodeFromJsonElement(MessageStartEvent.serializer(), obj).message?.model,
                )
                "content_block_delta" -> {
                    val delta = CoreJson.decodeFromJsonElement(ContentBlockDeltaEvent.serializer(), obj).delta
                    // thinking_delta / input_json_delta are ignored on the watch.
                    if (delta?.type == "text_delta" && delta.text != null) MessagesStreamEvent.TextDelta(delta.text) else null
                }
                "message_delta" -> {
                    val e = CoreJson.decodeFromJsonElement(MessageDeltaEvent.serializer(), obj)
                    MessagesStreamEvent.Stop(e.delta?.stopReason, e.usage?.outputTokens)
                }
                "error" -> {
                    val e = CoreJson.decodeFromJsonElement(StreamErrorEvent.serializer(), obj).error
                    MessagesStreamEvent.Error(e?.type, e?.message)
                }
                else -> null // ping, content_block_start/stop, message_stop
            }
        }
    }
}
