package com.claudeforwatch.core.store

import com.claudeforwatch.core.CoreJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

/** Local quick-chat threads (docs/PROTOCOL.md §4 "Threads are stored locally"). */
@Serializable
enum class ChatRole {
    @SerialName("user") User,
    @SerialName("assistant") Assistant,
}

@Serializable
data class ChatMessage(
    val role: ChatRole,
    val text: String,
    val at: Long,
    val stopReason: String? = null,
)

@Serializable
data class ChatThread(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val model: String,
    val messages: List<ChatMessage> = emptyList(),
) {
    fun appending(message: ChatMessage): ChatThread = copy(
        messages = messages + message,
        updatedAt = message.at,
        title = if (title.isBlank() && message.role == ChatRole.User) titleFrom(message.text) else title,
    )

    /** Replaces the last message (used while a reply streams and when it is final). */
    fun replacingLast(message: ChatMessage): ChatThread =
        copy(messages = messages.dropLast(1) + message, updatedAt = message.at)

    companion object {
        const val TITLE_LENGTH = 40

        fun titleFrom(text: String): String = text.trim().replace(Regex("\\s+"), " ").take(TITLE_LENGTH)

        fun new(model: String, now: Long, id: String = UUID.randomUUID().toString()) =
            ChatThread(id = id, title = "", createdAt = now, updatedAt = now, model = model)

        fun encodeAll(threads: List<ChatThread>): String = CoreJson.encodeToString(ListSerializer(serializer()), threads)
        fun decodeAll(json: String): List<ChatThread> = CoreJson.decodeFromString(ListSerializer(serializer()), json)
    }
}

interface ThreadStore {
    /** All threads, most recently updated first. */
    val threads: Flow<List<ChatThread>>
    suspend fun get(id: String): ChatThread?
    suspend fun upsert(thread: ChatThread)
    suspend fun delete(id: String)
    suspend fun clear()
}

class InMemoryThreadStore(initial: List<ChatThread> = emptyList()) : ThreadStore {
    private val state = MutableStateFlow(sorted(initial))
    override val threads: Flow<List<ChatThread>> = state.asStateFlow()
    override suspend fun get(id: String) = state.value.firstOrNull { it.id == id }
    override suspend fun upsert(thread: ChatThread) = state.update { list -> sorted(list.filterNot { it.id == thread.id } + thread) }
    override suspend fun delete(id: String) = state.update { list -> list.filterNot { it.id == id } }
    override suspend fun clear() { state.value = emptyList() }

    companion object {
        /** Keeps the store bounded on a watch. */
        const val MAX_THREADS = 50
        fun sorted(list: List<ChatThread>) = list.sortedByDescending { it.updatedAt }.take(MAX_THREADS)
    }
}
