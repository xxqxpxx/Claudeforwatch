package com.claudeforwatch.core.api

import com.claudeforwatch.core.reduce.TranscriptItem
import com.claudeforwatch.core.reduce.TranscriptState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withTimeout

/**
 * Chat that runs inside a Claude Code session the user picked (docs/PROTOCOL.md §5.7), so it is
 * covered by the user's Claude subscription through Anthropic's own Claude Code instead of
 * calling the Messages API from the watch. The question is sent as a user turn; the reply is
 * the assistant text of that turn, streamed as [MessagesStreamEvent.TextSnapshot]s and ended by
 * the turn's `result`. Tool activity is not shown in chat; a permission prompt ends the reply
 * with an [MessagesStreamEvent.Error] pointing at the Sessions screen.
 */
class SessionChat(
    private val sessions: SessionsClient,
    private val timeoutMillis: Long = 5 * 60_000,
) {
    fun ask(sessionId: String, question: String): Flow<MessagesStreamEvent> = flow {
        val session = sessions.get(sessionId)
        check(!session.isArchived) { "That chat session is archived. Pick another in Settings → Chat session." }
        check(!(session.environmentKind == "bridge" && session.connectionStatus != null && session.connectionStatus != "connected")) {
            "Your computer's session is offline. Wake the computer or pick another chat session."
        }
        val baseline = sessions.history(sessionId, limit = 1).mapNotNull { it.sequence }.maxOrNull()
        val sent = wrap(question)
        sessions.sendText(sessionId, sent)
        val state = TranscriptState().apply { startAfter(baseline) }
        var ourTurnSeq: Long? = null
        var lastText = ""
        withTimeout(timeoutMillis) {
            sessions.stream(sessionId, baseline).transformWhile { frame ->
                if (!state.apply(frame)) return@transformWhile true
                val items = state.items
                if (ourTurnSeq == null) {
                    ourTurnSeq = items.firstOrNull { it is TranscriptItem.User && isOurs(it.text, question) }?.seq
                }
                val start = ourTurnSeq ?: return@transformWhile true
                val after = items.filter { it.seq > start || (it.seq == start && it !is TranscriptItem.User) }
                val text = (after.filterIsInstance<TranscriptItem.Assistant>().map { it.text } +
                    listOfNotNull(state.streamingText)).joinToString("\n").trim()
                if (text != lastText) {
                    lastText = text
                    emit(MessagesStreamEvent.TextSnapshot(text))
                }
                val end = after.filterIsInstance<TranscriptItem.TurnEnd>().firstOrNull()
                when {
                    end != null -> {
                        if (end.isError) emit(MessagesStreamEvent.Error("session_error", "Claude Code stopped with an error."))
                        emit(MessagesStreamEvent.Stop(if (end.isError) "error" else "end_turn", null))
                        false
                    }
                    state.pendingPermission != null -> {
                        emit(MessagesStreamEvent.Error(NEEDS_APPROVAL, APPROVAL_MESSAGE))
                        false
                    }
                    else -> true
                }
            }.collect { emit(it) }
        }
    }

    companion object {
        const val NEEDS_APPROVAL = "needs_approval"
        const val APPROVAL_MESSAGE = "Claude Code is waiting for your approval. Open Sessions to allow or deny it."

        /** Keeps replies watch-sized and stops Claude Code from touching files for a quick question. */
        const val WATCH_PREFIX =
            "[Sent from my watch. Reply in plain text, one to three short sentences, no markdown. " +
                "Don't run tools or change files unless I ask.]"

        fun wrap(question: String): String = "$WATCH_PREFIX\n${question.trim()}"

        /** The session echoes our turn back; match it even if the prefix was altered. */
        fun isOurs(echo: String, question: String): Boolean = echo.trim().endsWith(question.trim())
    }
}
