package com.claudeforwatch.core.api

import com.claudeforwatch.core.CoreJson
import com.claudeforwatch.core.auth.AuthHeaders
import com.claudeforwatch.core.auth.AuthMode
import com.claudeforwatch.core.auth.AuthProvider
import com.claudeforwatch.core.net.HttpClient
import com.claudeforwatch.core.net.HttpRequest
import com.claudeforwatch.core.net.HttpResponse
import com.claudeforwatch.core.store.ChatMessage
import com.claudeforwatch.core.store.ChatRole
import com.claudeforwatch.core.store.ChatThread
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant

/** One claude.ai conversation in the list. */
data class WebChatSummary(val uuid: String, val name: String, val updatedAt: String?, val model: String?)

/** A claude.ai message reduced to plain text. */
data class WebChatMessage(val fromUser: Boolean, val text: String, val createdAt: String?)

/**
 * EXPERIMENTAL, personal builds only (docs/PROTOCOL.md §7). Reads the user's claude.ai web chats
 * with the Claude-account OAuth token through the endpoints the claude.ai web app uses. Anthropic
 * documents no such API: these routes normally take a browser cookie and sit behind Cloudflare,
 * so this may simply be refused. Each host is tried once; the first that answers is remembered.
 * Read-only: nothing is ever written back to claude.ai.
 */
class WebChatsClient(
    private val http: HttpClient,
    private val auth: AuthProvider,
    private val hosts: List<String> = listOf("https://claude.ai", "https://api.anthropic.com"),
) {
    /** Thrown when no host accepted the token; [attempts] says what each one answered. */
    class Unavailable(val attempts: List<String>) : Exception(
        "claude.ai chats aren't reachable with this sign-in (" + attempts.joinToString("; ") + ").",
    )

    @Volatile
    private var workingHost: String? = null

    suspend fun list(limit: Int = 30): List<WebChatSummary> {
        val body = getJson { org -> "/api/organizations/$org/chat_conversations?limit=$limit" }
        return parseList(body)
    }

    suspend fun messages(uuid: String): List<WebChatMessage> {
        val id = URLEncoder.encode(uuid, "UTF-8")
        val body = getJson { org -> "/api/organizations/$org/chat_conversations/$id?tree=True&rendering_mode=messages" }
        return parseMessages(body)
    }

    private suspend fun getJson(path: (String) -> String): String {
        val creds = auth.validCredentials()
        if (creds.mode != AuthMode.ClaudeAccount) throw ApiException.NotAvailableInMode()
        val org = creds.organizationUuid ?: throw IllegalStateException("Sign in again: the account organization is missing.")
        val attempts = mutableListOf<String>()
        for (host in workingHost?.let { listOf(it) } ?: hosts) {
            val response = try {
                call(host + path(org), creds.accessToken)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                attempts += "${hostLabel(host)}: no connection"
                continue
            }
            if (response.status in 200..299 && looksLikeJson(response)) {
                workingHost = host
                return response.bodyString
            }
            attempts += "${hostLabel(host)}: ${describe(response)}"
        }
        workingHost = null
        throw Unavailable(attempts)
    }

    private suspend fun call(url: String, token: String?): HttpResponse = http.request(
        HttpRequest(
            method = "GET",
            url = url,
            headers = mapOf(
                "Authorization" to "Bearer ${token ?: throw ApiException.NotSignedIn()}",
                "Accept" to "application/json",
                "anthropic-version" to AuthHeaders.ANTHROPIC_VERSION,
                "anthropic-beta" to AuthHeaders.BETA_OAUTH,
                "anthropic-client-platform" to AuthHeaders.CLIENT_PLATFORM,
                "User-Agent" to auth.userAgent,
            ),
        ),
    )

    companion object {
        private fun hostLabel(host: String) = host.substringAfter("://")

        private fun looksLikeJson(r: HttpResponse) = r.bodyString.trimStart().let { it.startsWith("{") || it.startsWith("[") }

        fun describe(r: HttpResponse): String = when {
            r.header("cf-mitigated") != null || r.bodyString.contains("Just a moment", ignoreCase = true) ->
                "${r.status}, blocked by Cloudflare"
            r.status in 200..299 -> "${r.status}, not JSON"
            else -> r.status.toString() + (ApiErrors.parseError(r.bodyString).second?.let { ", " + it.take(60) } ?: "")
        }

        private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

        fun parseList(body: String): List<WebChatSummary> {
            val root = CoreJson.parseToJsonElement(body)
            val items = when (root) {
                is JsonArray -> root
                is JsonObject -> (root["data"] as? JsonArray) ?: (root["conversations"] as? JsonArray) ?: JsonArray(emptyList())
                else -> JsonArray(emptyList())
            }
            return items.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val uuid = o["uuid"].str() ?: o["id"].str() ?: return@mapNotNull null
                WebChatSummary(
                    uuid = uuid,
                    name = o["name"].str()?.takeIf { it.isNotBlank() } ?: o["summary"].str()?.take(40) ?: "Untitled chat",
                    updatedAt = o["updated_at"].str(),
                    model = o["model"].str(),
                )
            }.sortedByDescending { it.updatedAt.orEmpty() }
        }

        /** Text of every message in order; tool and attachment blocks are dropped. */
        fun parseMessages(body: String): List<WebChatMessage> {
            val root = CoreJson.parseToJsonElement(body) as? JsonObject ?: return emptyList()
            val items = (root["chat_messages"] as? JsonArray) ?: (root["messages"] as? JsonArray) ?: return emptyList()
            return items.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val sender = o["sender"].str() ?: o["role"].str()
                val blocks = (o["content"] as? JsonArray)?.mapNotNull { b ->
                    val bo = b as? JsonObject
                    if (bo?.get("type").str() == "text") bo?.get("text").str() else null
                }.orEmpty()
                val text = (blocks.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: o["text"].str()).orEmpty().trim()
                if (text.isEmpty()) null
                else WebChatMessage(fromUser = sender == "human" || sender == "user", text = text, createdAt = o["created_at"].str())
            }
        }

        /**
         * A local thread seeded with the end of a claude.ai chat, so it can be read and continued
         * on the watch. Continuing does not write back to claude.ai.
         */
        fun toThread(summary: WebChatSummary, messages: List<WebChatMessage>, model: String, now: Long): ChatThread {
            val tail = messages.takeLast(ChatThreadLimits.IMPORTED_MESSAGES)
            fun at(m: WebChatMessage) = m.createdAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: now
            return ChatThread(
                id = "web-${summary.uuid}",
                title = ChatThread.titleFrom(summary.name),
                createdAt = tail.firstOrNull()?.let(::at) ?: now,
                updatedAt = now,
                model = model,
                messages = tail.map { ChatMessage(if (it.fromUser) ChatRole.User else ChatRole.Assistant, it.text, at(it)) },
            )
        }
    }
}

object ChatThreadLimits {
    /** How much of a claude.ai chat is copied to the watch (memory and context stay small). */
    const val IMPORTED_MESSAGES = 30
}
