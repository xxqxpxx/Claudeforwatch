package com.claudeforwatch.provision

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.claudeforwatch.AppGraph
import com.claudeforwatch.BuildConfig
import com.claudeforwatch.appGraph
import com.claudeforwatch.core.auth.AccountCompletion
import com.claudeforwatch.core.auth.AuthMode
import com.claudeforwatch.core.auth.Credentials
import com.claudeforwatch.core.auth.ProvisionMessage
import com.claudeforwatch.core.auth.Provisioning
import com.claudeforwatch.platform.Haptics
import com.claudeforwatch.ui.vm.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Applies credentials delivered from outside the watch UI: the ADB [ProvisionReceiver] and the
 * phone companion's Data Layer message ([ProvisionListenerService]). Exactly one of `api_key`,
 * `oauth_code`, `credentials_b64`: validate → (Claude account: refresh if expired, fill the
 * profile) → `auth.signIn` → toast + haptic. Claude-account input needs a PERSONAL_MODE build.
 *
 * Callers decide who may reach it; this class never logs or echoes secret values (keys, codes,
 * tokens, verifier). The result text may hold the account email and stays out of logcat.
 */
class Provisioner(context: Context, private val g: AppGraph = context.appGraph) {
    private val app = context.applicationContext

    sealed interface Result {
        /** `ok: …` / `error: …`, as returned to `am broadcast` and to the phone. */
        val wire: String

        data class Success(val who: String) : Result {
            override val wire get() = ProvisionMessage.ok(who)
        }

        data class Failure(val message: String) : Result {
            override val wire get() = ProvisionMessage.error(message)
        }
    }

    /** Never throws (except cancellation of the caller); shows a toast and buzzes either way. */
    suspend fun apply(apiKey: String?, oauthCode: String?, credentialsB64: String?, timeoutMillis: Long): Result {
        var result: Result = Result.Failure("interrupted")
        try {
            val who = withTimeout(timeoutMillis) { provision(apiKey, oauthCode, credentialsB64) }
            result = Result.Success(who)
        } catch (e: TimeoutCancellationException) {
            result = Result.Failure("timed out talking to Anthropic. Check the watch's network and retry.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            result = Result.Failure(e.userMessage())
        } finally {
            // Outcome only: the result may hold the account email, which stays out of logcat.
            Log.i(TAG, if (result is Result.Success) "provisioned" else "provisioning failed")
            feedback(result)
        }
        return result
    }

    private suspend fun feedback(result: Result) {
        try {
            withContext(NonCancellable + Dispatchers.Main) {
                Toast.makeText(app, result.wire.substringAfter(": ").replaceFirstChar { it.uppercase() }, Toast.LENGTH_LONG).show()
                if (result is Result.Success) Haptics.tick(app) else Haptics.attention(app)
            }
        } catch (e: Exception) {
            // Toast/vibration are best-effort (e.g. no vibrator); the result is what matters.
        }
    }

    /** Stores the credentials and returns the display identity (email, or a key label). */
    private suspend fun provision(apiKey: String?, oauthCode: String?, credentialsB64: String?): String {
        val creds = when (val req = Provisioning.request(apiKey, oauthCode, credentialsB64)) {
            is Provisioning.Request.ApiKey -> {
                // Signed in with Claude: keep the account for sessions, use the key for chat.
                val addedToAccount = g.auth.setChatApiKey(Provisioning.apiKey(req.key))
                return if (addedToAccount) "an API key for chat (sessions keep your Claude sign-in)" else "an API key"
            }
            is Provisioning.Request.OAuthCode -> {
                requirePersonal()
                val p = g.pendingAuth.load()
                    ?: throw IllegalStateException("no sign-in in progress. On the watch open Sign in → Sign in with Claude, then send the code within 10 minutes.")
                val exchanged = g.oauth.exchange(Provisioning.codeWithState(req.code, p.pkce.state), p)
                g.pendingAuth.clear()
                completeAccount(exchanged)
            }
            is Provisioning.Request.Record -> {
                val record = Provisioning.record(req.base64, System.currentTimeMillis())
                if (record.mode == AuthMode.ClaudeAccount) {
                    requirePersonal()
                    completeAccount(record)
                } else record
            }
        }
        g.auth.signIn(creds)
        return when (creds.mode) {
            AuthMode.ApiKey -> "an API key"
            AuthMode.ClaudeAccount -> creds.accountEmail ?: "your Claude account"
        }
    }

    /** Refresh-if-expired + profile fill, without persisting anything until the record is complete. */
    private suspend fun completeAccount(creds: Credentials): Credentials =
        AccountCompletion.complete(creds, g.oauth, g.http, g.userAgent)

    private fun requirePersonal() {
        check(BuildConfig.PERSONAL_MODE) { "Claude-account sign-in needs the personal build; this build takes api_key only." }
    }

    private companion object {
        const val TAG = "CfwProvision"
    }
}
