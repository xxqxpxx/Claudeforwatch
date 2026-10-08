package com.claudeforwatch.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.claudeforwatch.core.auth.OAuthClient
import com.claudeforwatch.core.auth.OAuthConfig
import com.claudeforwatch.core.auth.PendingAuthorization
import com.claudeforwatch.core.auth.Pkce
import kotlinx.coroutines.flow.first

private val Context.pendingAuthStore by preferencesDataStore(name = "pending_auth")

/**
 * The in-progress PKCE sign-in (verifier, state, redirect URI, start time) shown as a QR code,
 * persisted so [com.claudeforwatch.provision.ProvisionReceiver] can finish the exchange with an
 * `oauth_code` pushed over ADB even if the activity was destroyed. Plain DataStore: the verifier
 * is short-lived (10 min, PROTOCOL §2) and useless without the one-time code, but it is still
 * kept out of logs ([PendingAuthorization.toString] is redacted). Backups are disabled app-wide.
 */
class PendingAuthStore(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val store = context.applicationContext.pendingAuthStore

    suspend fun save(pending: PendingAuthorization) {
        store.edit {
            it[VERIFIER] = pending.pkce.verifier
            it[STATE] = pending.pkce.state
            it[REDIRECT_URI] = OAuthConfig.REDIRECT_URI
            it[CREATED_AT] = pending.createdAtMillis
        }
    }

    /** The pending sign-in, or `null` (and wiped) when absent, expired or from another redirect URI. */
    suspend fun load(): PendingAuthorization? {
        val p = store.data.first()
        val verifier = p[VERIFIER]
        val state = p[STATE]
        val createdAt = p[CREATED_AT]
        if (verifier == null || state == null || createdAt == null) return null
        val pkce = Pkce(verifier, Pkce.challengeFor(verifier), state)
        val pending = PendingAuthorization(pkce, OAuthClient.authorizeUrl(pkce), createdAt)
        if (p[REDIRECT_URI] != OAuthConfig.REDIRECT_URI || pending.isExpired(clock()) || createdAt > clock() + 60_000) {
            clear()
            return null
        }
        return pending
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    private companion object {
        val VERIFIER = stringPreferencesKey("verifier")
        val STATE = stringPreferencesKey("state")
        val REDIRECT_URI = stringPreferencesKey("redirect_uri")
        val CREATED_AT = longPreferencesKey("created_at")
    }
}
