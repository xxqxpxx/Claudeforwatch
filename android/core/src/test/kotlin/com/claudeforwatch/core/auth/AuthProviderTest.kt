package com.claudeforwatch.core.auth

import com.claudeforwatch.core.Spec
import com.claudeforwatch.core.api.ApiException
import com.claudeforwatch.core.api.ApiTransport
import com.claudeforwatch.core.net.HttpResponse
import com.claudeforwatch.core.net.StubHttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class AuthProviderTest {
    private val ua = "ClaudeForWatch/0.1.0 (Wear OS)"
    private val now = 1_760_000_000_000L
    private val account = Credentials(
        AuthMode.ClaudeAccount, "sk-ant-oat01-A", "sk-ant-ort01-R", now + 3_600_000,
        OAuthConfig.SCOPES, "org-1", "you@example.com",
    )
    private val key = Credentials.forApiKey("sk-ant-api03-K")
    private val common = mapOf("anthropic-version" to "2023-06-01", "Content-Type" to "application/json", "User-Agent" to ua)

    @Test
    fun apiKeyHeaderMatrix() {
        assertEquals(common + ("x-api-key" to "sk-ant-api03-K"), AuthHeaders.build(Endpoint.Messages, key, ua))
        for (e in listOf(Endpoint.SessionsList, Endpoint.Session, Endpoint.OAuthAccount)) {
            assertFailsWith<ApiException.NotAvailableInMode> { AuthHeaders.build(e, key, ua) }
        }
    }

    @Test
    fun claudeAccountHeaderMatrix() {
        val bearer = common + ("Authorization" to "Bearer sk-ant-oat01-A")
        assertEquals(
            bearer + ("anthropic-beta" to "oauth-2025-04-20,claude-code-20250219"),
            AuthHeaders.build(Endpoint.Messages, account, ua),
        )
        val list = AuthHeaders.build(Endpoint.SessionsList, account, ua)
        assertEquals(bearer + mapOf("anthropic-client-platform" to "web_claude_ai", "x-organization-uuid" to "org-1"), list)
        assertNull(list["anthropic-beta"], "ccr-byoc must NOT be on the bare list")
        assertEquals(
            bearer + mapOf(
                "anthropic-client-platform" to "web_claude_ai",
                "x-organization-uuid" to "org-1",
                "anthropic-beta" to "ccr-byoc-2025-07-29",
            ),
            AuthHeaders.build(Endpoint.Session, account, ua),
        )
        assertEquals(bearer + ("anthropic-beta" to "oauth-2025-04-20"), AuthHeaders.build(Endpoint.OAuthAccount, account, ua))
        assertNull(AuthHeaders.build(Endpoint.Messages, account, ua)["x-api-key"])
    }

    @Test
    fun concurrentExpiredCallersShareOneRefresh() = runTest {
        val http = StubHttpClient().on({ it.url == OAuthConfig.TOKEN_URL }) {
            delay(100) // keep the refresh in flight while the others queue up
            HttpResponse(200, body = Spec.fixtureBytes("oauth_refresh_response.json"))
        }
        val store = InMemoryTokenStore(account.copy(expiresAt = now - 1))
        val provider = AuthProvider(store, OAuthClient(http, clock = { now }), clock = { now }, userAgent = ua)

        val results = (1..8).map { async { provider.validCredentials() } }.awaitAll()

        assertEquals(1, http.requests.count { it.url == OAuthConfig.TOKEN_URL })
        assertEquals(1, provider.refreshCount)
        results.forEach { assertEquals("sk-ant-oat01-fixture-access-2", it.accessToken) }
        val stored = store.load()!!
        assertEquals("sk-ant-ort01-fixture-refresh-2", stored.refreshToken, "rotated refresh token persisted")
        assertEquals("org-1", stored.organizationUuid)
    }

    @Test
    fun unauthorizedTriggersOneRefreshThenRetries() = runTest {
        var calls = 0
        val http = StubHttpClient()
            .on({ it.url == OAuthConfig.TOKEN_URL }) { HttpResponse(200, body = Spec.fixtureBytes("oauth_refresh_response.json")) }
            .on({ it.url.endsWith("/v1/code/sessions") }) {
                calls++
                if (it.header("Authorization") == "Bearer sk-ant-oat01-A") HttpResponse(401, body = "{}".toByteArray())
                else HttpResponse(200, body = """{"data":[]}""".toByteArray())
            }
        val provider = AuthProvider(InMemoryTokenStore(account), OAuthClient(http, clock = { now }), clock = { now }, userAgent = ua)
        val transport = ApiTransport(http, provider)
        val response = transport.send(Endpoint.SessionsList, "GET", "https://api.anthropic.com/v1/code/sessions")
        assertEquals(200, response.status)
        assertEquals(2, calls)
        assertEquals("Bearer sk-ant-oat01-fixture-access-2", http.requests.last().header("Authorization"))
    }

    @Test
    fun failedRefreshSignsOut() = runTest {
        val http = StubHttpClient().onUrl(OAuthConfig.TOKEN_URL, status = 400, body = """{"error":"invalid_grant"}""")
        val store = InMemoryTokenStore(account.copy(expiresAt = now - 1))
        val provider = AuthProvider(store, OAuthClient(http, clock = { now }), clock = { now }, userAgent = ua)
        assertFailsWith<ApiException.SignedOut> { provider.validCredentials() }
        assertNull(store.load())
        assertIs<AuthState.SignedOut>(provider.state.value)
    }

    @Test
    fun apiKeyNeverRefreshes() = runTest {
        val http = StubHttpClient().onUrl("https://api.anthropic.com/v1/messages", status = 401, body = "{}")
        val provider = AuthProvider(InMemoryTokenStore(key), OAuthClient(http), userAgent = ua)
        assertFailsWith<ApiException.InvalidApiKey> {
            ApiTransport(http, provider).send(Endpoint.Messages, "POST", "https://api.anthropic.com/v1/messages", "{}")
        }
        assertEquals(1, http.requests.size)
        assertEquals(AuthState.SignedIn(AuthMode.ApiKey, null, false), provider.state.value)
    }

    @Test
    fun credentialsRoundTripMatchesProtocolRecord() {
        val json = account.encode()
        assertEquals(account, Credentials.decode(json))
        val keys = (com.claudeforwatch.core.CoreJson.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject).keys
        assertEquals(
            setOf("mode", "accessToken", "refreshToken", "expiresAt", "scopes", "organizationUuid", "accountEmail", "apiKey"),
            keys,
        )
        assert(json.contains("\"apiKey\":null")) { "explicit null per PROTOCOL §1.1" }
        val decoded = Credentials.decode(
            """{"mode":"claudeAccount","accessToken":"a","refreshToken":"r","expiresAt":1760000000000,
              |"scopes":["user:profile","user:inference","user:sessions:claude_code"],"organizationUuid":"o",
              |"accountEmail":"e","apiKey":null,"futureField":1}""".trimMargin(),
        )
        assertEquals(AuthMode.ClaudeAccount, decoded.mode)
        assertEquals(true, decoded.hasSessionsScope)
    }
}
