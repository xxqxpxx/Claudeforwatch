package com.claudeforwatch.core.api

import com.claudeforwatch.core.CoreJson
import com.claudeforwatch.core.Spec
import com.claudeforwatch.core.reduce.SessionListReducer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlin.test.Test
import kotlin.test.assertEquals

/** spec/fixtures/sessions_list_drift.json: the shape that broke the list on a real watch. */
class SessionListDriftTest {
    private val expected = Spec.expected("sessions_list_drift.json")

    @Test
    fun objectSummaryAndBadItemDoNotBreakTheList() {
        val response = SessionsClient.decodeList(Spec.fixture("sessions_list_drift.json"))
        assertEquals(expected["skipped"]!!.jsonPrimitive.int, response.skipped)
        val order = expected["visibleOrder"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(order, SessionListReducer.reduce(response.data).rows.map { it.id })
        val summaries = expected["summaries"] as JsonObject
        response.data.forEach { s ->
            assertEquals(summaries[s.id]!!.jsonPrimitive.content, s.externalMetadata?.summaryText)
        }
        val needs = expected["needsActionText"] as JsonObject
        assertEquals(needs["cse_10Drift"]!!.jsonPrimitive.content, response.data.first().externalMetadata?.needsActionText)
    }

    @Test
    fun rateLimitCopyUsesServerTextAndReset() {
        val e = ApiErrors.from(
            429,
            { name -> mapOf("retry-after" to "30")[name] },
            """{"type":"error","error":{"type":"rate_limit_error","message":"This request would exceed your account's rate limit"}}""",
            null,
        )
        assertEquals("This request would exceed your account's rate limit. Try again in 30s.", e.message)
        val generic = ApiErrors.from(429, { null }, """{"type":"error","error":{"type":"rate_limit_error","message":"Error"}}""", null)
        assertEquals("Rate limited. Try again soon.", generic.message)
    }
}
