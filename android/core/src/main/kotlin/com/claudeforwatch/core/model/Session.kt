package com.claudeforwatch.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `GET /v1/code/sessions` (docs/PROTOCOL.md §5.1, unofficial). */
@Serializable
data class SessionListResponse(
    val data: List<SessionDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("resume_token") val resumeToken: String? = null,
    /** Sessions dropped because they could not be decoded (not part of the wire format). */
    @kotlinx.serialization.Transient val skipped: Int = 0,
)

@Serializable
data class SessionDto(
    val id: String,
    val title: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_event_at") val lastEventAt: String? = null,
    /** "active" | "archived" */
    val status: String? = null,
    @SerialName("status_bucket") val statusBucket: String? = null,
    /** "idle" | "running" | "requires_action" */
    @SerialName("worker_status") val workerStatus: String? = null,
    @SerialName("connection_status") val connectionStatus: String? = null,
    /** "bridge" = Remote Control on the user's machine; anything else = cloud. */
    @SerialName("environment_kind") val environmentKind: String? = null,
    val unread: Boolean = false,
    val config: SessionConfig? = null,
    @SerialName("external_metadata") val externalMetadata: ExternalMetadata? = null,
) {
    val isArchived: Boolean get() = status == "archived"
    val needsAction: Boolean get() = workerStatus == WORKER_REQUIRES_ACTION
    val isRunning: Boolean get() = workerStatus == WORKER_RUNNING

    companion object {
        const val WORKER_REQUIRES_ACTION = "requires_action"
        const val WORKER_RUNNING = "running"
    }
}

@Serializable
data class SessionConfig(val model: String? = null)

/**
 * Free-form metadata the server attaches to a session. Every field is kept as raw JSON because
 * the shapes are unofficial and have changed: `post_turn_summary` arrived as a string in early
 * captures and as an object (`{"needs_action": …, …}`) on a live account in Oct 2026.
 */
@Serializable
data class ExternalMetadata(
    @SerialName("post_turn_summary") val postTurnSummary: JsonElement? = null,
    @SerialName("pending_action") val pendingAction: JsonElement? = null,
) {
    /** One display line for the session row, whatever shape the summary has. */
    val summaryText: String? get() = SummaryText.of(postTurnSummary)

    /** What the session is waiting for, when the summary says so. */
    val needsActionText: String? get() =
        ((postTurnSummary as? JsonObject)?.get("needs_action") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}

/** Flattens an unknown summary value into display text (docs/PROTOCOL.md §5.1). */
object SummaryText {
    private val preferredKeys = listOf("needs_action", "summary", "text", "title", "status", "description")

    fun of(element: JsonElement?): String? = when (element) {
        null, JsonNull -> null
        is JsonPrimitive -> element.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        is JsonObject -> preferredKeys.firstNotNullOfOrNull { of(element[it]) }
            ?: element.values.firstNotNullOfOrNull { of(it) }
        is JsonArray -> element.firstNotNullOfOrNull { of(it) }
    }
}
