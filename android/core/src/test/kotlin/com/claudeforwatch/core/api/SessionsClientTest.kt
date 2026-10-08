package com.claudeforwatch.core.api

import com.claudeforwatch.core.CoreJson
import com.claudeforwatch.core.Spec
import com.claudeforwatch.core.auth.AuthMode
import com.claudeforwatch.core.auth.AuthProvider
import com.claudeforwatch.core.auth.Credentials
import com.claudeforwatch.core.auth.InMemoryTokenStore
import com.claudeforwatch.core.auth.OAuthConfig
import com.claudeforwatch.core.net.HttpResponse
import com.claudeforwatch.core.net.StubHttpClient
import com.claudeforwatch.core.reduce.TranscriptState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SessionsClientTest {
    private val base = "https://api.anthropic.com/v1/code/sessions"
    private val creds = Credentials(AuthMode.ClaudeAccount, "sk-ant-oat01-A", "r", Long.MAX_VALUE, OAuthConfig.SCOPES, "org-1")

    private fun client(http: StubHttpClient) =
        SessionsClient(ApiTransport(http, AuthProvider(InMemoryTokenStore(creds), null)), newUuid = { "uuid-1" })

    private fun json(s: String) = CoreJson.parseToJsonElement(s)

    @Test
    fun listHasNoCcrBetaButEverythingElseDoes() = runTest {
        val http = StubHttpClient()
            .on({ it.url == base }) { HttpResponse(200, body = Spec.fixtureBytes("sessions_list.json")) }
            .on({ it.url.startsWith("$base/s1/events?") }) { HttpResponse(200, body = Spec.fixtureBytes("session_events_history.json")) }
        val c = client(http)
        assertEquals(4, c.list().data.size)
        val history = c.history("s1")
        assertNull(http.requests[0].header("anthropic-beta"))
        assertEquals("web_claude_ai", http.requests[0].header("anthropic-client-platform"))
        assertEquals("org-1", http.requests[0].header("x-organization-uuid"))
        assertEquals("$base/s1/events?limit=60&sort_order=desc", http.requests[1].url)
        assertEquals("ccr-byoc-2025-07-29", http.requests[1].header("anthropic-beta"))
        assertEquals(6, TranscriptState().also { it.apply(history) }.lastSequence)
    }

    @Test
    fun streamResumesFromSequence() = runTest {
        val http = StubHttpClient().onStream("$base/s1/events/stream") { listOf(Spec.fixture("session_events_stream.sse")) }
        val frames = client(http).stream("s1", fromSequence = 6).toList()
        val req = http.requests.single()
        assertEquals("$base/s1/events/stream?from_sequence_num=6", req.url)
        assertEquals("6", req.header("Last-Event-ID"))
        assertEquals("text/event-stream", req.header("Accept"))
        assertEquals(8, frames.size)
    }

    @Test
    fun sendBodiesMatchProtocol() = runTest {
        val http = StubHttpClient().onUrl("$base/s1/", body = "{}")
        val c = client(http)
        c.sendText("s1", "Run the tests")
        c.interrupt("s1")
        c.setPermissionMode("s1", PermissionMode.AcceptEdits)
        c.setModel("s1", "claude-sonnet-5-5")
        val input = json("""{"command":"git push"}""") as JsonObject
        c.respondToPermission("s1", "req_1", allow = true, input = input)
        c.respondToPermission("s1", "req_1", allow = false, input = input)
        val bodies = http.requests.map { json(it.bodyString!!) }
        http.requests.forEach { assertEquals("$base/s1/events", it.url); assertEquals("POST", it.method) }
        assertEquals(
            json("""{"session_id":"s1","events":[{"payload":{"type":"user","uuid":"uuid-1","message":{"role":"user","content":[{"type":"text","text":"Run the tests"}]}}}]}"""),
            bodies[0],
        )
        assertEquals(json("""{"session_id":"s1","events":[{"payload":{"type":"control_request","request_id":"uuid-1","request":{"subtype":"interrupt"}}}]}"""), bodies[1])
        assertEquals(json("""{"session_id":"s1","events":[{"payload":{"type":"control_request","request_id":"uuid-1","request":{"subtype":"set_permission_mode","mode":"acceptEdits"}}}]}"""), bodies[2])
        assertEquals(json("""{"session_id":"s1","events":[{"payload":{"type":"control_request","request_id":"uuid-1","request":{"subtype":"set_model","model":"claude-sonnet-5-5"}}}]}"""), bodies[3])
        assertEquals(
            json("""{"session_id":"s1","events":[{"payload":{"type":"control_response","response":{"subtype":"success","request_id":"req_1","response":{"behavior":"allow","updatedInput":{"command":"git push"}}}}}]}"""),
            bodies[4],
        )
        assertEquals(
            json("""{"session_id":"s1","events":[{"payload":{"type":"control_response","response":{"subtype":"success","request_id":"req_1","response":{"behavior":"deny","message":"User denied from watch"}}}}]}"""),
            bodies[5],
        )
    }

    @Test
    fun archiveAccepts409AndPresenceBodies() = runTest {
        val http = StubHttpClient()
            .onUrl("$base/s1/archive", status = 409, body = "{}")
            .onUrl("$base/s1/", body = "{}")
        val c = client(http)
        c.archive("s1")
        c.markRead("s1")
        c.presence("s1", "install-1", 1000)
        c.clearPresence("s1", "install-1")
        assertEquals(listOf("$base/s1/archive", "$base/s1/mark_read", "$base/s1/client/presence", "$base/s1/client/presence"), http.requests.map { it.url })
        assertEquals(json("""{"client_id":"install-1","connected_at":1000}"""), json(http.requests[2].bodyString!!))
        assertEquals(json("""{"client_id":"install-1","clear":true}"""), json(http.requests[3].bodyString!!))
    }

    @Test
    fun trustedDevice403IsExplained() = runTest {
        val http = StubHttpClient().onUrl(base, status = 403, body = """{"type":"error","error":{"type":"permission_error","message":"Trusted device required"}}""")
        val e = assertFailsWith<ApiException.TrustedDeviceRequired> { client(http).list() }
        assertEquals("This organization requires Trusted Devices; enroll from claude.ai", e.message)
    }

    @Test
    fun apiKeyCannotReachSessions() = runTest {
        val http = StubHttpClient()
        val c = SessionsClient(ApiTransport(http, AuthProvider(InMemoryTokenStore(Credentials.forApiKey("k")), null)))
        assertFailsWith<ApiException.NotAvailableInMode> { c.list() }
        assertEquals(0, http.requests.size)
    }
}
