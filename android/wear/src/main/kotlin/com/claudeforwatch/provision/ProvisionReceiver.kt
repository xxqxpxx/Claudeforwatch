package com.claudeforwatch.provision

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.claudeforwatch.AppGraph
import com.claudeforwatch.BuildConfig
import com.claudeforwatch.appGraph
import com.claudeforwatch.core.api.ApiTransport
import com.claudeforwatch.core.api.UsageClient
import com.claudeforwatch.core.auth.AuthMode
import com.claudeforwatch.core.auth.AuthProvider
import com.claudeforwatch.core.auth.Credentials
import com.claudeforwatch.core.auth.InMemoryTokenStore
import com.claudeforwatch.core.auth.Provisioning
import com.claudeforwatch.platform.Haptics
import com.claudeforwatch.ui.vm.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Gets credentials onto a watch that has no clipboard, from a computer over ADB, with no server
 * and no phone app:
 *
 * ```
 * adb shell am broadcast -a com.claudeforwatch.PROVISION --include-stopped-packages \
 *   -n com.claudeforwatch.personal/com.claudeforwatch.provision.ProvisionReceiver \
 *   --es api_key sk-ant-api03-…            # or
 *   --es oauth_code '<code>#<state>'        # finishes the QR sign-in the watch is showing, or
 *   --es credentials_b64 <base64 of a PROTOCOL §1.1 record>   # what scripts/watch-login.py sends
 * ```
 * Exactly one extra. `am` prints `Broadcast completed: result=0, data="ok: signed in as …"` on
 * success and `result=1, data="error: …"` on failure (result=0 with no data means the receiver
 * is not in this build or the package name is wrong).
 *
 * SECURITY: the receiver is `exported="true"` with no permission, so not only `adb shell` but
 * **any app installed on the watch** can send this broadcast. It cannot read anything back (the
 * result carries only "ok"/"error" and the account email), but it can overwrite the stored
 * credentials, e.g. silently signing the watch into an attacker's account so that dictated
 * prompts go there. That write-only risk is acceptable for a sideloaded personal build or a
 * debug build on your own watch, so the receiver is only declared in `src/personal/` and
 * `src/debug/` manifests. The distributable `store` release APK does not contain it (checked
 * with `aapt dump xmltree`), and [onReceive] refuses to run in any other build as well.
 *
 * Never logs or echoes secret values (keys, codes, tokens, verifier).
 */
class ProvisionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        if (!ENABLED) {
            setResult(RESULT_ERROR, "error: provisioning is disabled in this build", null)
            return
        }
        val app = context.applicationContext
        val g = app.appGraph
        val pending = goAsync()
        g.appScope.launch {
            var code = RESULT_ERROR
            var data = "error: interrupted"
            try {
                // Background broadcasts may run ~60 s before the system gives up on goAsync().
                val who = withTimeout(TIMEOUT_MILLIS) {
                    provision(g, intent.getStringExtra(EXTRA_API_KEY), intent.getStringExtra(EXTRA_OAUTH_CODE), intent.getStringExtra(EXTRA_CREDENTIALS_B64))
                }
                code = RESULT_OK
                data = "ok: signed in as $who"
            } catch (e: TimeoutCancellationException) {
                data = "error: timed out talking to Anthropic. Check the watch's network and retry."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                data = "error: ${e.userMessage()}"
            } finally {
                // Outcome only: the data string may hold the account email, which stays out of logcat.
                Log.i(TAG, if (code == RESULT_OK) "provisioned" else "provisioning failed")
                try {
                    withContext(NonCancellable + Dispatchers.Main) {
                        Toast.makeText(app, data.substringAfter(": ").replaceFirstChar { it.uppercase() }, Toast.LENGTH_LONG).show()
                        if (code == RESULT_OK) Haptics.tick(app) else Haptics.attention(app)
                    }
                } catch (e: Exception) {
                    // Toast/vibration are best-effort (e.g. no vibrator); the result is what matters.
                }
                pending.setResult(code, data, null)
                pending.finish()
            }
        }
    }

    /** Stores the credentials and returns the display identity (email, or a key label). */
    private suspend fun provision(g: AppGraph, apiKey: String?, oauthCode: String?, credentialsB64: String?): String {
        val creds = when (val req = Provisioning.request(apiKey, oauthCode, credentialsB64)) {
            is Provisioning.Request.ApiKey -> Credentials.forApiKey(Provisioning.apiKey(req.key))
            is Provisioning.Request.OAuthCode -> {
                requirePersonal()
                val p = g.pendingAuth.load()
                    ?: throw IllegalStateException("no sign-in in progress. On the watch open Sign in → Sign in with Claude, then send the code within 10 minutes.")
                val exchanged = g.oauth.exchange(Provisioning.codeWithState(req.code, p.pkce.state), p)
                g.pendingAuth.clear()
                completeAccount(g, exchanged)
            }
            is Provisioning.Request.Record -> {
                val record = Provisioning.record(req.base64, System.currentTimeMillis())
                if (record.mode == AuthMode.ClaudeAccount) {
                    requirePersonal()
                    completeAccount(g, record)
                } else record
            }
        }
        g.auth.signIn(creds)
        return when (creds.mode) {
            AuthMode.ApiKey -> "an API key"
            AuthMode.ClaudeAccount -> creds.accountEmail ?: "your Claude account"
        }
    }

    /**
     * Refreshes once if the token is expired, then fills organizationUuid / accountEmail from
     * `/api/oauth/profile` when missing (sessions need the org header). Uses a throwaway
     * in-memory [AuthProvider] so nothing is persisted until the record is complete; a rotated
     * refresh token is carried over from it.
     */
    private suspend fun completeAccount(g: AppGraph, creds: Credentials): Credentials {
        val probe = AuthProvider(InMemoryTokenStore(creds), g.oauth, userAgent = g.userAgent)
        var current = probe.validCredentials() // refreshes (once) if past expiresAt
        if (current.organizationUuid == null || current.accountEmail == null) {
            val profile = try {
                UsageClient(ApiTransport(g.http, probe)).profile()
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

    private fun requirePersonal() {
        check(BuildConfig.PERSONAL_MODE) { "Claude-account sign-in needs the personal build; this build takes api_key only." }
    }

    companion object {
        const val ACTION = "com.claudeforwatch.PROVISION"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_OAUTH_CODE = "oauth_code"
        const val EXTRA_CREDENTIALS_B64 = "credentials_b64"
        const val RESULT_OK = 0
        const val RESULT_ERROR = 1
        private const val TAG = "CfwProvision"
        private const val TIMEOUT_MILLIS = 45_000L

        /** Defence in depth: the manifest only declares the receiver in these builds anyway. */
        val ENABLED: Boolean get() = BuildConfig.DEBUG || BuildConfig.PERSONAL_MODE
    }
}
