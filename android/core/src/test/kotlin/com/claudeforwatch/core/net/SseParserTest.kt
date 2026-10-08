package com.claudeforwatch.core.net

import com.claudeforwatch.core.Spec
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SseParserTest {
    @Test
    fun parsesMessagesFixture() {
        val events = SseParser.parseAll(Spec.fixture("messages_stream.sse"))
        assertEquals(
            listOf("message_start", "content_block_start", "ping", "content_block_delta", "content_block_delta",
                "content_block_stop", "message_delta", "message_stop"),
            events.map { it.event },
        )
        assertEquals("""{"type":"ping"}""", events[2].data)
    }

    @Test
    fun everyChunkSplitYieldsTheSameEvents() {
        for (fixture in listOf("messages_stream.sse", "session_events_stream.sse", "messages_refusal.sse")) {
            val bytes = Spec.fixtureBytes(fixture)
            val whole = SseParser().feed(bytes)
            for (size in listOf(1, 2, 3, 5, 7, 13, 64)) {
                val parser = SseParser()
                val split = Spec.chunked(bytes, size).flatMap { parser.feed(it) } + parser.finish()
                assertEquals(whole, split, "$fixture split every $size bytes")
            }
        }
    }

    @Test
    fun crlfAndCrLineEndingsSplitAcrossChunks() {
        val parser = SseParser()
        val out = parser.feed("event: a\r") + parser.feed("\ndata: 1\r") + parser.feed("\n\r") + parser.feed("\n") +
            parser.feed("data: 2\r\r")
        assertEquals(listOf(SseEvent("a", "1"), SseEvent("message", "2")), out)
    }

    @Test
    fun multiLineDataCommentsIdAndRetry() {
        val text = ": keep-alive\nid: 41\nretry: 3000\nevent: client_event\ndata: line1\ndata:line2\ndata\n\n"
        val events = SseParser.parseAll(text)
        assertEquals(listOf(SseEvent("client_event", "line1\nline2\n", "41", 3000)), events)
    }

    @Test
    fun idPersistsAndEmptyDataIsNotDispatched() {
        val parser = SseParser()
        val events = parser.feed("id: 7\nevent: x\n\ndata: a\n\n")
        assertEquals(listOf(SseEvent("message", "a", "7")), events)
        assertEquals("7", parser.lastId)
    }

    @Test
    fun utf8SplitInsideAMultibyteCharacter() {
        val bytes = "data: héllo ⌚ 👋\n\n".toByteArray()
        val parser = SseParser()
        val events = bytes.map { parser.feed(byteArrayOf(it)) }.flatten()
        assertEquals("héllo ⌚ 👋", events.single().data)
    }

    @Test
    fun unterminatedFinalEventIsDiscardedAndBomStripped() {
        val parser = SseParser()
        assertEquals(listOf(SseEvent("message", "x")), parser.feed("﻿data: x\n\ndata: partial"))
        assertEquals(emptyList(), parser.finish())
        assertNull(parser.lastId)
    }

    @Test
    fun flowAdapter() = runTest {
        val chunks = Spec.chunked(Spec.fixtureBytes("messages_refusal.sse"), 9)
        val events = flowOf(*chunks.toTypedArray()).sseEvents().toList()
        assertEquals(6, events.size)
    }
}
