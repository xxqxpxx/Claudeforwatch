package com.claudeforwatch.core.api

import com.claudeforwatch.core.CoreJson
import com.claudeforwatch.core.auth.Endpoint
import com.claudeforwatch.core.model.SessionDto
import com.claudeforwatch.core.model.SessionEventEnvelope
import com.claudeforwatch.core.model.SessionEventsResponse
import com.claudeforwatch.core.model.SessionListResponse
import com.claudeforwatch.core.net.SseEvent
import com.claudeforwatch.core.net.sseEvents
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/** Permission modes accepted by `set_permission_mode` (Claude Code). */
enum class PermissionMode(val wire: String, val label: String) {
    Default("default", "Ask every time"),
    AcceptEdits("acceptEdits", "Accept edits"),
    Plan("plan", "Plan only"),
    BypassPermissions("bypassPermissions", "Bypass (danger)"),
}

/**
 * The private Claude Code sessions API used by claude.ai/code and the Claude apps
 * (docs/PROTOCOL.md §5, unofficial; may change). claudeAccount mode only.
 */
class SessionsClient(
    private val transport: ApiTransport,
    private val baseUrl: String = ApiTransport.API_BASE,
    private val newUuid: () -> String = { UUID.randomUUID().toString() },
) {
    private fun sessionUrl(id: String) = "$baseUrl/v1/code/sessions/${encode(id)}"

    /** §5.1: the bare list must NOT carry the ccr-byoc beta header. */
    suspend fun list(): SessionListResponse {
        val r = transport.send(Endpoint.SessionsList, "GET", "$baseUrl/v1/code/sessions")
        return decodeList(r.bodyString)
    }

    suspend fun get(id: String): SessionDto {
        val r = transport.send(Endpoint.Session, "GET", sessionUrl(id))
        return CoreJson.decodeFromString(SessionDto.serializer(), r.bodyString)
    }

    /** §5.2: newest [limit] events (server returns desc); returned as delivered (reducer sorts). */
    suspend fun history(id: String, limit: Int = 60): List<SessionEventEnvelope> {
        val r = transport.send(Endpoint.Session, "GET", "${sessionUrl(id)}/events?limit=$limit&sort_order=desc")
        return CoreJson.decodeFromString(SessionEventsResponse.serializer(), r.bodyString).data
    }

    /**
     * §5.2 live stream from [fromSequence] with `Last-Event-ID`. Emits raw SSE frames; feed
     * them to [com.claudeforwatch.core.reduce.TranscriptState.apply]. Cancel to disconnect.
     */
    fun stream(id: String, fromSequence: Long?): Flow<SseEvent> = flow {
        val query = fromSequence?.let { "?from_sequence_num=$it" } ?: ""
        val headers = fromSequence?.let { mapOf("Last-Event-ID" to it.toString()) } ?: emptyMap()
        val stream = transport.stream(Endpoint.Session, "GET", "${sessionUrl(id)}/events/stream$query", null, headers)
        stream.body.sseEvents().collect { emit(it) }
    }

    /** §5.4: a user turn (slash commands are plain text). Returns the event uuid. */
    suspend fun sendText(id: String, text: String): String {
        val uuid = newUuid()
        postEvents(id, userTextPayload(uuid, text))
        return uuid
    }

    suspend fun interrupt(id: String) = postEvents(id, controlRequest { put("subtype", "interrupt") })

    suspend fun setPermissionMode(id: String, mode: PermissionMode) =
        postEvents(id, controlRequest { put("subtype", "set_permission_mode"); put("mode", mode.wire) })

    suspend fun setModel(id: String, model: String) =
        postEvents(id, controlRequest { put("subtype", "set_model"); put("model", model) })

    /** §5.5: answer a `can_use_tool` prompt. Allow echoes the input unchanged. */
    suspend fun respondToPermission(id: String, requestId: String, allow: Boolean, input: JsonObject) =
        postEvents(id, permissionResponsePayload(requestId, allow, input))

    suspend fun markRead(id: String) {
        transport.send(Endpoint.Session, "POST", "${sessionUrl(id)}/mark_read", "{}")
    }

    /** §5.6: 200 or 409 (already archived) are both success. */
    suspend fun archive(id: String) {
        transport.send(Endpoint.Session, "POST", "${sessionUrl(id)}/archive", "{}", acceptStatus = { it in 200..299 || it == 409 })
    }

    suspend fun presence(id: String, clientId: String, connectedAtMillis: Long) {
        val body = buildJsonObject { put("client_id", clientId); put("connected_at", connectedAtMillis) }.toString()
        transport.send(Endpoint.Session, "POST", "${sessionUrl(id)}/client/presence", body)
    }

    suspend fun clearPresence(id: String, clientId: String) {
        val body = buildJsonObject { put("client_id", clientId); put("clear", true) }.toString()
        transport.send(Endpoint.Session, "POST", "${sessionUrl(id)}/client/presence", body)
    }

    private suspend fun postEvents(id: String, payload: JsonObject) {
        transport.send(Endpoint.Session, "POST", "${sessionUrl(id)}/events", eventsBody(id, payload).toString())
    }

    private fun controlRequest(request: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("type", "control_request")
            put("request_id", newUuid())
            putJsonObject("request", request)
        }

    companion object {
        /**
         * Decodes the list one session at a time so a single session with an unexpected shape
         * is skipped instead of failing the whole screen (the API is unofficial and drifts).
         */
        fun decodeList(body: String): SessionListResponse {
            val root = CoreJson.parseToJsonElement(body) as? JsonObject
                ?: throw IllegalStateException("The sessions list had an unexpected format.")
            val items = (root["data"] as? kotlinx.serialization.json.JsonArray).orEmpty()
            val sessions = items.mapNotNull { item ->
                runCatching { CoreJson.decodeFromJsonElement(SessionDto.serializer(), item) }.getOrNull()
            }
            fun str(key: String) =
                (root[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            return SessionListResponse(
                data = sessions,
                nextCursor = str("next_cursor"),
                resumeToken = str("resume_token"),
                skipped = items.size - sessions.size,
            )
        }

        fun eventsBody(sessionId: String, payload: JsonElement): JsonObject = buildJsonObject {
            put("session_id", sessionId)
            putJsonArray("events") { add(buildJsonObject { put("payload", payload) }) }
        }

        fun userTextPayload(uuid: String, text: String): JsonObject = buildJsonObject {
            put("type", "user")
            put("uuid", uuid)
            putJsonObject("message") {
                put("role", "user")
                putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", text) }) }
            }
        }

        fun permissionResponsePayload(requestId: String, allow: Boolean, input: JsonObject): JsonObject = buildJsonObject {
            put("type", "control_response")
            putJsonObject("response") {
                put("subtype", "success")
                put("request_id", requestId)
                putJsonObject("response") {
                    if (allow) {
                        put("behavior", "allow")
                        put("updatedInput", input)
                    } else {
                        put("behavior", "deny")
                        put("message", DENY_MESSAGE)
                    }
                }
            }
        }

        const val DENY_MESSAGE = "User denied from watch"

        val QUICK_REPLIES = listOf(
            "Continue", "Looks good, proceed", "Run the tests", "Stop", "Explain what you're doing", "Commit and push",
        )

        private fun encode(id: String) = java.net.URLEncoder.encode(id, "UTF-8")
    }
}
