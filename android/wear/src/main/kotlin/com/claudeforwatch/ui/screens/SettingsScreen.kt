package com.claudeforwatch.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import com.claudeforwatch.BuildConfig
import com.claudeforwatch.R
import com.claudeforwatch.appGraph
import com.claudeforwatch.core.auth.AuthMode
import com.claudeforwatch.core.auth.AuthState
import com.claudeforwatch.core.model.UsageWindow
import com.claudeforwatch.platform.rememberTextInputLauncher
import com.claudeforwatch.ui.Header
import com.claudeforwatch.ui.ListButton
import com.claudeforwatch.ui.ListScreen
import com.claudeforwatch.ui.Paragraph
import com.claudeforwatch.ui.vm.SettingsViewModel

const val POLICY_NOTICE =
    "Claude for Watch is an independent app, not made by Anthropic. API-key chat uses the official Claude API " +
        "and is billed to the key's organization. Claude-account sign-in (personal builds only) uses Anthropic's " +
        "private sign-in, which Anthropic does not support for third-party apps."

@Composable
fun SettingsScreen(onSignIn: () -> Unit, onOpenSession: (String) -> Unit, onPickChatSession: () -> Unit = {}) {
    val graph = LocalContext.current.appGraph
    val vm: SettingsViewModel = viewModel { SettingsViewModel(graph) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }
    val routineIdInput = rememberTextInputLauncher { vm.setRoutineId(it) }
    val routineTokenInput = rememberTextInputLauncher { vm.setRoutineToken(it) }
    val routineTask = rememberTextInputLauncher { text -> vm.runRoutine(text) { id -> if (id != null && graph.canUseSessions(ui.auth)) onOpenSession(id) } }
    val auth = ui.auth

    ListScreen { spec ->
        item { Header("Settings", spec) }
        ui.message?.let { msg -> item { ListButton(msg, spec, onClick = vm::dismissMessage, secondary = "Tap to dismiss") } }

        // Account
        when (auth) {
            is AuthState.SignedIn -> {
                item {
                    ListButton(
                        if (auth.mode == AuthMode.ClaudeAccount) "Claude account" else "API key", spec,
                        onClick = {},
                        secondary = auth.accountEmail ?: if (auth.mode == AuthMode.ApiKey) "Chat only" else null,
                        iconRes = R.drawable.ic_claude,
                    )
                }
                if (auth.mode == AuthMode.ClaudeAccount) {
                    item {
                        ListButton(
                            "Chat uses", spec,
                            onClick = { if (auth.hasChatApiKey) vm.removeChatKey() },
                            secondary = if (auth.hasChatApiKey) "API key · tap to remove" else "Claude account · send a key from the phone app",
                        )
                    }
                }
                if (BuildConfig.PERSONAL_MODE && auth.mode == AuthMode.ApiKey) {
                    item { ListButton("Sign in with Claude", spec, onClick = onSignIn, secondary = "Unlocks sessions and usage") }
                }
                item { ListButton("Sign out", spec, onClick = { confirmSignOut = true }) }
            }
            else -> item { ListButton("Sign in", spec, onClick = onSignIn, primary = true) }
        }

        // Chat
        item { Header("Chat", spec) }
        if (graph.canUseSessions(auth)) {
            item {
                ListButton(
                    "Chat runs on", spec,
                    onClick = { vm.toggleChatVia() },
                    secondary = ui.settings.chatVia.label,
                )
            }
            if (ui.settings.chatVia == com.claudeforwatch.data.ChatVia.Session) {
                item {
                    ListButton(
                        "Chat session", spec,
                        onClick = onPickChatSession,
                        secondary = ui.settings.chatSessionTitle ?: "Not set · tap to pick",
                    )
                }
            }
        }
        item { ListButton("Model", spec, onClick = { vm.cycleModel() }, secondary = ui.settings.model.label) }
        item { ListButton("Effort", spec, onClick = { vm.cycleEffort() }, secondary = ui.settings.effort.wire) }
        item { ReadAloudSwitch(ui.settings.readAloud, { vm.setReadAloud(it) }, spec) }

        // Usage (claudeAccount)
        if (auth is AuthState.SignedIn && auth.mode == AuthMode.ClaudeAccount) {
            item { Header("Usage", spec) }
            val usage = ui.usage
            if (usage == null) {
                item { Paragraph(ui.usageError ?: "Loading…", spec) }
            } else {
                item { UsageMeter("5 hours", usage.fiveHour, Modifier.transformedHeight(this, spec)) }
                item { UsageMeter("7 days", usage.sevenDay, Modifier.transformedHeight(this, spec)) }
            }
            item { ListButton("Refresh usage", spec, onClick = vm::loadUsage) }
        }

        // Routine (PROTOCOL §6)
        item { Header("Cloud routine", spec) }
        item {
            ListButton("Routine id", spec, onClick = { routineIdInput.type("trig_…", allowEmoji = false) }, secondary = ui.settings.routineId ?: "Not set")
        }
        item {
            ListButton(
                "Routine token", spec,
                onClick = { routineTokenInput.type("Paste routine token", allowEmoji = false) },
                secondary = if (ui.routineTokenSet) "Saved" else "Not set",
            )
        }
        if (ui.routineTokenSet && ui.settings.routineId != null) {
            item { ListButton("Run routine", spec, onClick = { routineTask.dictate("Describe the task") }, iconRes = R.drawable.ic_mic) }
            item { ListButton("Remove routine", spec, onClick = { vm.clearRoutine() }) }
        }

        // About
        item { Header("About", spec) }
        item { Paragraph(POLICY_NOTICE, spec) }
        item {
            Paragraph("Version ${BuildConfig.VERSION_NAME}" + if (BuildConfig.PERSONAL_MODE) " (personal build)" else "", spec)
        }
    }

    AlertDialog(
        visible = confirmSignOut,
        onDismissRequest = { confirmSignOut = false },
        title = { Text("Sign out?") },
        text = { Text("Removes your sign-in, routine token and chats from this watch.") },
        confirmButton = { AlertDialogDefaults.ConfirmButton(onClick = { confirmSignOut = false; vm.signOut() }) },
        dismissButton = { AlertDialogDefaults.DismissButton(onClick = { confirmSignOut = false }) },
    )
}

@Composable
private fun UsageMeter(label: String, window: UsageWindow?, modifier: Modifier = Modifier) {
    val pct = window?.utilization ?: 0.0
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text("$label: ${pct.toInt()}%", style = MaterialTheme.typography.labelMedium)
        LinearProgressIndicator(progress = { (pct / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        window?.resetsAt?.let { Text("Resets ${it.take(16).replace('T', ' ')} UTC", style = MaterialTheme.typography.labelSmall) }
    }
}
