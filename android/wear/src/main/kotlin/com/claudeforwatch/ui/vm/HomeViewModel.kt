package com.claudeforwatch.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claudeforwatch.AppGraph
import com.claudeforwatch.core.auth.AuthState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUi(val auth: AuthState = AuthState.Loading, val canUseSessions: Boolean = false, val needsActionCount: Int = 0)

class HomeViewModel(private val g: AppGraph) : ViewModel() {
    init {
        viewModelScope.launch { g.auth.load() }
    }

    val ui: StateFlow<HomeUi> = combine(g.auth.state, g.snapshot.snapshot) { auth, snap ->
        HomeUi(auth, g.canUseSessions(auth), if (g.canUseSessions(auth)) snap.needsActionCount else 0)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())
}
