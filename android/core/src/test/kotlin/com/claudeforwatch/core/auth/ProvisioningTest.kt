package com.claudeforwatch.core.auth

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProvisioningTest {
    private val now = 1_760_000_000_000L
    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())
    private fun b64Url(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    @Test
    fun exactlyOneExtra() {
        assertIs<Provisioning.Request.ApiKey>(Provisioning.request("sk-ant-x", null, " "))
        assertIs<Provisioning.Request.OAuthCode>(Provisioning.request(null, "c#s", null))
        assertIs<Provisioning.Request.Record>(Provisioning.request("", null, "e30="))
        assertFailsWith<IllegalArgumentException> { Provisioning.request(null, null, null) }
        assertFailsWith<IllegalArgumentException> { Provisioning.request("sk-ant-x", "c#s", null) }
    }

    @Test
    fun requestToStringIsRedacted() {
        val r = Provisioning.request("sk-ant-api03-secret", null, null)
        assertTrue("secret" !in r.toString())
    }

    @Test
    fun apiKeyShape() {
        assertEquals("sk-ant-api03-abcdefghij", Provisioning.apiKey("  sk-ant-api03-abcdefghij\n"))
        assertFailsWith<IllegalArgumentException> { Provisioning.apiKey("sk-proj-abcdefghijkl") }
        assertFailsWith<IllegalArgumentException> { Provisioning.apiKey("sk-ant-api03 abcdefghij") }
        assertFailsWith<IllegalArgumentException> { Provisioning.apiKey("sk-ant-") }
    }

    @Test
    fun codeWithStateKeepsSuffixOrAppendsPendingState() {
        assertEquals("abc#xyz", Provisioning.codeWithState(" abc#xyz ", "STATE"))
        assertEquals("abc#STATE", Provisioning.codeWithState("abc", "STATE"))
        val url = "https://platform.claude.com/oauth/code/callback?code=abc&state=xyz"
        assertEquals(url, Provisioning.codeWithState(url, "STATE"))
        // The appended form passes the core parser; a wrong suffix still fails it.
        assertEquals("abc", OAuthClient.parseCode(Provisioning.codeWithState("abc", "STATE"), "STATE"))
        assertFailsWith<OAuthException.StateMismatch> { OAuthClient.parseCode(Provisioning.codeWithState("abc#nope", "STATE"), "STATE") }
    }

    @Test
    fun fullRecordRoundTrips() {
        val creds = Credentials(
            mode = AuthMode.ClaudeAccount, accessToken = "sk-ant-oat01-a", refreshToken = "sk-ant-ort01-r",
            expiresAt = now + 1000, scopes = OAuthConfig.SCOPES, organizationUuid = "org", accountEmail = "me@example.com",
        )
        assertEquals(creds, Provisioning.record(b64(creds.encode()), now))
        assertEquals(creds, Provisioning.record(b64Url(creds.encode()), now))
    }

    @Test
    fun missingExpiryAndScopesAreFilled() {
        val json = """{"mode":"claudeAccount","accessToken":"sk-ant-oat01-a","refreshToken":"sk-ant-ort01-r"}"""
        val c = Provisioning.record(b64(json), now)
        assertEquals(now + 8 * 3600_000L - 60_000L, c.expiresAt)
        assertEquals(OAuthConfig.SCOPES, c.scopes)
        assertNull(c.organizationUuid)
        assertTrue(c.hasSessionsScope)
    }

    @Test
    fun apiKeyRecord() {
        val c = Provisioning.record(b64("""{"mode":"apiKey","apiKey":"sk-ant-api03-abcdefghij","accessToken":null}"""), now)
        assertEquals(AuthMode.ApiKey, c.mode)
        assertEquals("sk-ant-api03-abcdefghij", c.apiKey)
    }

    @Test
    fun invalidRecords() {
        assertFailsWith<IllegalArgumentException> { Provisioning.record("!!!not base64!!!", now) }
        assertFailsWith<IllegalArgumentException> { Provisioning.record(b64("not json"), now) }
        assertFailsWith<IllegalArgumentException> { Provisioning.record(b64("""{"accessToken":"x"}"""), now) }
        assertFailsWith<IllegalArgumentException> { Provisioning.record(b64("""{"mode":"claudeAccount"}"""), now) }
        assertFailsWith<IllegalArgumentException> { Provisioning.record(b64("""{"mode":"apiKey","apiKey":"nope"}"""), now) }
    }
}
