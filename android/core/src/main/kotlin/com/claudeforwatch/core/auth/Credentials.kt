package com.claudeforwatch.core.auth

import com.claudeforwatch.core.CoreJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** docs/PROTOCOL.md §1. */
@Serializable
enum class AuthMode {
    @SerialName("apiKey") ApiKey,
    @SerialName("claudeAccount") ClaudeAccount,
}

/**
 * The stored credential record (docs/PROTOCOL.md §1.1). Serialized as JSON and encrypted at rest
 * by the platform [TokenStore]. [toString] never prints secrets.
 */
@Serializable
data class Credentials(
    val mode: AuthMode,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    /** Epoch millis; already reduced by 60 s of safety margin at exchange time. */
    val expiresAt: Long? = null,
    val scopes: List<String> = emptyList(),
    val organizationUuid: String? = null,
    val accountEmail: String? = null,
    val apiKey: String? = null,
) {
    fun isExpired(nowMillis: Long): Boolean = mode == AuthMode.ClaudeAccount && expiresAt != null && nowMillis >= expiresAt

    val hasSessionsScope: Boolean get() = mode == AuthMode.ClaudeAccount && SCOPE_SESSIONS in scopes

    /**
     * The credential chat (the Messages API) runs on. A Claude-account record may also carry an
     * API key (PROTOCOL §1.3): chat then uses the key, which is the supported path and is billed
     * to the key's Console org, while sessions keep using the OAuth token.
     */
    val chatMode: AuthMode get() = if (!apiKey.isNullOrBlank()) AuthMode.ApiKey else mode

    val hasChatApiKey: Boolean get() = mode == AuthMode.ClaudeAccount && !apiKey.isNullOrBlank()

    /** PROTOCOL §1.1 record: every key present, nulls explicit (`"apiKey":null`). */
    fun encode(): String = RecordJson.encodeToString(serializer(), this)

    override fun toString(): String =
        "Credentials(mode=$mode, accessToken=${redact(accessToken)}, refreshToken=${redact(refreshToken)}, " +
            "expiresAt=$expiresAt, scopes=$scopes, organizationUuid=$organizationUuid, " +
            "accountEmail=${if (accountEmail == null) null else "<set>"}, apiKey=${redact(apiKey)})"

    companion object {
        const val SCOPE_SESSIONS = "user:sessions:claude_code"

        fun forApiKey(key: String) = Credentials(mode = AuthMode.ApiKey, apiKey = key.trim())

        fun decode(json: String): Credentials = CoreJson.decodeFromString(serializer(), json)

        private val RecordJson = kotlinx.serialization.json.Json(CoreJson) { explicitNulls = true }

        private fun redact(secret: String?) = if (secret == null) "null" else "<redacted>"
    }
}
