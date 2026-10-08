package com.claudeforwatch.ui.screens

import androidx.compose.runtime.Composable
import androidx.wear.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.claudeforwatch.R
import com.claudeforwatch.appGraph
import com.claudeforwatch.core.reduce.SessionKind
import com.claudeforwatch.platform.rememberTextInputLauncher
import com.claudeforwatch.ui.CenteredBox
import com.claudeforwatch.ui.Header
import com.claudeforwatch.ui.ListButton
import com.claudeforwatch.ui.ListScreen
import com.claudeforwatch.ui.Paragraph
import com.claudeforwatch.ui.StatusColors
import com.claudeforwatch.ui.vm.SessionsListViewModel

/** Claude Code sessions, needs-action first (PROTOCOL §5.1). Long-press a row to archive. */
@Composable
fun SessionsListScreen(onOpen: (String) -> Unit) {
    val graph = LocalContext.current.appGraph
    val vm: SessionsListViewModel = viewModel { SessionsListViewModel(graph) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var archiveId by remember { mutableStateOf<String?>(null) }
    val taskInput = rememberTextInputLauncher { text -> vm.startRoutine(text) { id -> id?.let(onOpen) } }

    LifecycleStartEffect(vm) {
        vm.start()
        onStopOrDispose { vm.stop() }
    }

    ListScreen { spec ->
        item { Header("Sessions", spec) }
        ui.error?.let { msg -> item { Paragraph(msg, spec, color = MaterialTheme.colorScheme.error) } }
        if (ui.loading) {
            item { CenteredBox { CircularProgressIndicator() } }
        } else if (ui.list.rows.isEmpty() && ui.error == null) {
            item { Paragraph("No sessions. Start one with claude.ai/code or `claude /rc`.", spec) }
        }
        items(ui.list.rows, key = { it.id }) { row ->
            val status = when {
                row.needsAction -> "Needs you"
                row.isRunning -> "Running"
                else -> null
            }
            val where = if (row.kind == SessionKind.RemoteControl) "Remote" else "Cloud"
            ListButton(
                label = row.title,
                spec = spec,
                onClick = { onOpen(row.id) },
                onLongClick = { archiveId = row.id },
                secondary = listOfNotNull(status, row.summary ?: where).joinToString(" · "),
                badgeColor = when {
                    row.needsAction -> StatusColors.NeedsAction
                    row.isRunning -> StatusColors.Running
                    else -> StatusColors.Idle
                },
            )
        }
        if (ui.routineReady) {
            item {
                ListButton(
                    "New cloud task", spec,
                    onClick = { taskInput.dictate("Describe the task") },
                    secondary = "Runs your routine",
                    iconRes = R.drawable.ic_mic,
                    enabled = !ui.busy,
                )
            }
        }
    }

    AlertDialog(
        visible = archiveId != null,
        onDismissRequest = { archiveId = null },
        title = { Text("Archive session?") },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(onClick = { archiveId?.let(vm::archive); archiveId = null })
        },
        dismissButton = { AlertDialogDefaults.DismissButton(onClick = { archiveId = null }) },
    )
}
