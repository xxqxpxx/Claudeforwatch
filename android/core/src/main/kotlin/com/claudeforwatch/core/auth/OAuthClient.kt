package com.claudeforwatch.core.auth

import com.claudeforwatch.core.CoreJson
import com.claudeforwatch.core.net.HttpClient
import com.claudeforwatch.core.net.HttpRequest
import com.claudeforwatch.core.net.HttpResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * Claude Code's public OAuth client (unofficial; docs/PROTOCOL.md §2,
 * docs/research/anthropic-auth-and-sessions.md §2). Personal use only (PROTOCOL §1.2).
 */
object OAuthConfig {
    const val AUTHORIZE_URL = "https://claude.ai/oauth/authorize"
    const val TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
    const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    const val REDIRECT_URI = "https://platform.claude.com/oauth/code/callback"
    val SCOPES = listOf("user:profile", "user:inference", "user:sessions:claude_code")
    const val OAUTH_BETA = "oauth-2025-04-20"

    /** PKCE verifier/state live for 10 minutes (PROTOCOL §2 step 4). */
    const val PENDING_TTL_MILLIS = 10 * 60 * 1000L
}

sealed class OAuthException(message: String) : Exception(message) {
    class StateMismatch : OAuthException("The code doesn't belong to this sign-in. Start again.")
    class InvalidCode : OAuthException("That doesn't look like a sign-in code.")
    class Expired : OAuthException("Sign-in took longer than 10 minutes. Start again.")
    /** [error] is the OAuth `error` code; never contains tokens. */
    class Http(val status: Int, val error: String?) : OAuthException("Sign-in failed ($status${error?.let { ", $it" } ?: ""})")
    /** Refresh token rejected (400/401): the user must sign in again. */
    class SignedOut : OAuthException("Signed out. Sign in again.")
}

/**
 * One in-progress authorization (QR shown, waiting for the code). [redirectUri] is what the
 * authorize URL carried and what the token exchange must repeat: the watch always uses
 * [OAuthConfig.REDIRECT_URI]; the phone companion also uses `http://localhost:<port>/callback`.
 */
class PendingAuthorization(
    val pkce: Pkce,
    val authorizeUrl: String,
    val createdAtMillis: Long,
    val redirectUri: String = OAuthConfig.REDIRECT_URI,
) {
    fun isExpired(nowMillis: Long) = nowMillis - createdAtMillis > OAuthConfig.PENDING_TTL_MILLIS
    override fun toString() = "PendingAuthorization(createdAt=$createdAtMillis)"
}

@Serializable
data class TokenResponse(
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 0,
    val scope: String? = null,
    val organization: Org? = null,
    val account: Account? = null,
) {
    @Serializable data class Org(val uuid: String? = null, val name: String? = null)
    @Serializable data class Account(val uuid: String? = null, @SerialName("email_address") val emailAddress: String? = null)

    override fun toString() = "TokenResponse(<redacted>, expiresIn=$expiresIn, scope=$scope)"
}

class OAuthClient(
    private val http: HttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val userAgent: String = com.claudeforwatch.core.CoreInfo.userAgent(),
) {
    fun startAuthorization(pkce: Pkce = Pkce.generate(), redirectUri: String = OAuthConfig.REDIRECT_URI): PendingAuthorization =
        PendingAuthorization(pkce, authorizeUrl(pkce, redirectUri), clock(), redirectUri)

    /**
     * Exchanges the pasted `<code>#<state>` (or a callback URL with `code`/`state`) for
     * credentials (PROTOCOL §2 "Token exchange"), using [PendingAuthorization.redirectUri].
     */
    suspend fun exchange(pastedCode: String, pending: PendingAuthorization): Credentials {
        if (pending.isExpired(clock())) throw OAuthException.Expired()
        val code = parseCode(pastedCode, pending.pkce.state)
        val fields = linkedMapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "state" to pending.pkce.state,
            "client_id" to OAuthConfig.CLIENT_ID,
            "redirect_uri" to pending.redirectUri,
            "code_verifier" to pending.pkce.verifier,
        )
        var response = http.request(jsonPost(fields, beta = false))
        // PROTOCOL §2: one sources reports form encoding; retry once on invalid_grant only.
        if (response.status == 400 && errorCode(response) == "invalid_grant") {
            response = http.request(formPost(fields))
        }
        val token = decodeOrThrow(response, refresh = false)
        return credentialsFrom(token, previous = null)
    }

    /** Refreshes and rotates (PROTOCOL §2 "Refresh"). 400/401 ⇒ [OAuthException.SignedOut]. */
    suspend fun refresh(current: Credentials): Credentials {
        val refreshToken = current.refreshToken ?: throw OAuthException.SignedOut()
        val fields = linkedMapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to OAuthConfig.CLIENT_ID,
        )
        val response = http.request(jsonPost(fields, beta = true))
        val token = decodeOrThrow(response, refresh = true)
        return credentialsFrom(token, previous = current)
    }

    fun credentialsFrom(token: TokenResponse, previous: Credentials?): Credentials {
        val now = clock()
        return Credentials(
            mode = AuthMode.ClaudeAccount,
            accessToken = token.accessToken,
            // Rotation: always keep the newest refresh token; keep the old one only if none returned.
            refreshToken = token.refreshToken ?: previous?.refreshToken,
            expiresAt = now + token.expiresIn * 1000 - 60_000,
            scopes = token.scope?.split(' ')?.filter { it.isNotBlank() } ?: previous?.scopes ?: OAuthConfig.SCOPES,
            organizationUuid = token.organization?.uuid ?: previous?.organizationUuid,
            accountEmail = token.account?.emailAddress ?: previous?.accountEmail,
            // A chat API key stored next to the account (PROTOCOL §1.3) survives refreshes.
            apiKey = previous?.apiKey,
        )
    }

    private fun jsonPost(fields: Map<String, String>, beta: Boolean): HttpRequest {
        val body = buildJsonObject { fields.forEach { (k, v) -> put(k, v) } }.toString()
        val headers = buildMap {
            put("Content-Type", "application/json")
            put("User-Agent", userAgent)
            if (beta) put("anthropic-beta", OAuthConfig.OAUTH_BETA)
        }
        return HttpRequest.post(OAuthConfig.TOKEN_URL, headers, body)
    }

    private fun formPost(fields: Map<String, String>): HttpRequest {
        val body = fields.entries.joinToString("&") { (k, v) -> "${formEncode(k)}=${formEncode(v)}" }
        return HttpRequest.post(
            OAuthConfig.TOKEN_URL,
            mapOf("Content-Type" to "application/x-www-form-urlencoded", "User-Agent" to userAgent),
            body,
        )
    }

    private fun decodeOrThrow(response: HttpResponse, refresh: Boolean): TokenResponse {
        if (!response.isSuccessful) {
            if (refresh && (response.status == 400 || response.status == 401)) throw OAuthException.SignedOut()
            throw OAuthException.Http(response.status, errorCode(response))
        }
        return try {
            CoreJson.decodeFromString(TokenResponse.serializer(), response.bodyString)
        } catch (e: Exception) {
            throw OAuthException.Http(response.status, "unreadable_response")
        }
    }

    private fun errorCode(response: HttpResponse): String? = runCatching {
        val obj = CoreJson.parseToJsonElement(response.bodyString) as JsonObject
        val err = obj["error"]
        when (err) {
            is JsonObject -> err["type"]?.jsonPrimitive?.content
            null -> null
            else -> err.jsonPrimitive.content
        }
    }.getOrNull() ?: if (response.bodyString.contains("invalid_grant")) "invalid_grant" else null

    companion object {
        /** `http://localhost:<port>/callback`: accepted by the server, capturable only off-watch (§2). */
        fun loopbackRedirectUri(port: Int): String = "http://localhost:$port/callback"

        /**
         * Authorize URL with parameters in PROTOCOL §2 order. Values are form-encoded exactly like
         * Claude Code's `URLSearchParams` (space ⇒ `+`, `:` and `/` escaped).
         */
        fun authorizeUrl(pkce: Pkce, redirectUri: String = OAuthConfig.REDIRECT_URI): String {
            val params = listOf(
                "code" to "true",
                "client_id" to OAuthConfig.CLIENT_ID,
                "response_type" to "code",
                "redirect_uri" to redirectUri,
                "scope" to OAuthConfig.SCOPES.joinToString(" "),
                "code_challenge" to pkce.challenge,
                "code_challenge_method" to "S256",
                "state" to pkce.state,
            )
            return OAuthConfig.AUTHORIZE_URL + "?" + params.joinToString("&") { (k, v) -> "$k=${formEncode(v)}" }
        }

        /**
         * Parses what the callback page shows: `<code>#<state>` (PROTOCOL §2). The state suffix is
         * mandatory and must equal [expectedState]. Also accepts a pasted callback URL with
         * `code` and `state` query parameters. Whitespace (from phone keyboards) is ignored.
         */
        fun parseCode(input: String, expectedState: String): String {
            val trimmed = input.trim().filterNot { it.isWhitespace() }
            if (trimmed.isEmpty()) throw OAuthException.InvalidCode()
            val (code, state) = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                val query = trimmed.substringAfter('?', "").substringBefore('#')
                val params = query.split('&').filter { '=' in it }.associate {
                    it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
                }
                (params["code"] ?: throw OAuthException.InvalidCode()) to params["state"]
            } else {
                val hash = trimmed.indexOf('#')
                if (hash < 0) trimmed to null else trimmed.substring(0, hash) to trimmed.substring(hash + 1)
            }
            if (code.isEmpty()) throw OAuthException.InvalidCode()
            if (state == null || !constantTimeEquals(state, expectedState)) throw OAuthException.StateMismatch()
            return code
        }

        private fun constantTimeEquals(a: String, b: String): Boolean =
            java.security.MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

        internal fun formEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}
