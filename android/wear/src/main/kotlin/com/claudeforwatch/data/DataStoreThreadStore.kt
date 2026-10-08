package com.claudeforwatch.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.claudeforwatch.core.store.ChatThread
import com.claudeforwatch.core.store.InMemoryThreadStore
import com.claudeforwatch.core.store.ThreadStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.threadsStore by preferencesDataStore(name = "threads")

/**
 * Quick-chat threads as one JSON document (PROTOCOL §4 shape), bounded to
 * [InMemoryThreadStore.MAX_THREADS]. Small enough for a watch; Room is a later milestone.
 */
class DataStoreThreadStore(context: Context) : ThreadStore {
    private val store = context.applicationContext.threadsStore
    private val key = stringPreferencesKey("threads_json")

    override val threads: Flow<List<ChatThread>> = store.data.map { prefs -> decode(prefs[key]) }

    override suspend fun get(id: String): ChatThread? = threads.first().firstOrNull { it.id == id }

    override suspend fun upsert(thread: ChatThread) = mutate { list -> list.filterNot { it.id == thread.id } + thread }

    override suspend fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }

    override suspend fun clear() = mutate { emptyList() }

    private suspend fun mutate(change: (List<ChatThread>) -> List<ChatThread>) {
        store.edit { prefs ->
            prefs[key] = ChatThread.encodeAll(InMemoryThreadStore.sorted(change(decode(prefs[key]))))
        }
    }

    private fun decode(json: String?): List<ChatThread> =
        json?.let { runCatching { ChatThread.decodeAll(it) }.getOrNull() } ?: emptyList()
}
