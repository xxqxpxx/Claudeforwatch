package com.claudeforwatch.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.claudeforwatch.core.api.ClaudeModel
import com.claudeforwatch.core.api.Effort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.settingsStore by preferencesDataStore(name = "settings")

/** Non-secret preferences. Secrets live in [com.claudeforwatch.platform.EncryptedBlobStore]s. */
data class AppSettings(
    val model: ClaudeModel = ClaudeModel.Default,
    val effort: Effort = Effort.Default,
    val readAloud: Boolean = false,
    /** PROTOCOL §1.2 warning acknowledged (PERSONAL_MODE builds only). */
    val accountWarningAccepted: Boolean = false,
    val routineId: String? = null,
    /**
     * Claude-account chat: "session" sends questions through [chatSessionId] so the Claude
     * subscription covers them (PROTOCOL §5.7); "api" calls the Messages API (uses a chat API
     * key when one is stored).
     */
    val chatVia: ChatVia = ChatVia.Session,
    val chatSessionId: String? = null,
    val chatSessionTitle: String? = null,
)

enum class ChatVia(val wire: String, val label: String) {
    Session("session", "Subscription (Claude Code)"),
    Api("api", "API");

    companion object {
        fun fromWire(v: String?) = entries.firstOrNull { it.wire == v } ?: Session
    }
}

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsStore

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            model = ClaudeModel.fromId(p[MODEL]),
            effort = Effort.fromWire(p[EFFORT]),
            readAloud = p[READ_ALOUD] ?: false,
            accountWarningAccepted = p[WARNING] ?: false,
            routineId = p[ROUTINE_ID],
            chatVia = ChatVia.fromWire(p[CHAT_VIA]),
            chatSessionId = p[CHAT_SESSION_ID],
            chatSessionTitle = p[CHAT_SESSION_TITLE],
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setModel(model: ClaudeModel) = edit { it[MODEL] = model.id }
    suspend fun setEffort(effort: Effort) = edit { it[EFFORT] = effort.wire }
    suspend fun setReadAloud(on: Boolean) = edit { it[READ_ALOUD] = on }
    suspend fun setAccountWarningAccepted() = edit { it[WARNING] = true }
    suspend fun setChatVia(via: ChatVia) = edit { it[CHAT_VIA] = via.wire }
    suspend fun setChatSession(id: String?, title: String?) = edit {
        if (id == null) { it.remove(CHAT_SESSION_ID); it.remove(CHAT_SESSION_TITLE) }
        else { it[CHAT_SESSION_ID] = id; it[CHAT_SESSION_TITLE] = title ?: "Session" }
    }
    suspend fun setRoutineId(id: String?) = edit { if (id == null) it.remove(ROUTINE_ID) else it[ROUTINE_ID] = id }

    /** Random per-install id for session presence (PROTOCOL §5.6). Not tied to the account. */
    suspend fun installId(): String {
        store.data.first()[INSTALL_ID]?.let { return it }
        val id = UUID.randomUUID().toString()
        edit { if (it[INSTALL_ID] == null) it[INSTALL_ID] = id }
        return store.data.first()[INSTALL_ID] ?: id
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        store.edit { block(it) }
    }

    private companion object {
        val MODEL: Preferences.Key<String> = stringPreferencesKey("model")
        val EFFORT = stringPreferencesKey("effort")
        val READ_ALOUD = booleanPreferencesKey("read_aloud")
        val WARNING = booleanPreferencesKey("account_warning_accepted")
        val ROUTINE_ID = stringPreferencesKey("routine_id")
        val INSTALL_ID = stringPreferencesKey("install_id")
        val CHAT_VIA = stringPreferencesKey("chat_via")
        val CHAT_SESSION_ID = stringPreferencesKey("chat_session_id")
        val CHAT_SESSION_TITLE = stringPreferencesKey("chat_session_title")
    }
}
