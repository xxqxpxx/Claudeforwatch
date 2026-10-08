package com.claudeforwatch.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.claudeforwatch.appGraph
import com.claudeforwatch.ui.vm.ChatViewModel
import com.claudeforwatch.ui.vm.SessionTranscriptViewModel

/** A Claude Code session: stream between onStart and onStop, resume from the last sequence. */
@Composable
fun SessionTranscriptScreen(sessionId: String, onClosed: () -> Unit) {
    val graph = LocalContext.current.appGraph
    val vm: SessionTranscriptViewModel = viewModel(key = "session-$sessionId") { SessionTranscriptViewModel(graph, sessionId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    LifecycleStartEffect(vm) {
        vm.start()
        onStopOrDispose { vm.stop() }
    }
    TranscriptScreen(
        ui = ui,
        onSend = vm::send,
        menu = TranscriptMenu(
            onInterrupt = vm::interrupt,
            onSetModel = { vm.setModel(it.id) },
            onSetPermissionMode = vm::setPermissionMode,
            onArchive = { vm.archive(onClosed) },
        ),
        onDismissError = vm::dismissError,
        onPermission = vm::answerPermission,
        onAnswer = vm::answerQuestion,
    )
}

/** A local chat thread ([threadId] null = new chat). */
@Composable
fun ChatTranscriptScreen(threadId: String?, onClosed: () -> Unit) {
    val context = LocalContext.current
    val graph = context.appGraph
    val vm: ChatViewModel = viewModel(key = "chat-${threadId ?: "new"}") { ChatViewModel(graph, context, threadId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    LifecycleStartEffect(vm) {
        onStopOrDispose { vm.stop() }
    }
    TranscriptScreen(
        ui = ui.transcript(),
        onSend = vm::send,
        menu = TranscriptMenu(
            onInterrupt = if (ui.isStreaming) vm::stop else null,
            onSetModel = vm::setModel,
            onDelete = { vm.delete(onClosed) },
            readAloud = ui.readAloud,
            onReadAloud = vm::setReadAloud,
        ),
        onDismissError = vm::dismissError,
    )
}
