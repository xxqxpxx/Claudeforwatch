package com.claudeforwatch.core.model

import com.claudeforwatch.core.CoreJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Claude Code session event payloads (docs/PROTOCOL.md §5.2–§5.5, unofficial). Payloads are
 * Claude Code "stream-json" messages. Every polymorphic family decodes by its `type`
 * (or `subtype`) key and falls back to an Unknown case so new server types never crash.
 */

/** `GET …/events` response. */
@Serializable
data class SessionEventsResponse(val data: List<SessionEventEnvelope> = emptyList())

/** One event: history items carry `sequence_num` + `payload`; stream frames add `event_type`/`source`. */
@Serializable
data class SessionEventEnvelope(
    @SerialName("sequence_num") val sequenceNum: String? = null,
    @SerialName("event_type") val eventType: String? = null,
    val source: String? = null,
    val payload: SessionPayload? = null,
) {
    /** Sequence numbers arrive as strings; compare as integers (PROTOCOL §5.2). */
    val sequence: Long? get() = sequenceNum?.trim()?.toLongOrNull()

    companion object {
        fun decode(json: String): SessionEventEnvelope = CoreJson.decodeFromString(serializer(), json)
    }
}

@Serializable(with = SessionPayloadSerializer::class)
sealed interface SessionPayload {
    val uuid: String?

    @Serializable
    data class User(override val uuid: String? = null, val message: PayloadMessage? = null) : SessionPayload

    @Serializable
    data class Assistant(override val uuid: String? = null, val message: PayloadMessage? = null) : SessionPayload

    /** Partial Anthropic stream event (`content_block_delta` with `text_delta`, …). */
    @Serializable
    data class StreamEvent(override val uuid: String? = null, val event: JsonObject? = null) : SessionPayload {
        /** The `text_delta` text when this is one, else null. */
        val textDelta: String?
            get() {
                val e = event ?: return null
                if ((e["type"] as? JsonPrimitive)?.content != "content_block_delta") return null
                val delta = e["delta"] as? JsonObject ?: return null
                if ((delta["type"] as? JsonPrimitive)?.content != "text_delta") return null
                return (delta["text"] as? JsonPrimitive)?.content
            }
        val streamEventType: String? get() = (event?.get("type") as? JsonPrimitive)?.content
    }

    @Serializable
    data class Result(
        override val uuid: String? = null,
        val subtype: String? = null,
        @SerialName("is_error") val isError: Boolean = false,
        val result: String? = null,
        @SerialName("stop_reason") val stopReason: String? = null,
    ) : SessionPayload

    @Serializable
    data class System(override val uuid: String? = null, val subtype: String? = null, val model: String? = null) : SessionPayload

    @Serializable
    data class ControlRequest(
        override val uuid: String? = null,
        @SerialName("request_id") val requestId: String? = null,
        val request: ControlRequestBody? = null,
    ) : SessionPayload

    @Serializable
    data class ControlResponse(override val uuid: String? = null, val response: JsonObject? = null) : SessionPayload {
        val requestId: String? get() = (response?.get("request_id") as? JsonPrimitive)?.content
    }

    /** tool_progress, tool_use_summary, rate_limit_event and anything new. */
    data class Unknown(val type: String, override val uuid: String? = null) : SessionPayload
}

@Serializable
data class PayloadMessage(
    val role: String? = null,
    val model: String? = null,
    @Serializable(with = ContentListSerializer::class) val content: List<ContentBlock> = emptyList(),
)

@Serializable(with = ContentBlockSerializer::class)
sealed interface ContentBlock {
    @Serializable data class Text(val text: String = "") : ContentBlock

    @Serializable
    data class ToolUse(val id: String? = null, val name: String = "", val input: JsonObject = JsonObject(emptyMap())) : ContentBlock

    @Serializable data class Thinking(val thinking: String? = null) : ContentBlock

    @Serializable
    data class ToolResult(
        @SerialName("tool_use_id") val toolUseId: String? = null,
        val content: JsonElement? = null,
        @SerialName("is_error") val isError: Boolean = false,
    ) : ContentBlock

    data class Unknown(val type: String) : ContentBlock
}

@Serializable(with = ControlRequestBodySerializer::class)
sealed interface ControlRequestBody {
    val subtype: String

    /** A permission prompt (PROTOCOL §5.5). */
    @Serializable
    data class CanUseTool(
        @SerialName("tool_name") val toolName: String = "",
        val input: JsonObject = JsonObject(emptyMap()),
        val description: String? = null,
        @SerialName("permission_suggestions") val permissionSuggestions: JsonElement? = null,
    ) : ControlRequestBody {
        override val subtype: String get() = "can_use_tool"
    }

    data class Unknown(override val subtype: String) : ControlRequestBody
}

private fun JsonElement.typeKey(key: String = "type"): String =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.content ?: ""

object SessionPayloadSerializer : JsonContentPolymorphicSerializer<SessionPayload>(SessionPayload::class) {
    override fun selectDeserializer(element: JsonElement) = when (element.typeKey()) {
        "user" -> SessionPayload.User.serializer()
        "assistant" -> SessionPayload.Assistant.serializer()
        "stream_event" -> SessionPayload.StreamEvent.serializer()
        "result" -> SessionPayload.Result.serializer()
        "system" -> SessionPayload.System.serializer()
        "control_request" -> SessionPayload.ControlRequest.serializer()
        "control_response" -> SessionPayload.ControlResponse.serializer()
        else -> UnknownPayloadSerializer
    }
}

private object UnknownPayloadSerializer : KSerializer<SessionPayload.Unknown> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor
    override fun deserialize(decoder: Decoder): SessionPayload.Unknown {
        val el = (decoder as JsonDecoder).decodeJsonElement()
        return SessionPayload.Unknown(el.typeKey(), ((el as? JsonObject)?.get("uuid") as? JsonPrimitive)?.content)
    }
    override fun serialize(encoder: Encoder, value: SessionPayload.Unknown) =
        (encoder as JsonEncoder).encodeJsonElement(buildJsonObject { put("type", value.type) })
}

object ContentBlockSerializer : JsonContentPolymorphicSerializer<ContentBlock>(ContentBlock::class) {
    override fun selectDeserializer(element: JsonElement) = when (element.typeKey()) {
        "text" -> ContentBlock.Text.serializer()
        "tool_use" -> ContentBlock.ToolUse.serializer()
        "thinking", "redacted_thinking" -> ContentBlock.Thinking.serializer()
        "tool_result" -> ContentBlock.ToolResult.serializer()
        else -> UnknownBlockSerializer
    }
}

private object UnknownBlockSerializer : KSerializer<ContentBlock.Unknown> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor
    override fun deserialize(decoder: Decoder) = ContentBlock.Unknown((decoder as JsonDecoder).decodeJsonElement().typeKey())
    override fun serialize(encoder: Encoder, value: ContentBlock.Unknown) =
        (encoder as JsonEncoder).encodeJsonElement(buildJsonObject { put("type", value.type) })
}

/** `content` is either a plain string or an array of blocks. */
object ContentListSerializer : KSerializer<List<ContentBlock>> {
    private val listSerializer = kotlinx.serialization.builtins.ListSerializer(ContentBlockSerializer)
    override val descriptor: SerialDescriptor = listSerializer.descriptor
    override fun deserialize(decoder: Decoder): List<ContentBlock> {
        val jd = decoder as JsonDecoder
        return when (val el = jd.decodeJsonElement()) {
            is JsonPrimitive -> if (el.isString) listOf(ContentBlock.Text(el.content)) else emptyList()
            is JsonArray -> jd.json.decodeFromJsonElement(listSerializer, el)
            else -> emptyList()
        }
    }
    override fun serialize(encoder: Encoder, value: List<ContentBlock>) = listSerializer.serialize(encoder, value)
}

object ControlRequestBodySerializer : JsonContentPolymorphicSerializer<ControlRequestBody>(ControlRequestBody::class) {
    override fun selectDeserializer(element: JsonElement) = when (element.typeKey("subtype")) {
        "can_use_tool" -> ControlRequestBody.CanUseTool.serializer()
        else -> UnknownControlSerializer
    }
}

private object UnknownControlSerializer : KSerializer<ControlRequestBody.Unknown> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor
    override fun deserialize(decoder: Decoder) =
        ControlRequestBody.Unknown((decoder as JsonDecoder).decodeJsonElement().typeKey("subtype"))
    override fun serialize(encoder: Encoder, value: ControlRequestBody.Unknown) =
        (encoder as JsonEncoder).encodeJsonElement(buildJsonObject { put("subtype", value.subtype) })
}

