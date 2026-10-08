package com.claudeforwatch.platform

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Read-aloud for replies. Lazily initialised; call [shutdown] when the owner goes away. */
class ReadAloud(context: Context) {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun speak(text: String) {
        if (text.isBlank()) return
        val engine = tts
        if (engine == null) {
            pending = text
            tts = TextToSpeech(appContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.language = Locale.getDefault()
                    pending?.let { say(it) }
                }
                pending = null
            }
            return
        }
        if (ready) say(text) else pending = text
    }

    private fun say(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cfw-reply")
    }

    fun stop() {
        pending = null
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }
}
