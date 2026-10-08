package com.claudeforwatch.phone.platform

import android.content.Context
import com.claudeforwatch.core.auth.ProvisionMessage
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * Wear Data Layer side of the companion: lists connected watches and sends them provisioning
 * messages ([ProvisionMessage]). Messages only reach the watch app with the same application ID
 * and signing key, so the phone and watch APKs must be the same flavor, signed with the same key.
 */
class WatchLink(context: Context) {
    data class Watch(val id: String, val name: String)

    private val nodeClient = Wearable.getNodeClient(context.applicationContext)
    private val messageClient = Wearable.getMessageClient(context.applicationContext)

    /** Throws a Play services `ApiException` when the Wearable API is unavailable on this phone. */
    suspend fun connectedWatches(): List<Watch> =
        nodeClient.connectedNodes.await().map { Watch(it.id, it.displayName.ifBlank { "Watch" }) }

    suspend fun send(nodeId: String, payload: ProvisionMessage.Payload) {
        messageClient.sendMessage(nodeId, ProvisionMessage.PATH, ProvisionMessage.encode(payload)).await()
    }

    /** Registers [onResult] for `/claudeforwatch/provision/result` replies; returns the handle for [removeResultListener]. */
    fun addResultListener(onResult: (nodeId: String, text: String) -> Unit): MessageClient.OnMessageReceivedListener {
        val listener = MessageClient.OnMessageReceivedListener { event ->
            if (event.path == ProvisionMessage.RESULT_PATH) onResult(event.sourceNodeId, event.data.toString(Charsets.UTF_8))
        }
        messageClient.addListener(listener)
        return listener
    }

    fun removeResultListener(listener: MessageClient.OnMessageReceivedListener) {
        messageClient.removeListener(listener)
    }
}
