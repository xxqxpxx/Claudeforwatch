package com.claudeforwatch.ui.screens

import androidx.compose.runtime.Composable
import androidx.wear.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Text
import com.claudeforwatch.R
import com.claudeforwatch.appGraph
import com.claudeforwatch.core.api.ClaudeModel
import com.claudeforwatch.ui.CenteredBox
import com.claudeforwatch.ui.Header
import com.claudeforwatch.ui.ListButton
import com.claudeforwatch.ui.ListScreen
import com.claudeforwatch.ui.Paragraph
import com.claudeforwatch.ui.Routes
import com.claudeforwatch.ui.vm.ChatsListViewModel
import com.claudeforwatch.ui.vm.WebChatsUi
import android.text.format.DateUtils

/** Local quick-chat threads (PLAN §2). Long-press to delete. */
@Composable
fun ChatsListScreen(onOpen: (String) -> Unit) {
    val graph = LocalContext.current.appGraph
    val vm: ChatsListViewModel = viewModel { ChatsListViewModel(graph) }
    val threads by vm.threads.collectAsStateWithLifecycle()
    val webAvailable by vm.webChatsAvailable.collectAsStateWithLifecycle()
    val web by vm.web.collectAsStateWithLifecycle()
    val opening by vm.opening.collectAsStateWithLifecycle()
    var deleteId by remember { mutableStateOf<String?>(null) }

    ListScreen { spec ->
        item { Header("Chats", spec) }
        item { ListButton("New chat", spec, onClick = { onOpen(Routes.NEW_CHAT_ID) }, primary = true, iconRes = R.drawable.ic_add) }
        val list = threads
        when {
            list == null -> item { CenteredBox { CircularProgressIndicator() } }
            list.isEmpty() -> item { Paragraph("Your watch chats stay on this watch.", spec) }
            else -> items(list, key = { it.id }) { t ->
                ListButton(
                    label = t.title.ifBlank { "Untitled" },
                    spec = spec,
                    onClick = { onOpen(t.id) },
                    onLongClick = { deleteId = t.id },
                    secondary = (if (t.id.startsWith("web-")) "claude.ai · " else "") +
                        DateUtils.getRelativeTimeSpanString(t.updatedAt).toString() + " · " + ClaudeModel.fromId(t.model).label,
                )
            }
        }

        // EXPERIMENTAL: claude.ai web chats (PROTOCOL §7). May be refused by claude.ai.
        if (webAvailable) {
            item { Header("claude.ai", spec) }
            when (val w = web) {
                WebChatsUi.Idle -> item {
                    ListButton("Load claude.ai chats", spec, onClick = vm::loadWebChats, secondary = "Experimental")
                }
                WebChatsUi.Loading -> item { CenteredBox { CircularProgressIndicator() } }
                is WebChatsUi.Failed -> item {
                    ListButton(w.message, spec, onClick = vm::loadWebChats, secondary = "Tap to retry")
                }
                is WebChatsUi.Loaded -> {
                    if (w.chats.isEmpty()) item { Paragraph("No claude.ai chats found.", spec) }
                    items(w.chats, key = { "web:" + it.uuid }) { c ->
                        ListButton(
                            label = c.name,
                            spec = spec,
                            onClick = { vm.openWebChat(c, onOpen) },
                            secondary = if (opening == c.uuid) "Opening…" else "Copy to watch and continue",
                        )
                    }
                }
            }
        }
    }

    AlertDialog(
        visible = deleteId != null,
        onDismissRequest = { deleteId = null },
        title = { Text("Delete chat?") },
        confirmButton = { AlertDialogDefaults.ConfirmButton(onClick = { deleteId?.let(vm::delete); deleteId = null }) },
        dismissButton = { AlertDialogDefaults.DismissButton(onClick = { deleteId = null }) },
    )
}
