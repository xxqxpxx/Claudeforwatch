package com.claudeforwatch.provision

import android.util.Log
import com.claudeforwatch.BuildConfig
import com.claudeforwatch.core.auth.ProvisionMessage
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/**
 * Receives credentials from the phone companion (android/phone) over the Wear Data Layer:
 * a message on [ProvisionMessage.PATH] whose payload is
 * `{"kind":"api_key"|"credentials","value":"<key or base64 PROTOCOL §1.1 record>"}`.
 * Runs [Provisioner] and replies to the sending node on [ProvisionMessage.RESULT_PATH] with
 * `ok: …` / `error: …`.
 *
 * SECURITY: the service is exported (Google Play services binds it), but the Data Layer only
 * delivers messages sent by an app with the **same application ID and signing key** on a node
 * paired with this watch: other phone or watch apps cannot reach it. That pairing + signature
 * match is the security boundary, so `api_key` is accepted in every flavor (including the
 * distributable store release). Claude-account `credentials` still need a PERSONAL_MODE build,
 * enforced by [Provisioner] exactly like the ADB receiver. Never logs payloads.
 */
class ProvisionListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != ProvisionMessage.PATH) return
        // Listener callbacks run on a background thread; blocking for up to ~20 s is fine here.
        val result = runBlocking {
            val payload = try {
                ProvisionMessage.decode(event.data)
            } catch (e: IllegalArgumentException) {
                return@runBlocking Provisioner.Result.Failure(e.message ?: "unreadable provisioning message")
            }
            when (payload.kind) {
                ProvisionMessage.Kind.ApiKey ->
                    Provisioner(this@ProvisionListenerService).apply(payload.value, null, null, TIMEOUT_MILLIS)
                ProvisionMessage.Kind.Credentials ->
                    if (!BuildConfig.PERSONAL_MODE) {
                        Provisioner.Result.Failure("this watch build takes API keys only. Install the personal build to sign in with Claude.")
                    } else {
                        Provisioner(this@ProvisionListenerService).apply(null, null, payload.value, TIMEOUT_MILLIS)
                    }
            }
        }
        reply(event.sourceNodeId, result.wire)
    }

    private fun reply(nodeId: String, text: String) {
        try {
            Tasks.await(
                Wearable.getMessageClient(this).sendMessage(nodeId, ProvisionMessage.RESULT_PATH, text.toByteArray(Charsets.UTF_8)),
                REPLY_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
        } catch (e: Exception) {
            // The phone shows "no reply" after its own timeout; the watch already toasted the outcome.
            Log.w(TAG, "could not send the provisioning result to the phone (${e.javaClass.simpleName})")
        }
    }

    private companion object {
        const val TAG = "CfwProvision"
        const val TIMEOUT_MILLIS = 20_000L
        const val REPLY_TIMEOUT_SECONDS = 10L
    }
}
