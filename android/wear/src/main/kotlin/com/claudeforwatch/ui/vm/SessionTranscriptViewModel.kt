package com.claudeforwatch.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claudeforwatch.AppGraph
import com.claudeforwatch.core.api.ApiException
import com.claudeforwatch.core.api.PermissionMode
import com.claudeforwatch.core.api.SessionsClient
import com.claudeforwatch.core.reduce.ToolSummary
import com.claudeforwatch.core.reduce.TranscriptItem
import com.claudeforwatch.core.reduce.TranscriptState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One Claude Code session: history, then the live stream resumed from the last sequence
 * number (PROTOCOL §5.2). The stream runs only between [start] (onStart) and [stop] (onStop).
 * [TranscriptState] is confined to the main thread.
 */
class SessionTranscriptViewModel(private val g: AppGraph, private val sessionId: String) : ViewModel() {
    private val state = TranscriptState()
    private val _ui = MutableStateFlow(TranscriptUi(isSession = true, quickReplies = SessionsClient.QUICK_REPLIES, loading = true))
    val ui: StateFlow<TranscriptUi> = _ui.asStateFlow()

    private var streamJob: Job? = null
    private var markedRead = false
    private var installId: String? = null
    private val outgoing = mutableListOf<Pair<String, Long>>() // text, lastSequence when sent

    fun start() {
        if (streamJob?.isActive == true) return
        streamJob = viewModelScope.launch { runStream() }
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
        val id = installId ?: return
        // Best effort: tell the server this client left (PROTOCOL §5.6).
        g.appScope.launch { runCatching { g.sessions.clearPresence(sessionId, id) } }
    }

    private suspend fun runStream() {
        val id = installId ?: g.settings.installId().also { installId = it }
        runCatching { g.sessions.presence(sessionId, id, System.currentTimeMillis()) }
        refreshDetails()
        var backoff = 1_000L
        while (currentCoroutineIsActive()) {
            try {
                if (state.lastSequence == null || state.needsRefetch) loadHistory()
                _ui.update { it.copy(loading = false, error = null) }
                g.sessions.stream(sessionId, state.lastSequence)
                    .flowOn(Dispatchers.IO)
                    .collect { frame ->
                        val changed = state.apply(frame)
                        if (state.needsRefetch) throw RefetchNeeded()
                        if (changed) {
                            publish()
                            backoff = 1_000L
                            if (state.items.lastOrNull() is TranscriptItem.TurnEnd) refreshDetails()
                        }
                    }
                // Server closed the stream normally: reconnect from the last sequence.
            } catch (e: RefetchNeeded) {
                state.reset()
                continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _ui.update { it.copy(loading = false, error = e.message) }
                if (e is ApiException.SignedOut || e is ApiException.NotSignedIn || e is ApiException.TrustedDeviceRequired ||
                    e is ApiException.NotAvailableInMode || (e.status == 404)
                ) return
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.userMessage()) }
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    private suspend fun currentCoroutineIsActive() = kotlin.coroutines.coroutineContext.isActive

    private suspend fun loadHistory() {
        val history = g.sessions.history(sessionId)
        state.reset()
        state.apply(history)
        publish()
        if (!markedRead) {
            markedRead = true
            runCatching { g.sessions.markRead(sessionId) }
        }
    }

    private suspend fun refreshDetails() {
        runCatching { g.sessions.get(sessionId) }.getOrNull()?.let { s ->
            _ui.update {
                it.copy(
                    title = s.title ?: it.title,
                    headerSummary = s.externalMetadata?.postTurnSummary?.let(ToolSummary::oneLine),
                    model = s.config?.model,
                    working = s.isRunning,
                    closed = s.isArchived,
                )
            }
        }
    }

    private fun publish() {
        val snap = state.snapshot()
        val last = snap.lastSequence ?: 0
        // Drop optimistic bubbles once the server echoes a user turn with the same text.
        outgoing.removeAll { (text, sentAt) ->
            snap.items.any { it is TranscriptItem.User && it.seq > sentAt && it.text.trim() == text.trim() }
        }
        val rows = snap.items.mapNotNull(::row) + outgoing.mapIndexed { i, (text, _) ->
            TranscriptRow.Bubble("out$i", fromUser = true, text = text, pending = true)
        }
        _ui.update {
            it.copy(
                rows = rows,
                streamingText = snap.streamingText,
                pendingPermission = snap.pendingPermission,
                working = snap.streamingText != null || (it.working && snap.items.lastOrNull() !is TranscriptItem.TurnEnd && last > 0),
            )
        }
    }

    private fun row(item: TranscriptItem): TranscriptRow? = when (item) {
        is TranscriptItem.User -> TranscriptRow.Bubble(item.key, fromUser = true, text = item.text)
        is TranscriptItem.Assistant -> TranscriptRow.Bubble(item.key, fromUser = false, text = item.text)
        is TranscriptItem.Tool -> TranscriptRow.Tool(item.key, item.tool, item.summary)
        is TranscriptItem.System -> TranscriptRow.Notice(item.key, item.text)
        is TranscriptItem.TurnEnd -> if (item.isError) TranscriptRow.Notice(item.key, "Turn ended with an error", isError = true) else null
        is TranscriptItem.Permission -> TranscriptRow.Notice(item.key, "Permission: ${item.tool} ${item.summary}".trim())
    }

    fun send(text: String) = action {
        outgoing += text to (state.lastSequence ?: 0)
        publish()
        try {
            g.sessions.sendText(sessionId, text)
        } catch (e: Exception) {
            outgoing.removeAll { it.first == text }
            publish()
            throw e
        }
    }

    fun interrupt() = action { g.sessions.interrupt(sessionId) }
    fun setModel(model: String) = action { g.sessions.setModel(sessionId, model); _ui.update { it.copy(model = model) } }
    fun setPermissionMode(mode: PermissionMode) = action { g.sessions.setPermissionMode(sessionId, mode) }

    fun archive(onDone: () -> Unit) = action {
        g.sessions.archive(sessionId)
        withContext(Dispatchers.Main) { onDone() }
    }

    fun answerPermission(allow: Boolean) {
        val pending = state.pendingPermission ?: return
        state.resolvePermission(pending.requestId)
        publish()
        action { g.sessions.respondToPermission(sessionId, pending.requestId, allow, pending.input) }
    }

    /**
     * AskUserQuestion: PROTOCOL §5.5 sends the chosen label as a normal user message.
     * (Unverified against a live session; see android/README.md.)
     */
    fun answerQuestion(option: String) {
        val pending = state.pendingPermission ?: return
        state.resolvePermission(pending.requestId)
        send(option)
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            attempt({ msg -> _ui.update { it.copy(error = msg) } }) { block() }
        }
    }

    private class RefetchNeeded : Exception()
}
