package com.claudeforwatch.core.api

import com.claudeforwatch.core.auth.AuthMode
import com.claudeforwatch.core.auth.AuthProvider
import com.claudeforwatch.core.auth.Credentials
import com.claudeforwatch.core.auth.InMemoryTokenStore
import com.claudeforwatch.core.net.StubHttpClient
import com.claudeforwatch.core.store.ChatMessage
import com.claudeforwatch.core.store.ChatRole
import com.claudeforwatch.core.store.ChatThread
import com.claudeforwatch.core.store.InMemoryThreadStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OtherClientsTest {
    @Test
    fun usageParsesWindowsWithOauthBeta() = runTest {
        val http = StubHttpClient().onUrl(
            "https://api.anthropic.com/api/oauth/usage",
            body = """{"five_hour":{"utilization":42.5,"resets_at":"2026-10-08T12:00:00Z"},"seven_day":{"utilization":7,"resets_at":null},"extra":1}""",
        )
        val creds = Credentials(AuthMode.ClaudeAccount, "a", "r", Long.MAX_VALUE)
        val usage = UsageClient(ApiTransport(http, AuthProvider(InMemoryTokenStore(creds), null))).usage()
        assertEquals(42.5, usage.fiveHour?.utilization)
        assertEquals(7.0, usage.sevenDay?.utilization)
        assertEquals("oauth-2025-04-20", http.requests.single().header("anthropic-beta"))
    }

    @Test
    fun routineFireUsesRoutineToken() = runTest {
        val http = StubHttpClient().onUrl(
            "https://api.anthropic.com/v1/claude_code/routines/trig_123/fire",
            body = """{"claude_code_session_id":"session_9","claude_code_session_url":"https://claude.ai/code/session_9"}""",
        )
        val result = RoutinesClient(http).fire("trig_123", "sk-ant-oat01-ROUTINE", "fix the build")
        assertEquals("session_9", result.sessionId)
        val req = http.requests.single()
        assertEquals("Bearer sk-ant-oat01-ROUTINE", req.header("Authorization"))
        assertEquals("2023-06-01", req.header("anthropic-version"))
        assertEquals("""{"text":"fix the build"}""", req.bodyString)
        assertFailsWith<IllegalArgumentException> { RoutinesClient(http).fire("bad", "t", "x") }
    }

    @Test
    fun threadModelAndStore() = runTest {
        val t0 = ChatThread.new("claude-haiku-5-5", now = 1, id = "t")
        val t1 = t0.appending(ChatMessage(ChatRole.User, "  What is the   tallest mountain in the solar system, and how tall?", 2))
        assertEquals("What is the tallest mountain in the sola", t1.title)
        assertEquals(40, t1.title.length)
        val t2 = t1.appending(ChatMessage(ChatRole.Assistant, "", 3)).replacingLast(ChatMessage(ChatRole.Assistant, "Olympus Mons.", 4, "end_turn"))
        assertEquals(2, t2.messages.size)
        assertEquals(4, t2.updatedAt)
        assertEquals(listOf(t2), ChatThread.decodeAll(ChatThread.encodeAll(listOf(t2))))

        val store = InMemoryThreadStore()
        store.upsert(t2)
        store.upsert(ChatThread.new("m", now = 10, id = "newer"))
        assertEquals(listOf("newer", "t"), store.threads.first().map { it.id })
        store.delete("t")
        assertEquals(listOf("newer"), store.threads.first().map { it.id })
    }
}
