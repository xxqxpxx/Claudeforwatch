package com.claudeforwatch.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claudeforwatch.AppGraph
import com.claudeforwatch.core.store.ChatThread
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatsListViewModel(private val g: AppGraph) : ViewModel() {
    val threads: StateFlow<List<ChatThread>?> =
        g.threads.threads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun delete(id: String) {
        viewModelScope.launch { g.threads.delete(id) }
    }
}
