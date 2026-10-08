package com.claudeforwatch

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.claudeforwatch.ui.ClaudeWatchApp
import com.claudeforwatch.ui.OpenTarget
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Single exported activity. The tile, complication and Ongoing Activity launch it with
 * [OpenTarget.EXTRA] = "ask" | "sessions".
 */
class MainActivity : ComponentActivity() {
    private val openRequests = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openRequests.value = intent.openTarget()
        setContent {
            ClaudeWatchApp(openRequests, onOpenHandled = { openRequests.value = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.openTarget()?.let { openRequests.value = it }
    }

    private fun Intent?.openTarget(): String? = this?.getStringExtra(OpenTarget.EXTRA)
}
