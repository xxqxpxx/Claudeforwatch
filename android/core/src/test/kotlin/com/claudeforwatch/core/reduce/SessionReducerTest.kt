package com.claudeforwatch.core.reduce

import com.claudeforwatch.core.CoreJson
import com.claudeforwatch.core.Spec
import com.claudeforwatch.core.model.SessionEventEnvelope
import com.claudeforwatch.core.model.SessionEventsResponse
import com.claudeforwatch.core.model.SessionPayload
import com.claudeforwatch.core.net.SseParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionReducerTest {
    private fun history(): List<SessionEventEnvelope> =
        CoreJson.decodeFromString(SessionEventsResponse.serializer(), Spec.fixture("session_events_history.json")).data

    @Test
    fun historyReducesToExpected() {
        val expected = Spec.expected("session_events_history.json")
        val state = TranscriptState()
        state.apply(history())
        assertEquals(expected["items"] as JsonArray, JsonArray(state.items.map(Spec::itemJson)))
        assertEquals(expected["lastSequence"]!!.jsonPrimitive.long, state.lastSequence)
        assertNull(state.streamingText)
        assertNull(state.pendingPermission)
    }

    @Test
    fun streamReducesToExpected() {
        val expected = Spec.expected("session_events_stream.json")
        val frames = SseParser.parseAll(Spec.fixture("session_events_stream.sse"))
        val state = TranscriptState()
        var streamingBefore10: String? = null
        for (frame in frames) {
            if (frame.id == "10" && frame.event == "client_event") streamingBefore10 = state.streamingText
            state.apply(frame)
        }
        assertEquals(expected["items"] as JsonArray, JsonArray(state.items.map(Spec::itemJson)))
        assertEquals(expected["streamingTextBeforeSeq10"]!!.jsonPrimitive.content, streamingBefore10)
        assertNull(state.streamingText, "the final assistant payload replaced the streamed text")
        assertEquals(expected["lastSequence"]!!.jsonPrimitive.long, state.lastSequence)
        assertEquals((expected["ignoredFrames"] as JsonArray).map { it.jsonPrimitive.content }, state.ignoredFrames)
        assertEquals(listOf(expected["duplicateSequenceDropped"]!!.jsonPrimitive.long), state.droppedDuplicates)

        val pending = assertNotNull(state.pendingPermission)
        val exp = expected["pendingPermission"] as JsonObject
        assertEquals(exp["requestId"]!!.jsonPrimitive.content, pending.requestId)
        assertEquals(exp["tool"]!!.jsonPrimitive.content, pending.tool)
        assertEquals(exp["summary"]!!.jsonPrimitive.content, pending.summary)
        assertEquals("Push the branch", pending.description)
    }

    @Test
    fun historyThenStreamIsChronologicalAndDeduped() {
        val state = TranscriptState()
        state.apply(history())
        SseParser.parseAll(Spec.fixture("session_events_stream.sse")).forEach { state.apply(it) }
        val seqs = state.items.map { it.seq }
        assertEquals(seqs.sorted(), seqs)
        assertEquals(10, state.items.size)
        // Re-applying history after the stream is a no-op (everything ≤ lastSequence).
        state.apply(history())
        assertEquals(10, state.items.size)
    }

    @Test
    fun resultOrControlResponseClearsPendingPermission() {
        val frames = SseParser.parseAll(Spec.fixture("session_events_stream.sse"))
        val a = TranscriptState().apply { frames.forEach { apply(it) } }
        a.apply(envelope(13, """{"type":"control_response","response":{"subtype":"success","request_id":"req_fixture_1","response":{"behavior":"allow"}}}"""))
        assertNull(a.pendingPermission)

        val b = TranscriptState().apply { frames.forEach { apply(it) } }
        b.apply(envelope(13, """{"type":"result","subtype":"error_during_execution","is_error":true}"""))
        assertNull(b.pendingPermission)
        val end = assertIs<TranscriptItem.TurnEnd>(b.items.last())
        assertTrue(end.isError)

        val c = TranscriptState().apply { frames.forEach { apply(it) } }
        c.resolvePermission("req_fixture_1")
        assertNull(c.pendingPermission)
    }

    @Test
    fun unknownPayloadsAndBlocksAreTolerated() {
        val state = TranscriptState()
        state.apply(envelope(1, """{"type":"rate_limit_event","info":{}}"""))
        state.apply(envelope(2, """{"type":"assistant","message":{"content":[{"type":"server_tool_use","id":"x"},{"type":"text","text":"ok"}]}}"""))
        state.apply(envelope(3, """{"type":"user","message":{"role":"user","content":"plain string content"}}"""))
        state.apply(envelope(4, """{"type":"system","subtype":"compact_boundary"}"""))
        state.apply(envelope(5, """{"type":"control_request","request_id":"r","request":{"subtype":"hook_callback"}}"""))
        assertEquals(
            listOf("ok", "plain string content", "— context compacted —"),
            state.items.map { (Spec.itemJson(it) as JsonObject)["text"]!!.jsonPrimitive.content },
        )
        assertNull(state.pendingPermission)
        assertEquals(5, state.lastSequence)
        assertIs<SessionPayload.Unknown>(SessionEventEnvelope.decode("""{"sequence_num":"9","payload":{"type":"new_thing"}}""").payload)
    }

    @Test
    fun askUserQuestionBecomesQuestionPermission() {
        val state = TranscriptState()
        state.apply(
            envelope(
                1,
                """{"type":"control_request","request_id":"q1","request":{"subtype":"can_use_tool","tool_name":"AskUserQuestion",
                   "input":{"questions":[{"question":"Which DB?","header":"DB","options":[{"label":"Postgres","description":"x"},{"label":"SQLite"}],"multiSelect":false}]}}}""",
            ),
        )
        val p = assertNotNull(state.pendingPermission)
        assertTrue(p.isQuestion)
        assertEquals("Which DB?", p.summary)
        assertEquals(listOf("Postgres", "SQLite"), p.questions.single().options)
    }

    @Test
    fun catchUpTruncatedRequestsRefetchAndNonClientFramesAreIgnored() {
        val state = TranscriptState()
        state.apply(com.claudeforwatch.core.net.SseEvent("delivery_update", "{}", "1"))
        state.apply(com.claudeforwatch.core.net.SseEvent("ephemeral_event", "{}", "2"))
        assertEquals(listOf("delivery_update", "ephemeral_event"), state.ignoredFrames)
        assertNull(state.lastSequence)
        state.apply(com.claudeforwatch.core.net.SseEvent("catch_up_truncated", "{}"))
        assertTrue(state.needsRefetch)
    }

    @Test
    fun toolSummaryRules() {
        fun obj(json: String) = CoreJson.parseToJsonElement(json) as JsonObject
        assertEquals("npm test", ToolSummary.of("Bash", obj("""{"description":"d","command":"npm test"}""")))
        assertEquals("/a/b.kt", ToolSummary.of("Edit", obj("""{"old_string":"x","file_path":"/a/b.kt"}""")))
        assertEquals("/a/c.kt", ToolSummary.of("Write", obj("""{"content":"x","file_path":"/a/c.kt"}""")))
        assertEquals("/a/d.kt", ToolSummary.of("Read", obj("""{"file_path":"/a/d.kt","limit":5}""")))
        assertEquals("TODO", ToolSummary.of("Grep", obj("""{"limit":3,"pattern":"TODO","path":"src"}""")))
        assertEquals("line one", ToolSummary.of("Bash", obj("""{"command":"\n line one\nline two"}""")))
        assertEquals(ToolSummary.MAX_LENGTH, ToolSummary.of("Bash", obj("""{"command":"${"x".repeat(300)}"}""")).length)
        assertEquals("", ToolSummary.of("Task", obj("{}")))
    }

    private fun envelope(seq: Long, payload: String) =
        SessionEventEnvelope.decode("""{"sequence_num":"$seq","payload":$payload}""")
}
