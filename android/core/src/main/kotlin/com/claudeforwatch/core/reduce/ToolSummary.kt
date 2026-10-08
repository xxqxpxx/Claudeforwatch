package com.claudeforwatch.core.reduce

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One-line summary of a tool call's input (docs/PROTOCOL.md §5.3). */
object ToolSummary {
    const val MAX_LENGTH = 120

    fun of(toolName: String, input: JsonObject): String {
        val raw = when (toolName) {
            "Bash" -> input.string("command")
            "Edit", "Write", "Read" -> input.string("file_path")
            "AskUserQuestion" -> askQuestionSummary(input)
            else -> null
        } ?: firstString(input)
        return oneLine(raw ?: "")
    }

    fun oneLine(text: String): String {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
        return if (line.length <= MAX_LENGTH) line else line.take(MAX_LENGTH - 1).trimEnd() + "…"
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun firstString(input: JsonObject): String? =
        input.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

    private fun askQuestionSummary(input: JsonObject): String? {
        val questions = input["questions"] as? kotlinx.serialization.json.JsonArray ?: return firstString(input)
        val first = questions.firstOrNull() as? JsonObject ?: return null
        return first.string("question") ?: first.string("header")
    }
}
