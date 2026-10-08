package com.claudeforwatch.core.api

import com.claudeforwatch.core.CoreJson
import com.claudeforwatch.core.Spec
import com.claudeforwatch.core.auth.AuthMode
import com.claudeforwatch.core.auth.AuthProvider
import com.claudeforwatch.core.auth.Credentials
import com.claudeforwatch.core.auth.InMemoryTokenStore
import com.claudeforwatch.core.auth.OAuthConfig
import com.claudeforwatch.core.net.HttpResponse
import com.claudeforwatch.core.net.SseParser
import com.claudeforwatch.core.net.StubHttpClient
import com.claudeforwatch.core.store.ChatMessage
import com.claudeforwatch.core.store.ChatRole
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessagesClientTest {
    private val url = "https://api.anthropic.com/v1/messages"

    private fun reduceFixture(name: String) =
        MessagesReduction.of(SseParser.parseAll(Spec.fixture(name)).mapNotNull(MessagesClient::decode))

    private fun assertMatchesExpected(r: MessagesReduction, expectedName: String) {
        val expected = Spec.expected(expectedName)
        expected["text"]?.let { assertEquals(it.jsonPrimitive.content, r.text) }
        expected["stopReason"]?.let { assertEquals(it.jsonPrimitive.content, r.stopReason) }
        expected["outputTokens"]?.let { assertEquals(it.jsonPrimitive.int, r.outputTokens) }
        expected["model"]?.let { assertEquals(it.jsonPrimitive.content, r.model) }
        expected["errorType"]?.let { assertEquals(it.jsonPrimitive.content, r.errorType) }
        expected["errorMessage"]?.let { assertEquals(it.jsonPrimitive.content, r.errorMessage) }
    }

    @Test
    fun fixturesReduceToExpected() {
        assertMatchesExpected(reduceFixture("messages_stream.sse"), "messages_stream.json")
        val refusal = reduceFixture("messages_refusal.sse")
        assertMatchesExpected(refusal, "messages_refusal.json")
        assertTrue(refusal.isRefusal)
        assertMatchesExpected(reduceFixture("messages_error.sse"), "messages_error.json")
    }

    @Test
    fun maxTokensAppendsEllipsis() {
        assertEquals("Hi…", MessagesReduction(text = "Hi", stopReason = "max_tokens").displayText)
    }

    @Test
    fun systemPromptPerMode() {
        assertEquals(WATCH_SYSTEM_PROMPT, MessagesClient.systemPrompt(AuthMode.ApiKey))
        val account = MessagesClient.systemPrompt(AuthMode.ClaudeAccount)
        assertTrue(account.startsWith("You are Claude Code, Anthropic's official CLI for Claude.\n"))
        assertEquals("$CLAUDE_CODE_IDENTITY_LINE\n$WATCH_SYSTEM_PROMPT", account)
        assertFalse(WATCH_SYSTEM_PROMPT.contains("Claude Code"))
    }

    @Test
    fun requestBodyShapeAndHistoryTrim() {
        val history = (1..25).map { i ->
            ChatMessage(if (i % 2 == 1) ChatRole.User else ChatRole.Assistant, "m$i", i.toLong())
        } // ends with user m25
        val body = MessagesClient.buildRequestBody(history, ClaudeModel.Default, Effort.Low, AuthMode.ApiKey)
        assertEquals("claude-haiku-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(400, body["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(true, body["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("low", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse("thinking" in body)
        val messages = body["messages"] as JsonArray
        assertTrue(messages.size <= 20)
        val first = messages.first().jsonObject
        val last = messages.last().jsonObject
        assertEquals("user", first["role"]!!.jsonPrimitive.content, "never starts with assistant")
        assertEquals("user", last["role"]!!.jsonPrimitive.content, "never sends assistant prefill")
        assertEquals("m25", last["content"]!!.jsonPrimitive.content)
        assertEquals("m7", first["content"]!!.jsonPrimitive.content) // last 20 = m6..m25, leading assistant dropped
        assertEquals(listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5"), ClaudeModel.entries.map { it.id })
    }

    @Test
    fun streamsOverApiKeyWithSplitChunks() = runTest {
        val bytes = Spec.fixtureBytes("messages_stream.sse")
        val http = StubHttpClient().onStream(url) { Spec.chunked(bytes, 11).map { String(it, Charsets.ISO_8859_1) } }
        // ISO-8859-1 round-trip keeps bytes intact for the stub; fixture is ASCII anyway.
        val auth = AuthProvider(InMemoryTokenStore(Credentials.forApiKey("sk-ant-api03-K")), null)
        val client = MessagesClient(ApiTransport(http, auth))
        val events = client.stream(listOf(ChatMessage(ChatRole.User, "hi", 0))).toList()
        assertMatchesExpected(MessagesReduction.of(events), "messages_stream.json")

        val req = http.requests.single()
        assertEquals("sk-ant-api03-K", req.header("x-api-key"))
        assertEquals("text/event-stream", req.header("Accept"))
        val body = CoreJson.parseToJsonElement(req.bodyString!!) as JsonObject
        assertEquals(WATCH_SYSTEM_PROMPT, (body["system"] as JsonPrimitive).content)
    }

    @Test
    fun claudeAccountUsesIdentityPromptAndBetas() = runTest {
        val http = StubHttpClient().onStream(url) { listOf(Spec.fixture("messages_stream.sse")) }
        val creds = Credentials(AuthMode.ClaudeAccount, "sk-ant-oat01-A", "r", Long.MAX_VALUE, OAuthConfig.SCOPES, "org")
        val client = MessagesClient(ApiTransport(http, AuthProvider(InMemoryTokenStore(creds), null)))
        client.stream(listOf(ChatMessage(ChatRole.User, "hi", 0)), ClaudeModel.Sonnet55).toList()
        val req = http.requests.single()
        assertEquals("oauth-2025-04-20,claude-code-20250219", req.header("anthropic-beta"))
        val body = CoreJson.parseToJsonElement(req.bodyString!!) as JsonObject
        assertTrue((body["system"] as JsonPrimitive).content.startsWith(CLAUDE_CODE_IDENTITY_LINE + "\n"))
        assertEquals("claude-sonnet-5-5", body["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun httpErrorsMapToUserFacingExceptionsWithoutRetry() = runTest {
        suspend fun failWith(status: Int, body: String, headers: Map<String, String> = emptyMap()): ApiException {
            val http = StubHttpClient().on({ true }) { HttpResponse(status, headers, body.toByteArray()) }
            val creds = Credentials.forApiKey("k")
            val client = MessagesClient(ApiTransport(http, AuthProvider(InMemoryTokenStore(creds), null)))
            val e = assertFailsWith<ApiException> { client.stream(listOf(ChatMessage(ChatRole.User, "hi", 0))).toList() }
            assertEquals(1, http.requests.size, "completion requests are never retried")
            return e
        }
        val limited = failWith(429, """{"type":"error","error":{"type":"rate_limit_error","message":"slow"}}""", mapOf("retry-after" to "12"))
        assertEquals(12L, (limited as ApiException.RateLimited).retryAfterSeconds)
        assertTrue(failWith(529, """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""") is ApiException.Overloaded)
        assertTrue(failWith(401, "{}") is ApiException.InvalidApiKey)
        assertTrue(
            failWith(400, """{"type":"error","error":{"type":"invalid_request_error","message":"This credential is only authorized for use with Claude Code and cannot be used for other API requests."}}""")
                is ApiException.ClaudeCodeOnly,
        )
    }
}
