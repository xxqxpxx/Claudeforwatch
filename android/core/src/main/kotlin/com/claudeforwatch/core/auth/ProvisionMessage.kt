package com.claudeforwatch.core.auth

import com.claudeforwatch.core.CoreJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Wear Data Layer provisioning, phone companion → watch (android/phone → wear's
 * `ProvisionListenerService`). The phone sends [Payload] as UTF-8 JSON on [PATH]; the watch
 * replies on [RESULT_PATH] with UTF-8 text `ok: …` or `error: …` (same strings as the ADB
 * receiver). The Data Layer only routes between apps with the same application ID and signing
 * key, which is the security boundary. Pure JVM so both sides share and test it.
 */
object ProvisionMessage {
    const val PATH = "/claudeforwatch/provision"
    const val RESULT_PATH = "/claudeforwatch/provision/result"

    /** Payload kinds. `credentials` carries a base64 PROTOCOL §1.1 record (what `credentials_b64` takes). */
    enum class Kind(val wire: String) { ApiKey("api_key"), Credentials("credentials") }

    class Payload(val kind: Kind, val value: String) {
        override fun toString() = "Payload(${kind.wire}, <redacted>)"
    }

    fun encode(payload: Payload): ByteArray =
        buildJsonObject {
            put("kind", payload.kind.wire)
            put("value", payload.value)
        }.toString().toByteArray(Charsets.UTF_8)

    /** Throws [IllegalArgumentException] with a secret-free message on anything malformed. */
    fun decode(bytes: ByteArray): Payload {
        val obj = try {
            CoreJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw IllegalArgumentException("provisioning message is not a JSON object")
        val kindWire = (obj["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val kind = Kind.entries.firstOrNull { it.wire == kindWire }
            ?: throw IllegalArgumentException("unknown provisioning kind (expected api_key or credentials)")
        val value = (obj["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(!value.isNullOrBlank()) { "provisioning message has no value" }
        return Payload(kind, value)
    }

    /** Result line the watch sends back. */
    fun ok(who: String) = "ok: signed in as $who"
    fun error(message: String) = "error: $message"
}
