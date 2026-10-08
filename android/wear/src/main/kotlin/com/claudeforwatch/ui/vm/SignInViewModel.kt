package com.claudeforwatch.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claudeforwatch.AppGraph
import com.claudeforwatch.BuildConfig
import com.claudeforwatch.core.api.ApiTransport
import com.claudeforwatch.core.api.MessagesClient
import com.claudeforwatch.core.auth.AuthProvider
import com.claudeforwatch.core.auth.Credentials
import com.claudeforwatch.core.auth.InMemoryTokenStore
import com.claudeforwatch.core.auth.PendingAuthorization
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SignInStep {
    data object Choose : SignInStep
    data object Working : SignInStep

    /** PROTOCOL §1.2 warning, shown before the Claude-account flow. */
    data object Warning : SignInStep
    data class Qr(val authorizeUrl: String) : SignInStep
    data object Done : SignInStep
}

data class SignInUi(val step: SignInStep = SignInStep.Choose, val error: String? = null, val personalMode: Boolean = BuildConfig.PERSONAL_MODE)

class SignInViewModel(private val g: AppGraph) : ViewModel() {
    private val _ui = MutableStateFlow(SignInUi())
    val ui: StateFlow<SignInUi> = _ui.asStateFlow()
    private var pending: PendingAuthorization? = null

    /** PLAN §3: validate a pasted key with a 1-token call before storing it. */
    fun submitApiKey(raw: String) {
        val key = raw.trim().filterNot { it.isWhitespace() }
        if (key.isEmpty()) return
        val previous = _ui.value.step
        _ui.update { it.copy(step = SignInStep.Working, error = null) }
        viewModelScope.launch {
            val creds = Credentials.forApiKey(key)
            val probe = AuthProvider(InMemoryTokenStore(creds), null, userAgent = g.userAgent)
            val ok = attempt({ msg -> _ui.update { it.copy(step = previous, error = msg) } }) {
                MessagesClient(ApiTransport(g.http, probe)).validate()
                g.auth.signIn(creds)
            }
            if (ok != null) _ui.update { it.copy(step = SignInStep.Done) }
        }
    }

    fun beginClaudeAccount() {
        if (!BuildConfig.PERSONAL_MODE) return
        viewModelScope.launch {
            if (g.settings.current().accountWarningAccepted) showQr() else _ui.update { it.copy(step = SignInStep.Warning, error = null) }
        }
    }

    fun acceptWarning() {
        viewModelScope.launch {
            g.settings.setAccountWarningAccepted()
            showQr()
        }
    }

    fun showQr() {
        val p = g.oauth.startAuthorization()
        pending = p
        _ui.update { it.copy(step = SignInStep.Qr(p.authorizeUrl), error = null) }
    }

    /** `<code>#<state>` pasted from the phone (PROTOCOL §2). */
    fun submitCode(code: String) {
        val p = pending ?: return showQr()
        val qr = _ui.value.step
        _ui.update { it.copy(step = SignInStep.Working, error = null) }
        viewModelScope.launch {
            val ok = attempt({ msg -> _ui.update { it.copy(step = qr, error = msg) } }) {
                val creds = g.oauth.exchange(code, p)
                g.auth.signIn(creds)
            }
            if (ok != null) {
                pending = null
                _ui.update { it.copy(step = SignInStep.Done) }
            }
        }
    }

    fun back() = _ui.update { it.copy(step = SignInStep.Choose, error = null) }
    fun dismissError() = _ui.update { it.copy(error = null) }
}
