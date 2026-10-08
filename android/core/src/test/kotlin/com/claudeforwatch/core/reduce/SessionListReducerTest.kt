package com.claudeforwatch.core.reduce

import com.claudeforwatch.core.CoreJson
import com.claudeforwatch.core.Spec
import com.claudeforwatch.core.model.SessionListResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionListReducerTest {
    @Test
    fun fixtureReducesToExpected() {
        val response = CoreJson.decodeFromString(SessionListResponse.serializer(), Spec.fixture("sessions_list.json"))
        val expected = Spec.expected("sessions_list.json")
        val state = SessionListReducer.reduce(response.data)

        assertEquals((expected["visibleOrder"] as JsonArray).map { it.jsonPrimitive.content }, state.rows.map { it.id })
        assertEquals((expected["hidden"] as JsonArray).map { it.jsonPrimitive.content }, state.hiddenIds)
        assertEquals(expected["needsActionCount"]!!.jsonPrimitive.int, state.needsActionCount)
        val kinds = (expected["kinds"] as JsonObject).mapValues { it.value.jsonPrimitive.content }
        assertEquals(
            kinds,
            state.rows.associate { it.id to if (it.kind == SessionKind.RemoteControl) "remoteControl" else "cloud" },
        )
        assertEquals("Logger now uses structured output; tests pass.", state.rows.last().summary)
        assertEquals("rt_fixture", response.resumeToken)
    }

    @Test
    fun showArchivedKeepsEverything() {
        val response = CoreJson.decodeFromString(SessionListResponse.serializer(), Spec.fixture("sessions_list.json"))
        val state = SessionListReducer.reduce(response.data, showArchived = true)
        assertEquals(4, state.rows.size)
        assertEquals("session_04Archived", state.rows.last().id)
    }
}
