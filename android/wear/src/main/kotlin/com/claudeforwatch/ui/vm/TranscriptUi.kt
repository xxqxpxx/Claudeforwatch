package com.claudeforwatch.ui.vm

import com.claudeforwatch.core.reduce.PendingPermission

/** Shared view model for the transcript screen (sessions and chats, PLAN §2). */
data class TranscriptUi(
    val title: String = "",
    val headerSummary: String? = null,
    val rows: List<TranscriptRow> = emptyList(),
    val streamingText: String? = null,
    val loading: Boolean = false,
    val working: Boolean = false,
    val error: String? = null,
    val pendingPermission: PendingPermission? = null,
    val quickReplies: List<String> = emptyList(),
    val isSession: Boolean = false,
    val model: String? = null,
    val closed: Boolean = false,
)

sealed interface TranscriptRow {
    val key: String

    data class Bubble(override val key: String, val fromUser: Boolean, val text: String, val pending: Boolean = false) : TranscriptRow
    data class Tool(override val key: String, val tool: String, val summary: String) : TranscriptRow
    data class Notice(override val key: String, val text: String, val isError: Boolean = false) : TranscriptRow
}
