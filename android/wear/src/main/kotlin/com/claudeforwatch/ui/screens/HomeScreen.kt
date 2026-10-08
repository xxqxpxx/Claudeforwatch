package com.claudeforwatch.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.CircularProgressIndicator
import com.claudeforwatch.BuildConfig
import com.claudeforwatch.R
import com.claudeforwatch.core.auth.AuthMode
import com.claudeforwatch.core.auth.AuthState
import com.claudeforwatch.ui.CenteredBox
import com.claudeforwatch.ui.Header
import com.claudeforwatch.ui.ListButton
import com.claudeforwatch.ui.ListScreen
import com.claudeforwatch.ui.Paragraph
import com.claudeforwatch.ui.Routes
import com.claudeforwatch.ui.StatusColors
import com.claudeforwatch.ui.vm.HomeViewModel

/** Entry list: Ask, Sessions (claudeAccount), Chats, Settings (PLAN §2). */
@Composable
fun HomeScreen(vm: HomeViewModel, onNavigate: (String) -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    ListScreen { spec ->
        item { Header("Claude", spec) }
        when (val auth = ui.auth) {
            AuthState.Loading -> item { CenteredBox { CircularProgressIndicator() } }
            AuthState.SignedOut -> {
                item { Paragraph("Talk to Claude from your wrist.", spec) }
                item { ListButton("Sign in", spec, onClick = { onNavigate(Routes.SIGN_IN) }, primary = true, iconRes = R.drawable.ic_claude) }
                item { ListButton("Settings", spec, onClick = { onNavigate(Routes.SETTINGS) }, iconRes = R.drawable.ic_settings) }
            }
            is AuthState.SignedIn -> {
                item { ListButton("Ask Claude", spec, onClick = { onNavigate(Routes.ASK) }, primary = true, iconRes = R.drawable.ic_mic) }
                if (ui.canUseSessions) {
                    item {
                        ListButton(
                            "Sessions", spec,
                            onClick = { onNavigate(Routes.SESSIONS) },
                            secondary = if (ui.needsActionCount > 0) "${ui.needsActionCount} need you" else "Claude Code",
                            badgeColor = if (ui.needsActionCount > 0) StatusColors.NeedsAction else null,
                            iconRes = R.drawable.ic_code,
                        )
                    }
                }
                item { ListButton("Chats", spec, onClick = { onNavigate(Routes.CHATS) }, iconRes = R.drawable.ic_chat) }
                item {
                    ListButton(
                        "Settings", spec,
                        onClick = { onNavigate(Routes.SETTINGS) },
                        secondary = if (auth.mode == AuthMode.ClaudeAccount) "Claude account" else "API key",
                        iconRes = R.drawable.ic_settings,
                    )
                }
                if (BuildConfig.PERSONAL_MODE && auth.mode == AuthMode.ApiKey) {
                    item { Paragraph("Sessions need a Claude-account sign-in (Settings).", spec) }
                }
            }
        }
    }
}
