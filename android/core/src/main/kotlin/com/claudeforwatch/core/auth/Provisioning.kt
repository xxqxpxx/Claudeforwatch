package com.claudeforwatch.core.auth

import java.util.Base64

/**
 * Pure parsing/validation for credentials delivered from a computer over ADB (the Wear OS
 * `ProvisionReceiver`, or `scripts/watch-login.py`). No Android imports so it is JVM-tested.
 * Error messages are user-facing and never contain secret values.
 */
object Provisioning {
    /** Used when a pushed record has no `expiresAt`: Claude Code access tokens live ~8 h. */
    const val DEFAULT_TOKEN_LIFETIME_MILLIS = 8 * 60 * 60 * 1000L
    const val EXPIRY_MARGIN_MILLIS = 60_000L
    const val API_KEY_PREFIX = "sk-ant-"

    sealed interface Request {
        class ApiKey(val key: String) : Request { override fun toString() = "ApiKey(<redacted>)" }
        class OAuthCode(val code: String) : Request { override fun toString() = "OAuthCode(<redacted>)" }
        class Record(val base64: String) : Request { override fun toString() = "Record(<redacted>)" }
    }

    /** Exactly one of the three extras must be non-blank. */
    fun request(apiKey: String?, oauthCode: String?, credentialsB64: String?): Request {
        val present = listOfNotNull(
            apiKey?.takeIf { it.isNotBlank() }?.let { Request.ApiKey(it) },
            oauthCode?.takeIf { it.isNotBlank() }?.let { Request.OAuthCode(it) },
            credentialsB64?.takeIf { it.isNotBlank() }?.let { Request.Record(it) },
        )
        require(present.size == 1) {
            if (present.isEmpty()) "pass one of --es api_key, --es oauth_code, --es credentials_b64"
            else "pass only one of api_key, oauth_code, credentials_b64"
        }
        return present.single()
    }

    /** Shape check only (`sk-ant-…`, no whitespace); the key is not sent anywhere here. */
    fun apiKey(raw: String): String {
        val key = raw.trim()
        require(key.startsWith(API_KEY_PREFIX)) { "api_key must start with $API_KEY_PREFIX" }
        require(key.none { it.isWhitespace() } && key.length > API_KEY_PREFIX.length + 8) { "api_key looks truncated or has spaces" }
        return key
    }

    /**
     * What the platform.claude.com callback page shows is `<code>#<state>`; a bare `<code>` is
     * also accepted and paired with the pending [expectedState] (the person pasting it over ADB
     * is the one who started the sign-in, so the CSRF check the suffix provides is moot).
     */
    fun codeWithState(raw: String, expectedState: String): String {
        val trimmed = raw.trim().filterNot { it.isWhitespace() }
        require(trimmed.isNotEmpty()) { "oauth_code is empty" }
        val isUrl = trimmed.startsWith("http://") || trimmed.startsWith("https://")
        return if (isUrl || '#' in trimmed) trimmed else "$trimmed#$expectedState"
    }

    /** Standard or URL-safe base64, padding optional, whitespace ignored. */
    fun decodeBase64(raw: String): String {
        var s = raw.filterNot { it.isWhitespace() }.replace('-', '+').replace('_', '/')
        s = s.trimEnd('=')
        s += "=".repeat((4 - s.length % 4) % 4)
        val bytes = try {
            Base64.getDecoder().decode(s)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("credentials_b64 is not valid base64")
        }
        return bytes.toString(Charsets.UTF_8)
    }

    /**
     * Decodes and validates a PROTOCOL §1.1 record. Fills `expiresAt` (now + 8 h − 60 s) and the
     * default scopes when missing. Does not refresh or call the network: the caller refreshes
     * an expired token and fills `organizationUuid` from `/api/oauth/profile`.
     */
    fun record(base64: String, nowMillis: Long): Credentials {
        val json = decodeBase64(base64)
        val parsed = try {
            Credentials.decode(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("credentials_b64 is not a PROTOCOL §1.1 credentials record")
        }
        return when (parsed.mode) {
            AuthMode.ApiKey -> Credentials.forApiKey(apiKey(parsed.apiKey ?: ""))
            AuthMode.ClaudeAccount -> {
                val access = parsed.accessToken?.trim()
                require(!access.isNullOrEmpty()) { "credentials_b64: accessToken is missing" }
                require(access.none { it.isWhitespace() }) { "credentials_b64: accessToken has spaces" }
                parsed.copy(
                    accessToken = access,
                    refreshToken = parsed.refreshToken?.trim()?.takeIf { it.isNotEmpty() },
                    expiresAt = parsed.expiresAt ?: (nowMillis + DEFAULT_TOKEN_LIFETIME_MILLIS - EXPIRY_MARGIN_MILLIS),
                    scopes = parsed.scopes.ifEmpty { OAuthConfig.SCOPES },
                    organizationUuid = parsed.organizationUuid?.takeIf { it.isNotBlank() },
                    accountEmail = parsed.accountEmail?.takeIf { it.isNotBlank() },
                    apiKey = null,
                )
            }
        }
    }
}
