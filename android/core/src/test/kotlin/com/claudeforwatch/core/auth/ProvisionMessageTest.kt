package com.claudeforwatch.core.auth

import com.claudeforwatch.core.Spec
import com.claudeforwatch.core.net.StubHttpClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProvisionMessageTest {
    @Test
    fun roundTrip() {
        val bytes = ProvisionMessage.encode(ProvisionMessage.Payload(ProvisionMessage.Kind.ApiKey, "sk-ant-api03-secret"))
        assertEquals("""{"kind":"api_key","value":"sk-ant-api03-secret"}""", bytes.toString(Charsets.UTF_8))
        val back = ProvisionMessage.decode(bytes)
        assertEquals(ProvisionMessage.Kind.ApiKey, back.kind)
        assertEquals("sk-ant-api03-secret", back.value)
        assertFalse("secret" in back.toString())
        assertEquals(ProvisionMessage.Kind.Credentials, ProvisionMessage.decode("""{"kind":"credentials","value":"e30="}""".toByteArray()).kind)
    }

    @Test
    fun rejectsMalformed() {
        for (bad in listOf("", "[]", "nope", """{"kind":"oauth_code","value":"x"}""", """{"kind":"api_key"}""",
            """{"kind":"api_key","value":" "}""", """{"kind":1,"value":"x"}""")) {
            val e = assertFailsWith<IllegalArgumentException>(bad) { ProvisionMessage.decode(bad.toByteArray()) }
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun loopbackAuthorizeUrlAndExchangeUseTheSameRedirect() = runTest {
        val pkce = Pkce("v", Pkce.challengeFor("v"), "st")
        val http = StubHttpClient().onUrl(OAuthConfig.TOKEN_URL, body = Spec.fixture("oauth_token_response.json"))
        val client = OAuthClient(http, clock = { 0L })
        val pending = client.startAuthorization(pkce, OAuthClient.loopbackRedirectUri(5555))
        assertTrue("&redirect_uri=http%3A%2F%2Flocalhost%3A5555%2Fcallback&" in pending.authorizeUrl)
        client.exchange("http://localhost:5555/callback?code=a%2Bb&state=st", pending)
        val body = http.requests.single().bodyString!!
        assertTrue(""""redirect_uri":"http://localhost:5555/callback"""" in body, body)
        assertTrue(""""code":"a+b"""" in body, body)
    }

    @Test
    fun accountCompletionFillsProfile() = runTest {
        val http = StubHttpClient().onUrl(
            "https://api.anthropic.com/api/oauth/profile",
            body = """{"account":{"email_address":"me@example.com"},"organization":{"uuid":"org-9"}}""",
        )
        val creds = Credentials(AuthMode.ClaudeAccount, accessToken = "sk-ant-oat01-a", refreshToken = "r", expiresAt = Long.MAX_VALUE)
        val done = AccountCompletion.complete(creds, OAuthClient(http), http, "ua")
        assertEquals("org-9", done.organizationUuid)
        assertEquals("me@example.com", done.accountEmail)
        assertEquals("sk-ant-oat01-a", done.accessToken)
    }
}
