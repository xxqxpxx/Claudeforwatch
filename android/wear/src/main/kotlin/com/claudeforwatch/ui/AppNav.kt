package com.claudeforwatch.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.claudeforwatch.appGraph
import com.claudeforwatch.core.auth.AuthState
import com.claudeforwatch.ui.screens.AskScreen
import com.claudeforwatch.ui.screens.ChatTranscriptScreen
import com.claudeforwatch.ui.screens.ChatSessionPickerScreen
import com.claudeforwatch.ui.screens.ChatsListScreen
import com.claudeforwatch.ui.screens.HomeScreen
import com.claudeforwatch.ui.screens.SessionTranscriptScreen
import com.claudeforwatch.ui.screens.SessionsListScreen
import com.claudeforwatch.ui.screens.SettingsScreen
import com.claudeforwatch.ui.screens.SignInScreen
import com.claudeforwatch.ui.vm.HomeViewModel
import kotlinx.coroutines.flow.StateFlow

object Routes {
    const val HOME = "home"
    const val ASK = "ask"
    const val SESSIONS = "sessions"
    const val SESSION = "session/{id}"
    const val CHATS = "chats"
    const val CHAT = "chat/{id}"
    const val SIGN_IN = "signin"
    const val SETTINGS = "settings"
    const val CHAT_SESSION_PICKER = "chatSessionPicker"
    const val NEW_CHAT_ID = "new"

    fun session(id: String) = "session/$id"
    fun chat(id: String) = "chat/$id"
}

/** Values of the `open` extra used by the tile, complication and Ongoing Activity. */
object OpenTarget {
    const val EXTRA = "com.claudeforwatch.OPEN"
    const val ASK = "ask"
    const val SESSIONS = "sessions"
}

@Composable
fun ClaudeWatchApp(openRequests: StateFlow<String?>, onOpenHandled: () -> Unit) {
    val context = LocalContext.current
    val graph = context.appGraph
    val nav = rememberSwipeDismissableNavController()
    val open by openRequests.collectAsStateWithLifecycle()

    LaunchedEffect(open) {
        val target = open ?: return@LaunchedEffect
        graph.auth.load()
        val auth = graph.auth.state.value
        when {
            auth !is AuthState.SignedIn -> nav.navigateSingle(Routes.SIGN_IN)
            target == OpenTarget.SESSIONS && graph.canUseSessions(auth) -> nav.navigateSingle(Routes.SESSIONS)
            else -> nav.navigateSingle(Routes.ASK)
        }
        onOpenHandled()
    }

    MaterialTheme {
        AppScaffold {
            SwipeDismissableNavHost(navController = nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) {
                    val vm: HomeViewModel = viewModel { HomeViewModel(graph) }
                    HomeScreen(vm, onNavigate = { nav.navigate(it) })
                }
                composable(Routes.ASK) {
                    AskScreen(onOpenChat = { id -> nav.navigate(Routes.chat(id)) })
                }
                composable(Routes.SESSIONS) {
                    SessionsListScreen(onOpen = { id -> nav.navigate(Routes.session(id)) })
                }
                composable(Routes.SESSION) { entry ->
                    val id = entry.arguments?.getString("id") ?: return@composable
                    SessionTranscriptScreen(id, onClosed = { nav.popBackStack() })
                }
                composable(Routes.CHATS) {
                    ChatsListScreen(onOpen = { id -> nav.navigate(Routes.chat(id)) })
                }
                composable(Routes.CHAT) { entry ->
                    val id = entry.arguments?.getString("id")?.takeIf { it != Routes.NEW_CHAT_ID }
                    ChatTranscriptScreen(id, onClosed = { nav.popBackStack() })
                }
                composable(Routes.SIGN_IN) {
                    SignInScreen(onDone = { nav.popBackStack(Routes.HOME, inclusive = false) })
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onSignIn = { nav.navigate(Routes.SIGN_IN) },
                        onOpenSession = { id -> nav.navigate(Routes.session(id)) },
                        onPickChatSession = { nav.navigate(Routes.CHAT_SESSION_PICKER) },
                    )
                }
                composable(Routes.CHAT_SESSION_PICKER) {
                    ChatSessionPickerScreen(onDone = { nav.popBackStack() })
                }
            }
        }
    }
}

private fun NavHostController.navigateSingle(route: String) {
    navigate(route) {
        popUpTo(Routes.HOME)
        launchSingleTop = true
    }
}
