package com.claudeforwatch.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claudeforwatch.AppGraph
import com.claudeforwatch.core.api.ClaudeModel
import com.claudeforwatch.core.api.Effort
import com.claudeforwatch.core.api.RoutinesClient
import com.claudeforwatch.core.auth.AuthMode
import com.claudeforwatch.core.auth.AuthState
import com.claudeforwatch.core.model.UsageDto
import com.claudeforwatch.data.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUi(
    val settings: AppSettings = AppSettings(),
    val auth: AuthState = AuthState.Loading,
    val usage: UsageDto? = null,
    val usageError: String? = null,
    val routineTokenSet: Boolean = false,
    val message: String? = null,
)

class SettingsViewModel(private val g: AppGraph) : ViewModel() {
    private val _ui = MutableStateFlow(SettingsUi())
    val ui: StateFlow<SettingsUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch { g.settings.settings.collect { s -> _ui.update { it.copy(settings = s) } } }
        viewModelScope.launch { g.auth.state.collect { a -> _ui.update { it.copy(auth = a) }; if (isAccount(a)) loadUsage() } }
        viewModelScope.launch { _ui.update { it.copy(routineTokenSet = g.routineSecret.token() != null) } }
    }

    private fun isAccount(a: AuthState) = a is AuthState.SignedIn && a.mode == AuthMode.ClaudeAccount

    fun loadUsage() {
        viewModelScope.launch {
            attempt({ msg -> _ui.update { it.copy(usageError = msg) } }) {
                val usage = g.usage.usage()
                _ui.update { it.copy(usage = usage, usageError = null) }
                usage.fiveHour?.utilization?.let { g.snapshot.update(fiveHourUtilization = it) }
            }
        }
    }

    fun cycleModel() = viewModelScope.launch {
        val all = ClaudeModel.entries
        g.settings.setModel(all[(all.indexOf(_ui.value.settings.model) + 1) % all.size])
    }

    fun cycleEffort() = viewModelScope.launch {
        val all = Effort.entries
        g.settings.setEffort(all[(all.indexOf(_ui.value.settings.effort) + 1) % all.size])
    }

    fun setReadAloud(on: Boolean) = viewModelScope.launch { g.settings.setReadAloud(on) }

    fun setRoutineId(id: String) = viewModelScope.launch {
        val trimmed = id.trim()
        if (!RoutinesClient.isValidTriggerId(trimmed)) {
            _ui.update { it.copy(message = "Routine id must start with trig_") }
        } else {
            g.settings.setRoutineId(trimmed)
            _ui.update { it.copy(message = "Routine saved") }
        }
    }

    fun setRoutineToken(token: String) = viewModelScope.launch {
        g.routineSecret.setToken(token)
        _ui.update { it.copy(routineTokenSet = true, message = "Routine token saved") }
    }

    fun clearRoutine() = viewModelScope.launch {
        g.routineSecret.clear()
        g.settings.setRoutineId(null)
        _ui.update { it.copy(routineTokenSet = false, message = "Routine removed") }
    }

    fun runRoutine(text: String, onSession: (String?) -> Unit) = viewModelScope.launch {
        attempt({ msg -> _ui.update { it.copy(message = msg) } }) {
            val id = _ui.value.settings.routineId ?: error("Set a routine id first")
            val token = g.routineSecret.token() ?: error("Set a routine token first")
            val result = g.routines.fire(id, token, text)
            _ui.update { it.copy(message = "Cloud session started") }
            onSession(result.sessionId)
        }
    }

    fun signOut() = viewModelScope.launch { g.signOut() }

    fun dismissMessage() = _ui.update { it.copy(message = null) }
}
