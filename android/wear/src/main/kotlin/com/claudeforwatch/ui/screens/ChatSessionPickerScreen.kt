package com.claudeforwatch.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.CircularProgressIndicator
import com.claudeforwatch.AppGraph
import com.claudeforwatch.appGraph
import com.claudeforwatch.core.reduce.SessionKind
import com.claudeforwatch.core.reduce.SessionListReducer
import com.claudeforwatch.core.reduce.SessionRow
import com.claudeforwatch.ui.CenteredBox
import com.claudeforwatch.ui.Header
import com.claudeforwatch.ui.ListButton
import com.claudeforwatch.ui.ListScreen
import com.claudeforwatch.ui.Paragraph
import com.claudeforwatch.ui.vm.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PickerUi(val rows: List<SessionRow>? = null, val error: String? = null, val currentId: String? = null)

class ChatSessionPickerViewModel(private val g: AppGraph) : ViewModel() {
    private val _ui = MutableStateFlow(PickerUi())
    val ui: StateFlow<PickerUi> = _ui.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        val current = g.settings.current().chatSessionId
        _ui.value = try {
            // Sessions on your computer first: they answer fastest and keep chat off cloud VMs.
            val rows = SessionListReducer.reduce(g.sessions.list().data).rows
                .sortedBy { if (it.kind == SessionKind.RemoteControl) 0 else 1 }
            PickerUi(rows = rows, currentId = current)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PickerUi(rows = emptyList(), error = e.userMessage(), currentId = current)
        }
    }

    fun pick(row: SessionRow, onDone: () -> Unit) = viewModelScope.launch {
        g.settings.setChatSession(row.id, row.title)
        onDone()
    }
}

/** Chooses the Claude Code session that watch chat runs through (PROTOCOL §5.7). */
@Composable
fun ChatSessionPickerScreen(onDone: () -> Unit) {
    val graph = LocalContext.current.appGraph
    val vm: ChatSessionPickerViewModel = viewModel { ChatSessionPickerViewModel(graph) }
    val ui by vm.ui.collectAsStateWithLifecycle()

    ListScreen { spec ->
        item { Header("Chat session", spec) }
        item {
            Paragraph(
                "Watch chat is sent to this Claude Code session, so your Claude plan covers it. " +
                    "Best: on your computer run claude remote-control --name \"Watch chat\" in an empty folder.",
                spec,
            )
        }
        ui.error?.let { item { ListButton(it, spec, onClick = { vm.load() }, secondary = "Tap to retry") } }
        val rows = ui.rows
        when {
            rows == null -> item { CenteredBox { CircularProgressIndicator() } }
            rows.isEmpty() && ui.error == null -> item { Paragraph("No sessions yet. Start one, then come back.", spec) }
            else -> items(rows, key = { it.id }) { row ->
                ListButton(
                    label = (if (row.id == ui.currentId) "✓ " else "") + row.title,
                    spec = spec,
                    onClick = { vm.pick(row, onDone) },
                    secondary = (if (row.kind == SessionKind.RemoteControl) "Your computer" else "Cloud") +
                        (row.model?.let { " · $it" } ?: ""),
                )
            }
        }
    }
}
