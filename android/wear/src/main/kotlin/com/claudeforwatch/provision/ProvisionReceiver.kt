package com.claudeforwatch.provision

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.claudeforwatch.BuildConfig
import com.claudeforwatch.appGraph
import kotlinx.coroutines.launch

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
 * The apply logic lives in [Provisioner] (shared with the phone companion's
 * [ProvisionListenerService]); this class only adapts a broadcast to it.
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
        val pending = goAsync()
        app.appGraph.appScope.launch {
            var result: Provisioner.Result = Provisioner.Result.Failure("interrupted")
            try {
                // Background broadcasts may run ~60 s before the system gives up on goAsync().
                result = Provisioner(app).apply(
                    intent.getStringExtra(EXTRA_API_KEY),
                    intent.getStringExtra(EXTRA_OAUTH_CODE),
                    intent.getStringExtra(EXTRA_CREDENTIALS_B64),
                    TIMEOUT_MILLIS,
                )
            } finally {
                pending.setResult(if (result is Provisioner.Result.Success) RESULT_OK else RESULT_ERROR, result.wire, null)
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.claudeforwatch.PROVISION"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_OAUTH_CODE = "oauth_code"
        const val EXTRA_CREDENTIALS_B64 = "credentials_b64"
        const val RESULT_OK = 0
        const val RESULT_ERROR = 1
        private const val TIMEOUT_MILLIS = 45_000L

        /** Defence in depth: the manifest only declares the receiver in these builds anyway. */
        val ENABLED: Boolean get() = BuildConfig.DEBUG || BuildConfig.PERSONAL_MODE
    }
}
