package com.claudeforwatch.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PkceTest {
    @Test
    fun rfc7636AppendixBVector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun generatedValuesAreBase64UrlWithoutPadding() {
        repeat(50) {
            val p = Pkce.generate()
            assertEquals(43, p.verifier.length)
            assertEquals(43, p.state.length)
            assertEquals(43, p.challenge.length)
            for (s in listOf(p.verifier, p.state, p.challenge)) {
                assertTrue(s.all { it.isLetterOrDigit() || it == '-' || it == '_' }, s)
                assertFalse('=' in s)
            }
            assertEquals(Pkce.challengeFor(p.verifier), p.challenge)
        }
    }

    @Test
    fun authorizeUrlHasProtocolParameterOrder() {
        val pkce = Pkce("verifier", "CHALLENGE", "STATE")
        assertEquals(
            "https://claude.ai/oauth/authorize?code=true&client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e" +
                "&response_type=code&redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback" +
                "&scope=user%3Aprofile+user%3Ainference+user%3Asessions%3Aclaude_code" +
                "&code_challenge=CHALLENGE&code_challenge_method=S256&state=STATE",
            OAuthClient.authorizeUrl(pkce),
        )
    }

    @Test
    fun toStringNeverLeaksSecrets() {
        val p = Pkce.generate()
        assertFalse(p.verifier in p.toString())
        val c = Credentials(AuthMode.ClaudeAccount, accessToken = "sk-ant-oat01-SECRET", refreshToken = "sk-ant-ort01-SECRET", apiKey = "k")
        assertFalse("SECRET" in c.toString())
    }
}
