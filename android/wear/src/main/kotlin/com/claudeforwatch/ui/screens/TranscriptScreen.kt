package com.claudeforwatch.ui.screens

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.wear.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Dialog
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import com.claudeforwatch.R
import com.claudeforwatch.core.api.ClaudeModel
import com.claudeforwatch.core.api.PermissionMode
import com.claudeforwatch.core.reduce.PendingPermission
import com.claudeforwatch.platform.Haptics
import com.claudeforwatch.platform.rememberTextInputLauncher
import com.claudeforwatch.ui.CenteredBox
import com.claudeforwatch.ui.ChatBubble
import com.claudeforwatch.ui.Header
import com.claudeforwatch.ui.ListButton
import com.claudeforwatch.ui.ListScreen
import com.claudeforwatch.ui.NoticeRow
import com.claudeforwatch.ui.Paragraph
import com.claudeforwatch.ui.ToolRow
import com.claudeforwatch.ui.vm.TranscriptRow
import com.claudeforwatch.ui.vm.TranscriptUi

/** Long-press menu entries; null hides an entry (chats have no interrupt/archive). */
data class TranscriptMenu(
    val onInterrupt: (() -> Unit)? = null,
    val onSetModel: ((ClaudeModel) -> Unit)? = null,
    val onSetPermissionMode: ((PermissionMode) -> Unit)? = null,
    val onArchive: (() -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
    val readAloud: Boolean? = null,
    val onReadAloud: ((Boolean) -> Unit)? = null,
)

private enum class MenuPage { Main, Model, PermissionMode }

/**
 * Shared by sessions and chats (PLAN §2): bubbles, "⚙ tool" rows, streaming text, an
 * EdgeButton composer (Dictate / Type / Quick replies), a long-press menu and, for sessions,
 * the full-screen permission card.
 */
@Composable
fun TranscriptScreen(
    ui: TranscriptUi,
    onSend: (String) -> Unit,
    menu: TranscriptMenu,
    onDismissError: () -> Unit,
    onPermission: (Boolean) -> Unit = {},
    onAnswer: (String) -> Unit = {},
) {
    var composerOpen by rememberSaveable { mutableStateOf(false) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var dismissedPermissionId by rememberSaveable { mutableStateOf<String?>(null) }
    val input = rememberTextInputLauncher { text -> composerOpen = false; onSend(text) }
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val longPress = Modifier.combinedClickable(onClick = {}, onLongClick = { menuOpen = true })

    // Keep the newest content in view while a reply streams. Index = title row, optional
    // loading row, the transcript rows, then the streaming bubble (see the list below).
    val contentCount = ui.rows.size + (if (ui.streamingText != null) 1 else 0)
    val lastContentIndex = 1 + (if (ui.loading) 1 else 0) + contentCount - 1
    LaunchedEffect(contentCount, ui.streamingText?.length?.div(80)) {
        if (contentCount > 0) state.scrollToItem(lastContentIndex)
    }

    ScreenScaffold(
        scrollState = state,
        edgeButton = {
            EdgeButton(onClick = { composerOpen = true }, enabled = !ui.closed) {
                Text(if (ui.working && ui.isSession) "Steer" else "Reply")
            }
        },
    ) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item(key = "title") {
                ListButton(
                    label = ui.title.ifBlank { if (ui.isSession) "Session" else "Chat" },
                    spec = spec,
                    onClick = { menuOpen = true },
                    onLongClick = { menuOpen = true },
                    secondary = ui.headerSummary ?: ui.model?.let { ClaudeModel.entries.firstOrNull { m -> m.id == it }?.label ?: it },
                )
            }
            if (ui.loading) item(key = "loading") { CenteredBox { CircularProgressIndicator() } }
            if (!ui.loading && ui.rows.isEmpty() && ui.streamingText == null) {
                item(key = "empty") { Paragraph(if (ui.isSession) "No messages yet." else "Tap Reply to start.", spec) }
            }
            items(ui.rows, key = { it.key }) { row ->
                when (row) {
                    is TranscriptRow.Bubble -> ChatBubble(row.text, row.fromUser, longPress.padding(vertical = 2.dp), pending = row.pending)
                    is TranscriptRow.Tool -> ToolRow(row.tool, row.summary, longPress)
                    is TranscriptRow.Notice -> NoticeRow(row.text, row.isError)
                }
            }
            ui.streamingText?.let { text ->
                item(key = "streaming") {
                    if (text.isEmpty()) CenteredBox { CircularProgressIndicator() }
                    else ChatBubble(text, fromUser = false, modifier = longPress.padding(vertical = 2.dp))
                }
            }
            ui.pendingPermission?.let { p ->
                item(key = "perm-chip") {
                    ListButton("Permission needed", spec, onClick = { dismissedPermissionId = null }, secondary = "${p.tool}: ${p.summary}")
                }
            }
            ui.error?.let { msg ->
                item(key = "error") {
                    ListButton(msg, spec, onClick = onDismissError, secondary = "Tap to dismiss")
                }
            }
        }
    }

    ComposerSheet(
        visible = composerOpen,
        quickReplies = ui.quickReplies,
        onDismiss = { composerOpen = false },
        onDictate = { input.dictate(if (ui.isSession) "Tell Claude" else "Ask Claude") },
        onType = { input.type("Message") },
        onQuickReply = { composerOpen = false; onSend(it) },
    )
    TranscriptMenuDialog(visible = menuOpen, menu = menu, onDismiss = { menuOpen = false })
    PermissionCardScreen(
        pending = ui.pendingPermission,
        dismissedId = dismissedPermissionId,
        onDismiss = { dismissedPermissionId = it },
        onPermission = onPermission,
        onAnswer = onAnswer,
    )
}

@Composable
private fun ComposerSheet(
    visible: Boolean,
    quickReplies: List<String>,
    onDismiss: () -> Unit,
    onDictate: () -> Unit,
    onType: () -> Unit,
    onQuickReply: (String) -> Unit,
) {
    Dialog(visible = visible, onDismissRequest = onDismiss) {
        ListScreen { spec ->
            item { ListButton("Dictate", spec, onClick = onDictate, primary = true, iconRes = R.drawable.ic_mic) }
            item { ListButton("Type", spec, onClick = onType, iconRes = R.drawable.ic_keyboard, secondary = "Or use your phone keyboard") }
            if (quickReplies.isNotEmpty()) item { Header("Quick replies", spec) }
            items(quickReplies) { reply -> ListButton(reply, spec, onClick = { onQuickReply(reply) }) }
        }
    }
}

@Composable
private fun TranscriptMenuDialog(visible: Boolean, menu: TranscriptMenu, onDismiss: () -> Unit) {
    var page by remember { mutableStateOf(MenuPage.Main) }
    val close = { page = MenuPage.Main; onDismiss() }
    Dialog(visible = visible, onDismissRequest = close) {
        ListScreen { spec ->
            when (page) {
                MenuPage.Main -> {
                    menu.onInterrupt?.let { f -> item { ListButton("Interrupt", spec, onClick = { f(); close() }, primary = true) } }
                    menu.onSetModel?.let { item { ListButton("Model", spec, onClick = { page = MenuPage.Model }) } }
                    menu.onSetPermissionMode?.let { item { ListButton("Permission mode", spec, onClick = { page = MenuPage.PermissionMode }) } }
                    if (menu.readAloud != null && menu.onReadAloud != null) {
                        item {
                            ListButton(
                                if (menu.readAloud) "Read aloud: on" else "Read aloud: off", spec,
                                onClick = { menu.onReadAloud.invoke(!menu.readAloud) },
                            )
                        }
                    }
                    menu.onArchive?.let { f -> item { ListButton("Archive", spec, onClick = { f(); close() }) } }
                    menu.onDelete?.let { f -> item { ListButton("Delete chat", spec, onClick = { f(); close() }) } }
                }
                MenuPage.Model -> {
                    item { Header("Model", spec) }
                    items(ClaudeModel.entries) { m -> ListButton(m.label, spec, onClick = { menu.onSetModel?.invoke(m); close() }) }
                }
                MenuPage.PermissionMode -> {
                    item { Header("Permission mode", spec) }
                    items(PermissionMode.entries) { m ->
                        ListButton(m.label, spec, onClick = { menu.onSetPermissionMode?.invoke(m); close() })
                    }
                }
            }
        }
    }
}

/**
 * Full-screen permission prompt (PROTOCOL §5.5) with a haptic on arrival. AskUserQuestion
 * options render as buttons. Swiping away hides it; the transcript chip reopens it.
 */
@Composable
fun PermissionCardScreen(
    pending: PendingPermission?,
    dismissedId: String?,
    onDismiss: (String?) -> Unit,
    onPermission: (Boolean) -> Unit,
    onAnswer: (String) -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(pending?.requestId) {
        if (pending != null) Haptics.attention(context)
    }
    val visible = pending != null && pending.requestId != dismissedId
    Dialog(visible = visible, onDismissRequest = { onDismiss(pending?.requestId) }) {
        val p = pending ?: return@Dialog
        ListScreen { spec ->
            if (p.isQuestion) {
                p.questions.forEach { q ->
                    item { Header(q.header ?: "Claude asks", spec) }
                    item { Paragraph(q.question, spec, color = MaterialTheme.colorScheme.onSurface) }
                    items(q.options) { option -> ListButton(option, spec, onClick = { onAnswer(option) }) }
                }
                item { ListButton("Deny", spec, onClick = { onPermission(false) }) }
            } else {
                item { Header("Allow ${p.tool}?", spec) }
                if (p.summary.isNotBlank()) item { Paragraph(p.summary, spec, color = MaterialTheme.colorScheme.onSurface) }
                p.description?.takeIf { it.isNotBlank() && it != p.summary }?.let { d -> item { Paragraph(d, spec) } }
                item {
                    ListButton(
                        "Allow", spec, primary = true,
                        onClick = { Haptics.tick(context); onPermission(true) },
                    )
                }
                item { ListButton("Deny", spec, onClick = { Haptics.tick(context); onPermission(false) }) }
            }
        }
    }
}
