package com.claudeforwatch.core.api

import com.claudeforwatch.core.store.ChatRole
import kotlin.test.Test
import kotlin.test.assertEquals

class WebChatsClientTest {
    @Test
    fun parsesArrayAndEnvelopeLists() {
        val arr = """[{"uuid":"a","name":"Trip","updated_at":"2026-10-01T10:00:00Z","model":"claude-opus-5-5"},
                      {"uuid":"b","name":"","summary":"Recipe ideas for dinner","updated_at":"2026-10-02T10:00:00Z"},
                      {"name":"no id"}]"""
        val list = WebChatsClient.parseList(arr)
        assertEquals(listOf("b", "a"), list.map { it.uuid })
        assertEquals("Recipe ideas for dinner", list.first().name)
        assertEquals(1, WebChatsClient.parseList("""{"data":[{"uuid":"x","name":"X"}]}""").size)
    }

    @Test
    fun parsesMessagesFromContentBlocksOrText() {
        val body = """{"uuid":"a","chat_messages":[
            {"sender":"human","text":"Hi there","created_at":"2026-10-01T10:00:00Z"},
            {"sender":"assistant","content":[{"type":"text","text":"Hello!"},{"type":"tool_use","name":"x"}]},
            {"sender":"assistant","content":[{"type":"tool_use","name":"x"}]}]}"""
        val msgs = WebChatsClient.parseMessages(body)
        assertEquals(listOf(true, false), msgs.map { it.fromUser })
        assertEquals("Hello!", msgs[1].text)
        val thread = WebChatsClient.toThread(WebChatSummary("a", "Trip", null, null), msgs, "claude-haiku-5-5", 5L)
        assertEquals("web-a", thread.id)
        assertEquals(listOf(ChatRole.User, ChatRole.Assistant), thread.messages.map { it.role })
    }
}
