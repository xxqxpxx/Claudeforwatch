package com.claudeforwatch.core.api

import com.claudeforwatch.core.CoreJson
import com.claudeforwatch.core.auth.AuthMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Errors surfaced to the UI. Messages are user-facing copy for a watch screen and never
 * contain secrets or response bodies (docs/PROTOCOL.md §4 "Errors", §5 Trusted Devices).
 */
sealed class ApiException(message: String, val status: Int? = null) : Exception(message) {
    class NotSignedIn : ApiException("Sign in first.")
    class NotAvailableInMode : ApiException("Sign in with your Claude account to use this.")
    class InvalidApiKey : ApiException("Check your API key.", 401)
    class SignedOut : ApiException("Your Claude sign-in expired. Sign in again.", 401)
    class RateLimited(val retryAfterSeconds: Long?) :
        ApiException(retryAfterSeconds?.let { "Rate limited. Try again in ${it}s." } ?: "Rate limited. Try again soon.", 429)
    class Overloaded : ApiException("Claude is overloaded. Try again in a moment.", 529)

    /** PROTOCOL §4: 400 "only authorized for use with Claude Code" ⇒ explain §1.2. */
    class ClaudeCodeOnly : ApiException(
        "Anthropic only accepts this sign-in from Claude Code. Use an API key for chat instead.", 400,
    )

    /** PROTOCOL §5: 403 mentioning trusted device. */
    class TrustedDeviceRequired : ApiException(
        "This organization requires Trusted Devices; enroll from claude.ai", 403,
    )

    class Http(status: Int, val errorType: String?, apiMessage: String?) :
        ApiException(apiMessage?.take(160) ?: "Request failed ($status).", status)

    class Network(cause: Throwable) : ApiException("No connection. Check the watch's network.") {
        init { initCause(cause) }
    }
}

object ApiErrors {
    /** Maps a non-2xx response to an [ApiException] (body is parsed, never logged). */
    fun from(status: Int, retryAfter: String?, body: String, mode: AuthMode?): ApiException {
        val (type, message) = parseError(body)
        val lower = body.lowercase()
        return when {
            status == 401 && mode == AuthMode.ApiKey -> ApiException.InvalidApiKey()
            status == 401 -> ApiException.SignedOut()
            status == 403 && (lower.contains("trusted device") || lower.contains("trusted_device")) ->
                ApiException.TrustedDeviceRequired()
            status == 400 && lower.contains("only authorized for use with claude code") -> ApiException.ClaudeCodeOnly()
            status == 429 -> ApiException.RateLimited(retryAfter?.trim()?.toLongOrNull())
            status == 529 || type == "overloaded_error" -> ApiException.Overloaded()
            else -> ApiException.Http(status, type, message)
        }
    }

    /** Anthropic error envelope: `{"type":"error","error":{"type":…,"message":…}}`. */
    fun parseError(body: String): Pair<String?, String?> = runCatching {
        val root = CoreJson.parseToJsonElement(body).jsonObject
        val err = root["error"]
        if (err is JsonObject) {
            err["type"]?.jsonPrimitive?.content to err["message"]?.jsonPrimitive?.content
        } else {
            err?.jsonPrimitive?.content to (root["error_description"] ?: root["message"])?.jsonPrimitive?.content
        }
    }.getOrDefault(null to null)
}
