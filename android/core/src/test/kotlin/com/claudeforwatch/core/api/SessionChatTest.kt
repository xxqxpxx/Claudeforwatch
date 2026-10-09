package com.claudeforwatch.core.api

import com.claudeforwatch.core.auth.AuthProvider
import com.claudeforwatch.core.auth.Credentials
import com.claudeforwatch.core.auth.AuthMode
import com.claudeforwatch.core.auth.InMemoryTokenStore
import com.claudeforwatch.core.net.StubHttpClient
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionChatTest {
    private fun frame(seq: Int, payload: String) =
        "event: client_event\nid: $seq\ndata: {\"event_type\":\"client_event\",\"sequence_num\":\"$seq\",\"payload\":$payload}\n\n"

    private fun client(streamBody: String, connection: String = "connected"): Pair<SessionChat, StubHttpClient> {
        val base = "https://api.anthropic.com/v1/code/sessions/cse_1"
        val http = StubHttpClient()
            .onUrl("$base/events?limit=1", body = """{"data":[{"sequence_num":"40","payload":{"type":"result","subtype":"success","is_error":false}}]}""")
            .on({ it.method == "GET" && it.url == base }) {
                com.claudeforwatch.core.net.HttpResponse(200, emptyMap(), """{"id":"cse_1","status":"active","environment_kind":"bridge","connection_status":"$connection"}""".toByteArray())
            }
            .onUrl("$base/events?limit=1", body = """{"data":[{"sequence_num":"40","payload":{"type":"result","subtype":"success","is_error":false}}]}""")
            .onStream("$base/events/stream") { listOf(streamBody) }
            .on({ it.method == "POST" && it.url == "$base/events" }) { com.claudeforwatch.core.net.HttpResponse(200, emptyMap(), "{}".toByteArray()) }
        val auth = AuthProvider(
            InMemoryTokenStore(Credentials(AuthMode.ClaudeAccount, accessToken = "t", scopes = listOf(Credentials.SCOPE_SESSIONS), organizationUuid = "o")),
            oauth = null,
        )
        return SessionChat(SessionsClient(ApiTransport(http, auth))) to http
    }

    @Test
    fun streamsTheReplyToOurTurnOnly() = runBlocking {
        val q = "What's the capital of France?"
        val stream = frame(41, """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Earlier work, not ours."}]}}""") +
            frame(42, """{"type":"result","subtype":"success","is_error":false}""") +
            frame(43, """{"type":"user","message":{"role":"user","content":[{"type":"text","text":${kotlinx.serialization.json.JsonPrimitive(SessionChat.wrap(q))}}]}}""") +
            frame(44, """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Paris"}}}""") +
            frame(45, """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Paris."}]}}""") +
            frame(46, """{"type":"result","subtype":"success","is_error":false}""") +
            frame(47, """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Never read."}]}}""")
        val (chat, http) = client(stream)
        val events = chat.ask("cse_1", q).toList()
        val reduced = MessagesReduction.of(events)
        assertEquals("Paris.", reduced.text)
        assertEquals("end_turn", reduced.stopReason)
        val sent = http.requests.first { it.method == "POST" }.bodyString.orEmpty()
        assertTrue(sent.contains("Sent from my watch") && sent.contains("capital of France"))
        assertTrue(http.requests.any { it.url.contains("from_sequence_num=40") })
    }

    @Test
    fun permissionPromptEndsWithApprovalMessage() = runBlocking {
        val q = "Run the tests"
        val stream = frame(41, """{"type":"user","message":{"role":"user","content":[{"type":"text","text":${kotlinx.serialization.json.JsonPrimitive(SessionChat.wrap(q))}}]}}""") +
            frame(42, """{"type":"control_request","request_id":"req_1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"npm test"}}}""")
        val events = client(stream).first.ask("cse_1", q).toList()
        assertEquals(SessionChat.NEEDS_APPROVAL, MessagesReduction.of(events).errorType)
    }

    @Test
    fun offlineComputerSessionFailsFastWithoutSending() = runBlocking {
        val (chat, http) = client("", connection = "disconnected")
        val error = runCatching { chat.ask("cse_1", "hi").toList() }.exceptionOrNull()
        assertTrue(error is IllegalStateException && error.message!!.contains("offline"))
        assertTrue(http.requests.none { it.method == "POST" })
    }
}
