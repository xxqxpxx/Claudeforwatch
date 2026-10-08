package com.claudeforwatch.core.auth

import com.claudeforwatch.core.api.ApiTransport
import com.claudeforwatch.core.api.UsageClient
import com.claudeforwatch.core.net.HttpClient
import kotlinx.coroutines.CancellationException

/**
 * Makes a Claude-account record ready to store: refreshes once if the token is past `expiresAt`,
 * then fills `organizationUuid` / `accountEmail` from `/api/oauth/profile` when missing (sessions
 * need the org header). Uses a throwaway in-memory [AuthProvider] so nothing is persisted until
 * the record is complete; a rotated refresh token is carried over from it.
 *
 * Shared by the watch's provisioning paths and the phone companion (which builds the record).
 */
object AccountCompletion {
    suspend fun complete(creds: Credentials, oauth: OAuthClient, http: HttpClient, userAgent: String): Credentials {
        val probe = AuthProvider(InMemoryTokenStore(creds), oauth, userAgent = userAgent)
        var current = probe.validCredentials() // refreshes (once) if past expiresAt
        if (current.organizationUuid == null || current.accountEmail == null) {
            val profile = try {
                UsageClient(ApiTransport(http, probe)).profile()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // optional (PROTOCOL §2); sessions still work for single-org accounts
            }
            current = probe.load() ?: current
            current = current.copy(
                organizationUuid = current.organizationUuid ?: profile?.organization?.uuid,
                accountEmail = current.accountEmail ?: profile?.account?.emailAddress,
            )
        }
        return current
    }
}
