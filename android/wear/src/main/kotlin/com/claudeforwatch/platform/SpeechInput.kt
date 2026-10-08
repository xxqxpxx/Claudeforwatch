package com.claudeforwatch.platform

import android.app.Activity
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.wearableExtender

/**
 * Text entry on Wear OS without our own keyboard or recognizer:
 * * Dictate: system `ACTION_RECOGNIZE_SPEECH` (on-device; `SpeechRecognizer` is unreliable on
 *   Galaxy watches, docs/research/platform-notes.md).
 * * Type: the system RemoteInput sheet (keyboard, dictation, and the paired phone's
 *   "use phone keyboard" notification, which lets a code/key be pasted from the phone).
 */
object TextInputs {
    const val RESULT_KEY = "cfw_text"

    fun speechIntent(prompt: String): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)

    fun keyboardIntent(label: String, allowEmoji: Boolean = false): Intent {
        val remoteInput = RemoteInput.Builder(RESULT_KEY)
            .setLabel(label)
            .wearableExtender {
                setEmojisAllowed(allowEmoji)
                setInputActionType(EditorInfo.IME_ACTION_DONE)
            }
            .build()
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
        RemoteInputIntentHelper.putTitleExtra(intent, label)
        return intent
    }

    /**
     * Triple-fallback result reader (agent-watch prior art): RemoteInput results, then
     * `RecognizerIntent.EXTRA_RESULTS`, then `Intent.EXTRA_TEXT`.
     */
    fun readResult(data: Intent?): String? {
        if (data == null) return null
        val fromRemote = RemoteInput.getResultsFromIntent(data)?.getCharSequence(RESULT_KEY)?.toString()
        val fromSpeech = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        val fromText = data.getStringExtra(Intent.EXTRA_TEXT)
        return listOf(fromRemote, fromSpeech, fromText).firstOrNull { !it.isNullOrBlank() }?.trim()
    }
}

class TextInputLauncher internal constructor(
    private val launchIntent: (Intent, Intent?) -> Unit,
) {
    /** Opens system dictation; falls back to the keyboard sheet when no recognizer exists. */
    fun dictate(prompt: String = "Ask Claude") =
        launchIntent(TextInputs.speechIntent(prompt), TextInputs.keyboardIntent(prompt))

    fun type(label: String = "Message", allowEmoji: Boolean = true) =
        launchIntent(TextInputs.keyboardIntent(label, allowEmoji), null)
}

@Composable
fun rememberTextInputLauncher(onText: (String) -> Unit): TextInputLauncher {
    val callback = rememberUpdatedState(onText)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            TextInputs.readResult(result.data)?.takeIf { it.isNotEmpty() }?.let { callback.value(it) }
        }
    }
    return remember(launcher) {
        TextInputLauncher { intent, fallback ->
            try {
                launcher.launch(intent)
            } catch (e: ActivityNotFoundException) {
                if (fallback != null) runCatching { launcher.launch(fallback) }
            }
        }
    }
}
