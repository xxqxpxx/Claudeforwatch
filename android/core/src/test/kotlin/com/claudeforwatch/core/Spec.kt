package com.claudeforwatch.core

import com.claudeforwatch.core.reduce.TranscriptItem
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Locates the shared `spec/` directory (repo root) for fixture-driven tests. */
object Spec {
    val dir: File by lazy {
        System.getProperty("cfw.specDir")?.let(::File)?.takeIf { it.isDirectory }
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .map { File(it, "spec") }
                .firstOrNull { File(it, "fixtures").isDirectory }
            ?: error("spec/ directory not found")
    }

    fun fixture(name: String): String = File(dir, "fixtures/$name").readText()
    fun fixtureBytes(name: String): ByteArray = File(dir, "fixtures/$name").readBytes()
    fun expected(name: String): JsonObject = CoreJson.parseToJsonElement(File(dir, "expected/$name").readText()) as JsonObject

    /** The spec/expected item shape for a transcript item. */
    fun itemJson(item: TranscriptItem): JsonElement = buildJsonObject {
        put("seq", item.seq)
        when (item) {
            is TranscriptItem.System -> { put("kind", "system"); put("text", item.text) }
            is TranscriptItem.User -> { put("kind", "user"); put("text", item.text) }
            is TranscriptItem.Assistant -> { put("kind", "assistant"); put("text", item.text) }
            is TranscriptItem.Tool -> { put("kind", "tool"); put("tool", item.tool); put("text", item.summary) }
            is TranscriptItem.TurnEnd -> { put("kind", "turnEnd"); put("isError", item.isError) }
            is TranscriptItem.Permission -> {
                put("kind", "permission"); put("requestId", item.requestId); put("tool", item.tool); put("text", item.summary)
            }
        }
    }

    /** Splits [text] into chunks of [size] bytes (deliberately cutting lines, CRLFs and UTF-8). */
    fun chunked(bytes: ByteArray, size: Int): List<ByteArray> =
        (bytes.indices step size).map { bytes.copyOfRange(it, minOf(it + size, bytes.size)) }
}
