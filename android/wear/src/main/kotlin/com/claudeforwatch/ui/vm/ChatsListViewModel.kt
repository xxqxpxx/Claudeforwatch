package com.claudeforwatch.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claudeforwatch.AppGraph
import com.claudeforwatch.core.api.WebChatSummary
import com.claudeforwatch.core.api.WebChatsClient
import com.claudeforwatch.core.store.ChatThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** State of the experimental claude.ai section (PROTOCOL §7). */
sealed interface WebChatsUi {
    data object Idle : WebChatsUi
    data object Loading : WebChatsUi
    data class Loaded(val chats: List<WebChatSummary>) : WebChatsUi
    data class Failed(val message: String) : WebChatsUi
}

class ChatsListViewModel(private val g: AppGraph) : ViewModel() {
    val threads: StateFlow<List<ChatThread>?> =
        g.threads.threads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The claude.ai section shows for a Claude-account sign-in in a personal build. */
    val webChatsAvailable: StateFlow<Boolean> =
        g.auth.state.map { g.canUseSessions(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _web = MutableStateFlow<WebChatsUi>(WebChatsUi.Idle)
    val web: StateFlow<WebChatsUi> = _web.asStateFlow()

    private val _opening = MutableStateFlow<String?>(null)
    val opening: StateFlow<String?> = _opening.asStateFlow()

    fun delete(id: String) {
        viewModelScope.launch { g.threads.delete(id) }
    }

    fun loadWebChats() {
        if (_web.value is WebChatsUi.Loading) return
        _web.value = WebChatsUi.Loading
        viewModelScope.launch {
            _web.value = try {
                WebChatsUi.Loaded(g.webChats.list())
            } catch (e: CancellationException) {
                throw e
            } catch (e: WebChatsClient.Unavailable) {
                android.util.Log.w("CfwApi", "web chats: " + e.attempts.joinToString("; "))
                WebChatsUi.Failed(e.message ?: "claude.ai chats aren't reachable.")
            } catch (e: Exception) {
                WebChatsUi.Failed(e.userMessage())
            }
        }
    }

    /** Copies the end of a claude.ai chat into a local thread (once) and opens it. */
    fun openWebChat(chat: WebChatSummary, onOpen: (String) -> Unit) {
        if (_opening.value != null) return
        _opening.value = chat.uuid
        viewModelScope.launch {
            try {
                val id = "web-${chat.uuid}"
                if (g.threads.get(id) == null) {
                    val model = g.settings.current().model.id
                    g.threads.upsert(WebChatsClient.toThread(chat, g.webChats.messages(chat.uuid), model, System.currentTimeMillis()))
                }
                onOpen(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _web.value = WebChatsUi.Failed(e.userMessage())
            } finally {
                _opening.value = null
            }
        }
    }
}
