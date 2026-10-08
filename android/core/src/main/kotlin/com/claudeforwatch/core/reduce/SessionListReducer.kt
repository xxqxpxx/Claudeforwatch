package com.claudeforwatch.core.reduce

import com.claudeforwatch.core.model.SessionDto
import java.time.Instant

enum class SessionKind { Cloud, RemoteControl }

data class SessionRow(
    val id: String,
    val title: String,
    val kind: SessionKind,
    val workerStatus: String?,
    val needsAction: Boolean,
    val isRunning: Boolean,
    val summary: String?,
    val model: String?,
    val unread: Boolean,
    val lastEventAt: String?,
)

data class SessionListState(
    val rows: List<SessionRow>,
    val hiddenIds: List<String>,
    val needsActionCount: Int,
) {
    companion object {
        val Empty = SessionListState(emptyList(), emptyList(), 0)
    }
}

/**
 * docs/PROTOCOL.md §5.1 + spec/README.md: drop archived; order requires_action, running, then
 * last_event_at desc; `environment_kind == "bridge"` ⇒ Remote Control.
 */
object SessionListReducer {
    fun reduce(sessions: List<SessionDto>, showArchived: Boolean = false): SessionListState {
        val (archived, active) = sessions.partition { it.isArchived }
        val visible = if (showArchived) sessions else active
        val rows = visible
            .sortedWith(compareBy<SessionDto> { rank(it) }.thenByDescending { epoch(it.lastEventAt) })
            .map(::row)
        return SessionListState(
            rows = rows,
            hiddenIds = if (showArchived) emptyList() else archived.map { it.id },
            needsActionCount = rows.count { it.needsAction },
        )
    }

    fun kindOf(environmentKind: String?): SessionKind =
        if (environmentKind == "bridge") SessionKind.RemoteControl else SessionKind.Cloud

    private fun rank(s: SessionDto) = when {
        s.needsAction -> 0
        s.isRunning -> 1
        else -> 2
    }

    private fun epoch(iso: String?): Long =
        iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: Long.MIN_VALUE

    private fun row(s: SessionDto) = SessionRow(
        id = s.id,
        title = s.title?.takeIf { it.isNotBlank() } ?: "Untitled session",
        kind = kindOf(s.environmentKind),
        workerStatus = s.workerStatus,
        needsAction = s.needsAction,
        isRunning = s.isRunning,
        summary = s.externalMetadata?.postTurnSummary?.let(ToolSummary::oneLine),
        model = s.config?.model,
        unread = s.unread,
        lastEventAt = s.lastEventAt,
    )
}
