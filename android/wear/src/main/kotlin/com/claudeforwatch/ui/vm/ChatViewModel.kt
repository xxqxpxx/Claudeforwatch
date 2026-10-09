package com.claudeforwatch.ui.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claudeforwatch.AppGraph
import com.claudeforwatch.core.api.ClaudeModel
import com.claudeforwatch.core.api.MessagesReduction
import com.claudeforwatch.core.store.ChatMessage
import com.claudeforwatch.core.store.ChatRole
import com.claudeforwatch.core.store.ChatThread
import com.claudeforwatch.platform.ReadAloud
import com.claudeforwatch.platform.StreamingOngoingActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUi(
    val thread: ChatThread? = null,
    val streamingText: String? = null,
    val error: String? = null,
    val readAloud: Boolean = false,
    val model: ClaudeModel = ClaudeModel.Default,
) {
    val isStreaming: Boolean get() = streamingText != null

    fun transcript(): TranscriptUi = TranscriptUi(
        title = thread?.title?.ifBlank { null } ?: "New chat",
        rows = thread?.messages.orEmpty().mapIndexed { i, m ->
            TranscriptRow.Bubble("m$i", fromUser = m.role == ChatRole.User, text = m.text)
        },
        streamingText = streamingText,
        working = isStreaming,
        error = error,
        isSession = false,
        model = thread?.model ?: model.id,
    )
}

/**
 * A local quick-chat thread over the Messages API (PROTOCOL §4). Used by the Ask screen
 * (always a new thread) and the chat transcript. Never retries a completion; a reply cut off
 * by [stop] (onStop) is persisted as far as it got.
 */
class ChatViewModel(private val g: AppGraph, context: Context, threadId: String?) : ViewModel() {
    private val appContext = context.applicationContext
    private val readAloud = ReadAloud(appContext)
    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = _ui.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            val settings = g.settings.current()
            val thread = threadId?.let { g.threads.get(it) }
            // Don't clobber a thread the user already started while settings were loading.
            _ui.update {
                val current = it.thread ?: thread
                it.copy(thread = current, readAloud = settings.readAloud, model = ClaudeModel.fromId(current?.model ?: settings.model.id))
            }
        }
    }

    fun send(text: String) {
        if (job?.isActive == true || text.isBlank()) return
        readAloud.stop()
        job = viewModelScope.launch {
            val settings = g.settings.current()
            val now = System.currentTimeMillis()
            var thread = (_ui.value.thread ?: ChatThread.new(_ui.value.model.id, now))
                .appending(ChatMessage(ChatRole.User, text.trim(), now))
            g.threads.upsert(thread)
            _ui.update { it.copy(thread = thread, streamingText = "", error = null) }
            StreamingOngoingActivity.show(appContext)
            var reduction = MessagesReduction()
            try {
                g.messages.stream(thread.messages, ClaudeModel.fromId(thread.model), settings.effort).collect { event ->
                    reduction = reduction.apply(event)
                    _ui.update { it.copy(streamingText = reduction.text) }
                }
                val replyText = when {
                    reduction.isRefusal && reduction.text.isBlank() -> MessagesReduction.REFUSAL_NOTICE
                    reduction.isRefusal -> reduction.text + "\n" + MessagesReduction.REFUSAL_NOTICE
                    else -> reduction.displayText
                }
                if (replyText.isNotBlank()) {
                    thread = thread.appending(ChatMessage(ChatRole.Assistant, replyText, System.currentTimeMillis(), reduction.stopReason))
                    g.threads.upsert(thread)
                }
                val streamError = reduction.errorType?.let { streamErrorMessage(it, reduction.errorMessage) }
                _ui.update { it.copy(thread = thread, streamingText = null, error = streamError) }
                if (_ui.value.readAloud && replyText.isNotBlank() && streamError == null) readAloud.speak(replyText)
            } catch (e: CancellationException) {
                if (reduction.text.isNotBlank()) {
                    val partial = thread.appending(ChatMessage(ChatRole.Assistant, reduction.text + "…", System.currentTimeMillis(), "interrupted"))
                    withContext(NonCancellable) { g.threads.upsert(partial) }
                    _ui.update { it.copy(thread = partial) }
                }
                _ui.update { it.copy(streamingText = null) }
                throw e
            } catch (e: Exception) {
                val creds = runCatching { g.auth.load() }.getOrNull()
                val onAccount = creds != null && creds.chatMode == com.claudeforwatch.core.auth.AuthMode.ClaudeAccount
                _ui.update { it.copy(thread = thread, streamingText = null, error = e.chatMessage(onAccount)) }
            } finally {
                StreamingOngoingActivity.hide(appContext)
            }
        }
    }

    /** onStop: cancel the stream (PLAN §4). */
    fun stop() {
        job?.cancel()
        readAloud.stop()
    }

    /** The Ask screen starts a fresh thread for each question. */
    fun startNewThread() {
        if (job?.isActive == true) return
        _ui.update { it.copy(thread = null, error = null, streamingText = null) }
    }

    fun setReadAloud(on: Boolean) {
        _ui.update { it.copy(readAloud = on) }
        if (!on) readAloud.stop()
        viewModelScope.launch { g.settings.setReadAloud(on) }
    }

    fun setModel(model: ClaudeModel) {
        _ui.update { it.copy(model = model, thread = it.thread?.copy(model = model.id)) }
        viewModelScope.launch { _ui.value.thread?.let { g.threads.upsert(it) } }
    }

    fun delete(onDone: () -> Unit) {
        val id = _ui.value.thread?.id
        viewModelScope.launch {
            if (id != null) g.threads.delete(id)
            onDone()
        }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    override fun onCleared() {
        readAloud.shutdown()
        StreamingOngoingActivity.hide(appContext)
    }
}
