package com.claudeforwatch.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /api/oauth/usage` (docs/PROTOCOL.md §2, unofficial). `utilization` is 0–100. */
@Serializable
data class UsageDto(
    @SerialName("five_hour") val fiveHour: UsageWindow? = null,
    @SerialName("seven_day") val sevenDay: UsageWindow? = null,
)

@Serializable
data class UsageWindow(val utilization: Double? = null, @SerialName("resets_at") val resetsAt: String? = null)

/** `GET /api/oauth/profile` (docs/PROTOCOL.md §2, unofficial). */
@Serializable
data class ProfileDto(val account: Account? = null, val organization: Organization? = null) {
    @Serializable data class Account(@SerialName("email_address") val emailAddress: String? = null, @SerialName("display_name") val displayName: String? = null)
    @Serializable data class Organization(val uuid: String? = null, val name: String? = null)
}

/** `POST /v1/claude_code/routines/{trig}/fire` response (docs/PROTOCOL.md §6, official). */
@Serializable
data class RoutineFireResult(
    @SerialName("claude_code_session_id") val sessionId: String? = null,
    @SerialName("claude_code_session_url") val sessionUrl: String? = null,
)
