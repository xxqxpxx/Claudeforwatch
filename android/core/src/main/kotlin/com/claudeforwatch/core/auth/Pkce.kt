package com.claudeforwatch.core.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** base64url without padding (RFC 4648 §5), as PKCE requires. */
object Base64Url {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)
}

/** PKCE material for one authorization attempt (docs/PROTOCOL.md §2). */
class Pkce(val verifier: String, val challenge: String, val state: String) {
    override fun toString() = "Pkce(<redacted>)"

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): Pkce {
            val verifier = Base64Url.encode(ByteArray(32).also(random::nextBytes))
            val state = Base64Url.encode(ByteArray(32).also(random::nextBytes))
            return Pkce(verifier, challengeFor(verifier), state)
        }

        /** S256: base64url(SHA-256(ascii(verifier))). */
        fun challengeFor(verifier: String): String =
            Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    }
}
