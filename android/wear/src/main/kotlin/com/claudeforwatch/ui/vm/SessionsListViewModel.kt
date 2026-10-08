package com.claudeforwatch.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claudeforwatch.AppGraph
import com.claudeforwatch.core.reduce.SessionListReducer
import com.claudeforwatch.core.reduce.SessionListState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SessionsUi(
    val loading: Boolean = true,
    val list: SessionListState = SessionListState.Empty,
    val error: String? = null,
    val routineReady: Boolean = false,
    val busy: Boolean = false,
)

/** Polls `GET /v1/code/sessions` every 20 s while on screen (PROTOCOL §5.1). */
class SessionsListViewModel(private val g: AppGraph) : ViewModel() {
    private val _ui = MutableStateFlow(SessionsUi())
    val ui: StateFlow<SessionsUi> = _ui.asStateFlow()
    private var poll: Job? = null

    fun start() {
        if (poll?.isActive == true) return
        poll = viewModelScope.launch {
            _ui.update { it.copy(routineReady = g.settings.current().routineId != null && g.routineSecret.token() != null) }
            while (isActive) {
                refresh()
                delay(POLL_MILLIS)
            }
        }
    }

    fun stop() {
        poll?.cancel()
        poll = null
    }

    suspend fun refresh() {
        attempt({ msg -> _ui.update { it.copy(loading = false, error = msg) } }) {
            val response = g.sessions.list()
            val state = SessionListReducer.reduce(response.data)
            _ui.update { it.copy(loading = false, list = state, error = null) }
            g.snapshot.update(needsActionCount = state.needsActionCount)
        }
    }

    fun archive(id: String) {
        viewModelScope.launch {
            attempt({ msg -> _ui.update { it.copy(error = msg) } }) {
                g.sessions.archive(id)
                refresh()
            }
        }
    }

    /** PROTOCOL §6: start a cloud session from dictated text via the routine fire endpoint. */
    fun startRoutine(text: String, onStarted: (String?) -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            attempt({ msg -> _ui.update { it.copy(error = msg) } }) {
                val id = g.settings.current().routineId ?: error("No routine configured")
                val token = g.routineSecret.token() ?: error("No routine token")
                val result = g.routines.fire(id, token, text)
                refresh()
                onStarted(result.sessionId)
            }
            _ui.update { it.copy(busy = false) }
        }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    private companion object {
        const val POLL_MILLIS = 20_000L
    }
}
