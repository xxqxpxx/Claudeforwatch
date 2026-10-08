package com.claudeforwatch.core.net

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** One dispatched Server-Sent Event. [event] defaults to "message" per the SSE spec. */
data class SseEvent(
    val event: String = "message",
    val data: String,
    val id: String? = null,
    val retry: Long? = null,
)

/**
 * Pure, incremental Server-Sent Events parser (WHATWG HTML §9.2.6 line rules).
 *
 * Feed it arbitrary chunks (bytes or text); it handles CRLF / LF / CR line endings,
 * line endings split across chunks, UTF-8 sequences split across chunks, `:` comments,
 * multi-line `data:` joins, `id:` (persisting as last-event-id) and `retry:`.
 */
class SseParser {
    private val line = StringBuilder()
    private val data = StringBuilder()
    private var hasData = false
    private var eventType: String? = null
    private var lastEventId: String? = null
    private var retry: Long? = null
    private var pendingCr = false
    private var sawFirstChar = false

    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var leftover = ByteArray(0)

    /** The id of the last dispatched (or seen) event, for `Last-Event-ID` on reconnect. */
    val lastId: String? get() = lastEventId

    fun feed(bytes: ByteArray): List<SseEvent> {
        val input = if (leftover.isEmpty()) bytes else leftover + bytes
        val inBuf = ByteBuffer.wrap(input)
        val outBuf = CharBuffer.allocate(input.size + 1)
        decoder.decode(inBuf, outBuf, false)
        leftover = if (inBuf.hasRemaining()) ByteArray(inBuf.remaining()).also { inBuf.get(it) } else ByteArray(0)
        outBuf.flip()
        return feed(outBuf.toString())
    }

    fun feed(text: String): List<SseEvent> {
        val out = mutableListOf<SseEvent>()
        for (ch in text) {
            if (!sawFirstChar) {
                sawFirstChar = true
                if (ch == '﻿') continue
            }
            if (pendingCr) {
                pendingCr = false
                if (ch == '\n') continue // second half of a CRLF split anywhere
            }
            when (ch) {
                '\r' -> { pendingCr = true; endLine(out) }
                '\n' -> endLine(out)
                else -> line.append(ch)
            }
        }
        return out
    }

    /** Call at end of stream. Per spec an unterminated final event is discarded. */
    fun finish(): List<SseEvent> {
        leftover = ByteArray(0)
        line.setLength(0)
        resetEvent()
        return emptyList()
    }

    private fun endLine(out: MutableList<SseEvent>) {
        val l = line.toString()
        line.setLength(0)
        if (l.isEmpty()) {
            dispatch(out)
            return
        }
        if (l[0] == ':') return // comment / keep-alive
        val colon = l.indexOf(':')
        val field: String
        var value: String
        if (colon >= 0) {
            field = l.substring(0, colon)
            value = l.substring(colon + 1)
            if (value.startsWith(' ')) value = value.substring(1)
        } else {
            field = l
            value = ""
        }
        when (field) {
            "event" -> eventType = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            "id" -> if (!value.contains('\u0000')) lastEventId = value
            "retry" -> value.toLongOrNull()?.let { retry = it }
            else -> Unit // unknown field: ignored
        }
    }

    private fun dispatch(out: MutableList<SseEvent>) {
        if (!hasData) {
            resetEvent()
            return
        }
        out += SseEvent(
            event = eventType?.takeIf { it.isNotEmpty() } ?: "message",
            data = data.toString(),
            id = lastEventId,
            retry = retry,
        )
        resetEvent()
    }

    private fun resetEvent() {
        data.setLength(0)
        hasData = false
        eventType = null
        retry = null
    }

    companion object {
        /** Parses a complete SSE document (tests, fixtures). */
        fun parseAll(text: String): List<SseEvent> = SseParser().run { feed(text) + finish() }
    }
}

/** Turns a byte-chunk flow into SSE events. */
fun Flow<ByteArray>.sseEvents(): Flow<SseEvent> = flow {
    val parser = SseParser()
    collect { chunk -> parser.feed(chunk).forEach { emit(it) } }
    parser.finish().forEach { emit(it) }
}
