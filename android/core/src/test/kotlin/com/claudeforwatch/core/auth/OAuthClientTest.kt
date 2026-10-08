package com.claudeforwatch.core.auth

import com.claudeforwatch.core.CoreJson
import com.claudeforwatch.core.Spec
import com.claudeforwatch.core.net.HttpResponse
import com.claudeforwatch.core.net.StubHttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OAuthClientTest {
    private val now = 1_760_000_000_000L
    private val pkce = Pkce("test-verifier", Pkce.challengeFor("test-verifier"), "expected-state")

    @Test
    fun parsesCodeAndChecksState() {
        assertEquals("abc123", OAuthClient.parseCode("abc123#expected-state", "expected-state"))
        assertEquals("abc123", OAuthClient.parseCode("  abc123#expected-state \n", "expected-state"))
        assertEquals(
            "abc123",
            OAuthClient.parseCode("https://platform.claude.com/oauth/code/callback?code=abc123&state=expected-state", "expected-state"),
        )
    }

    @Test
    fun stateMismatchOrMissingIsAnError() {
        assertFailsWith<OAuthException.StateMismatch> { OAuthClient.parseCode("abc123#other-state", "expected-state") }
        assertFailsWith<OAuthException.StateMismatch> { OAuthClient.parseCode("abc123", "expected-state") }
        assertFailsWith<OAuthException.InvalidCode> { OAuthClient.parseCode("#expected-state", "expected-state") }
        assertFailsWith<OAuthException.InvalidCode> { OAuthClient.parseCode("   ", "expected-state") }
    }

    @Test
    fun exchangeSendsJsonAndBuildsCredentials() = runTest {
        val http = StubHttpClient().onUrl(OAuthConfig.TOKEN_URL, body = Spec.fixture("oauth_token_response.json"))
        val client = OAuthClient(http, clock = { now })
        val creds = client.exchange("thecode#expected-state", PendingAuthorization(pkce, "", now))

        val req = http.requests.single()
        assertEquals("application/json", req.header("Content-Type"))
        assertNull(req.header("anthropic-beta"))
        val body = CoreJson.parseToJsonElement(req.bodyString!!) as JsonObject
        assertEquals(
            listOf("grant_type", "code", "state", "client_id", "redirect_uri", "code_verifier"),
            body.keys.toList(),
        )
        assertEquals("authorization_code", body["grant_type"]!!.jsonPrimitive.content)
        assertEquals("thecode", body["code"]!!.jsonPrimitive.content)
        assertEquals("expected-state", body["state"]!!.jsonPrimitive.content)
        assertEquals("test-verifier", body["code_verifier"]!!.jsonPrimitive.content)

        assertEquals(AuthMode.ClaudeAccount, creds.mode)
        assertEquals("sk-ant-oat01-fixture-access", creds.accessToken)
        assertEquals("sk-ant-ort01-fixture-refresh", creds.refreshToken)
        assertEquals(now + 28800 * 1000 - 60_000, creds.expiresAt)
        assertEquals(listOf("user:profile", "user:inference", "user:sessions:claude_code"), creds.scopes)
        assertEquals("org-uuid-fixture", creds.organizationUuid)
        assertEquals("you@example.com", creds.accountEmail)
        assertNull(creds.apiKey)
    }

    @Test
    fun exchangeFallsBackToFormOnceOnInvalidGrant() = runTest {
        val http = StubHttpClient()
            .on({ it.header("Content-Type") == "application/json" }) {
                HttpResponse(400, body = """{"error":"invalid_grant","error_description":"bad"}""".toByteArray())
            }
            .on({ it.header("Content-Type") == "application/x-www-form-urlencoded" }) {
                HttpResponse(200, body = Spec.fixtureBytes("oauth_token_response.json"))
            }
        val creds = OAuthClient(http, clock = { now }).exchange("c#expected-state", PendingAuthorization(pkce, "", now))
        assertEquals("sk-ant-oat01-fixture-access", creds.accessToken)
        assertEquals(2, http.requests.size)
        assertEquals(
            "grant_type=authorization_code&code=c&state=expected-state&client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e" +
                "&redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback&code_verifier=test-verifier",
            http.requests[1].bodyString,
        )
    }

    @Test
    fun exchangeDoesNotRetryOtherErrorsOrASecondInvalidGrant() = runTest {
        val other = StubHttpClient().onUrl(OAuthConfig.TOKEN_URL, status = 400, body = """{"error":"invalid_request"}""")
        val e = assertFailsWith<OAuthException.Http> {
            OAuthClient(other, clock = { now }).exchange("c#expected-state", PendingAuthorization(pkce, "", now))
        }
        assertEquals("invalid_request", e.error)
        assertEquals(1, other.requests.size)

        val twice = StubHttpClient().onUrl(OAuthConfig.TOKEN_URL, status = 400, body = """{"error":"invalid_grant"}""")
        assertFailsWith<OAuthException.Http> {
            OAuthClient(twice, clock = { now }).exchange("c#expected-state", PendingAuthorization(pkce, "", now))
        }
        assertEquals(2, twice.requests.size)
    }

    @Test
    fun expiredPendingAuthorizationIsRejected() = runTest {
        val http = StubHttpClient()
        val pending = PendingAuthorization(pkce, "", now - OAuthConfig.PENDING_TTL_MILLIS - 1)
        assertFailsWith<OAuthException.Expired> { OAuthClient(http, clock = { now }).exchange("c#expected-state", pending) }
        assertEquals(0, http.requests.size)
    }

    @Test
    fun refreshRotatesAndSendsBetaHeader() = runTest {
        val http = StubHttpClient().onUrl(OAuthConfig.TOKEN_URL, body = Spec.fixture("oauth_refresh_response.json"))
        val old = Credentials(
            AuthMode.ClaudeAccount, "sk-ant-oat01-old", "sk-ant-ort01-old", now - 1,
            OAuthConfig.SCOPES, "org-uuid-fixture", "you@example.com",
        )
        val fresh = OAuthClient(http, clock = { now }).refresh(old)
        val req = http.requests.single()
        assertEquals("oauth-2025-04-20", req.header("anthropic-beta"))
        assertEquals("application/json", req.header("Content-Type"))
        val body = CoreJson.parseToJsonElement(req.bodyString!!) as JsonObject
        assertEquals("refresh_token", body["grant_type"]!!.jsonPrimitive.content)
        assertEquals("sk-ant-ort01-old", body["refresh_token"]!!.jsonPrimitive.content)
        assertEquals(OAuthConfig.CLIENT_ID, body["client_id"]!!.jsonPrimitive.content)

        assertEquals("sk-ant-oat01-fixture-access-2", fresh.accessToken)
        assertEquals("sk-ant-ort01-fixture-refresh-2", fresh.refreshToken)
        assertEquals("org-uuid-fixture", fresh.organizationUuid) // kept: refresh response has no org
        assertEquals("you@example.com", fresh.accountEmail)
        assertEquals(now + 28800 * 1000 - 60_000, fresh.expiresAt)
    }

    @Test
    fun refreshRejectionMeansSignedOut() = runTest {
        for (status in listOf(400, 401)) {
            val http = StubHttpClient().onUrl(OAuthConfig.TOKEN_URL, status = status, body = """{"error":"invalid_grant"}""")
            val old = Credentials(AuthMode.ClaudeAccount, "a", "r", 0)
            assertFailsWith<OAuthException.SignedOut> { OAuthClient(http, clock = { now }).refresh(old) }
        }
    }
}
