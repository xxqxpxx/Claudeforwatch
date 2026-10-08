package com.claudeforwatch

import android.app.Application
import android.content.Context
import com.claudeforwatch.core.CoreInfo
import com.claudeforwatch.core.api.ApiTransport
import com.claudeforwatch.core.api.MessagesClient
import com.claudeforwatch.core.api.RoutinesClient
import com.claudeforwatch.core.api.SessionsClient
import com.claudeforwatch.core.api.UsageClient
import com.claudeforwatch.core.auth.AuthMode
import com.claudeforwatch.core.auth.AuthProvider
import com.claudeforwatch.core.auth.AuthState
import com.claudeforwatch.core.auth.OAuthClient
import com.claudeforwatch.data.DataStoreThreadStore
import com.claudeforwatch.data.PendingAuthStore
import com.claudeforwatch.data.RoutineSecretStore
import com.claudeforwatch.data.SettingsRepository
import com.claudeforwatch.data.WidgetSnapshotStore
import com.claudeforwatch.platform.EncryptedDataStoreTokenStore
import com.claudeforwatch.platform.OkHttpClientAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ClaudeApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}

/** Process-wide singletons (one DataStore per file per process). */
class AppGraph(context: Context) {
    /** For best-effort fire-and-forget calls that must outlive a screen (presence clear). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val userAgent = CoreInfo.userAgent(BuildConfig.VERSION_NAME, "Wear OS")
    val http = OkHttpClientAdapter()
    val tokenStore = EncryptedDataStoreTokenStore(context)
    val oauth = OAuthClient(http, userAgent = userAgent)
    val auth = AuthProvider(tokenStore, if (BuildConfig.PERSONAL_MODE) oauth else null, userAgent = userAgent)
    val transport = ApiTransport(http, auth)
    val messages = MessagesClient(transport)
    val sessions = SessionsClient(transport)
    val usage = UsageClient(transport)
    val routines = RoutinesClient(http, userAgent = userAgent)
    val threads = DataStoreThreadStore(context)
    val settings = SettingsRepository(context)
    val snapshot = WidgetSnapshotStore(context)
    val routineSecret = RoutineSecretStore(context)

    /** PKCE material of the QR currently (or recently) shown, for the ADB `oauth_code` path. */
    val pendingAuth = PendingAuthStore(context)

    /** Sessions and usage need a Claude-account sign-in, which only PERSONAL_MODE builds offer. */
    fun canUseSessions(state: AuthState): Boolean =
        BuildConfig.PERSONAL_MODE && state is AuthState.SignedIn && state.mode == AuthMode.ClaudeAccount && state.canUseSessions

    /** PLAN §3: sign-out clears everything (credentials, routine token, chats, widget state). */
    suspend fun signOut() {
        auth.signOut()
        routineSecret.clear()
        threads.clear()
        snapshot.reset()
    }
}

val Context.appGraph: AppGraph get() = (applicationContext as ClaudeApp).graph
