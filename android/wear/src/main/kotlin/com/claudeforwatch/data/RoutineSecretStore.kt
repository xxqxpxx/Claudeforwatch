package com.claudeforwatch.data

import android.content.Context
import com.claudeforwatch.platform.EncryptedBlobStore

/** The per-routine bearer token (PROTOCOL §6) is a secret: stored encrypted like credentials. */
class RoutineSecretStore(context: Context) {
    private val blobs = EncryptedBlobStore(context, "routine.pb")
    suspend fun token(): String? = blobs.read()?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
    suspend fun setToken(token: String) = blobs.write(token.trim().toByteArray(Charsets.UTF_8))
    suspend fun clear() = blobs.clear()
}
