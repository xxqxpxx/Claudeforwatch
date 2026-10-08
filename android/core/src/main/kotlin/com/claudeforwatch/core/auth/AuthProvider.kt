package com.claudeforwatch.core.auth

import com.claudeforwatch.core.CoreInfo
import com.claudeforwatch.core.api.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Which header set a request needs (docs/PROTOCOL.md §3–§5). */
enum class Endpoint {
    /** `POST /v1/messages` (§4). */
    Messages,

    /** Bare `GET /v1/code/sessions` (§5.1): no `ccr-byoc` beta. */
    SessionsList,

    /** Every other `/v1/code/sessions/…` call (§5): `anthropic-beta: ccr-byoc-2025-07-29`. */
    Session,

    /** `/api/oauth/usage` and `/api/oauth/profile` (§2, optional). */
    OAuthAccount,
}

object AuthHeaders {
    const val ANTHROPIC_VERSION = "2023-06-01"
    const val BETA_OAUTH = "oauth-2025-04-20"
    const val BETA_CLAUDE_CODE = "claude-code-20250219"
    const val BETA_CCR = "ccr-byoc-2025-07-29"
    const val CLIENT_PLATFORM = "web_claude_ai"

    /**
     * Exact header set per mode and endpoint. Throws [ApiException.NotAvailableInMode] for
     * endpoints an API key cannot reach (sessions, usage).
     */
    fun build(endpoint: Endpoint, credentials: Credentials, userAgent: String): Map<String, String> {
        val h = linkedMapOf(
            "anthropic-version" to ANTHROPIC_VERSION,
            "Content-Type" to "application/json",
            "User-Agent" to userAgent,
        )
        when (credentials.mode) {
            AuthMode.ApiKey -> {
                if (endpoint != Endpoint.Messages) throw ApiException.NotAvailableInMode()
                h["x-api-key"] = credentials.apiKey ?: throw ApiException.NotSignedIn()
            }
            AuthMode.ClaudeAccount -> {
                h["Authorization"] = "Bearer " + (credentials.accessToken ?: throw ApiException.NotSignedIn())
                when (endpoint) {
                    // §4: subscription inference needs the Claude Code identity beta (unofficial).
                    Endpoint.Messages -> h["anthropic-beta"] = "$BETA_OAUTH,$BETA_CLAUDE_CODE"
                    // §5: private sessions API (unofficial). The bare list rejects the ccr beta.
                    Endpoint.SessionsList, Endpoint.Session -> {
                        h["anthropic-client-platform"] = CLIENT_PLATFORM
                        credentials.organizationUuid?.let { h["x-organization-uuid"] = it }
                        if (endpoint == Endpoint.Session) h["anthropic-beta"] = BETA_CCR
                    }
                    // §2 usage/profile: api.anthropic.com only accepts OAuth bearer tokens with the
                    // oauth beta (Claude Code sends it here too). Addition to PROTOCOL §3.
                    Endpoint.OAuthAccount -> h["anthropic-beta"] = BETA_OAUTH
                }
            }
        }
        return h
    }
}

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val mode: AuthMode, val accountEmail: String?, val canUseSessions: Boolean) : AuthState
}

/**
 * Owns the current credentials: loads them from the [TokenStore], refreshes OAuth tokens
 * single-flight (one refresh shared by concurrent callers), rotates refresh tokens, and builds
 * per-endpoint headers. A failed refresh (400/401) signs the user out (PROTOCOL §2).
 */
class AuthProvider(
    private val store: TokenStore,
    private val oauth: OAuthClient?,
    private val clock: () -> Long = System::currentTimeMillis,
    val userAgent: String = CoreInfo.userAgent(),
) {
    private val refreshLock = Mutex()

    @Volatile
    private var cached: Credentials? = null

    @Volatile
    private var loaded = false

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /** Number of refresh calls actually sent (for tests and diagnostics). */
    @Volatile
    var refreshCount: Int = 0
        private set

    suspend fun load(): Credentials? {
        if (!loaded) {
            cached = store.load()
            loaded = true
            publish()
        }
        return cached
    }

    /** Current credentials, refreshed first if the OAuth token is past `expiresAt`. */
    suspend fun validCredentials(): Credentials {
        val current = load() ?: throw ApiException.NotSignedIn()
        if (!current.isExpired(clock())) return current
        return refresh(staleAccessToken = current.accessToken)
    }

    suspend fun headersFor(endpoint: Endpoint): Map<String, String> =
        AuthHeaders.build(endpoint, validCredentials(), userAgent)

    /**
     * Refreshes unless another caller already did. Pass the access token that failed (401) or
     * expired; if the stored token differs and is still valid, it is returned without a call.
     */
    suspend fun refresh(staleAccessToken: String?): Credentials = refreshLock.withLock {
        val current = load() ?: throw ApiException.NotSignedIn()
        if (current.mode != AuthMode.ClaudeAccount) return@withLock current
        if (current.accessToken != staleAccessToken && !current.isExpired(clock())) return@withLock current
        val client = oauth ?: throw ApiException.SignedOut()
        try {
            refreshCount++
            val fresh = client.refresh(current)
            store.save(fresh) // persist the rotated refresh token before anything else
            cached = fresh
            publish()
            fresh
        } catch (e: OAuthException.SignedOut) {
            signOutLocked()
            throw ApiException.SignedOut()
        } catch (e: OAuthException) {
            throw ApiException.Http(e.let { (it as? OAuthException.Http)?.status ?: 0 }, null, e.message)
        }
    }

    suspend fun signIn(credentials: Credentials) {
        refreshLock.withLock {
            store.save(credentials)
            cached = credentials
            loaded = true
            publish()
        }
    }

    suspend fun signOut() = refreshLock.withLock { signOutLocked() }

    private suspend fun signOutLocked() {
        store.clear()
        cached = null
        loaded = true
        publish()
    }

    private fun publish() {
        val c = cached
        _state.value = if (c == null) AuthState.SignedOut
        else AuthState.SignedIn(c.mode, c.accountEmail, c.hasSessionsScope)
    }
}
