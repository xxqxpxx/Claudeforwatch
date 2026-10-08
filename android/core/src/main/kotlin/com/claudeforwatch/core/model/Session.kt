package com.claudeforwatch.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** `GET /v1/code/sessions` (docs/PROTOCOL.md §5.1, unofficial). */
@Serializable
data class SessionListResponse(
    val data: List<SessionDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("resume_token") val resumeToken: String? = null,
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

@Serializable
data class ExternalMetadata(
    @SerialName("post_turn_summary") val postTurnSummary: String? = null,
    @SerialName("pending_action") val pendingAction: JsonObject? = null,
)
