package com.claudeforwatch.core.api

import com.claudeforwatch.core.CoreInfo
import com.claudeforwatch.core.CoreJson
import com.claudeforwatch.core.auth.AuthHeaders
import com.claudeforwatch.core.model.RoutineFireResult
import com.claudeforwatch.core.net.HttpClient
import com.claudeforwatch.core.net.HttpRequest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/**
 * Fires an API-triggered routine (docs/PROTOCOL.md §6, official, experimental). Uses the
 * per-routine token, independent of the signed-in mode. Never retried (30 fires/hour cap).
 */
class RoutinesClient(
    private val http: HttpClient,
    private val baseUrl: String = ApiTransport.API_BASE,
    private val userAgent: String = CoreInfo.userAgent(),
) {
    suspend fun fire(triggerId: String, routineToken: String, text: String): RoutineFireResult {
        require(isValidTriggerId(triggerId)) { "Routine id must start with trig_" }
        val request = HttpRequest.post(
            "$baseUrl/v1/claude_code/routines/${java.net.URLEncoder.encode(triggerId.trim(), "UTF-8")}/fire",
            headers(routineToken),
            buildJsonObject { put("text", text) }.toString(),
        )
        val response = try {
            http.request(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw ApiException.Network(e)
        }
        if (!response.isSuccessful) {
            throw ApiErrors.from(response.status, response.header("retry-after"), response.bodyString, null)
        }
        return CoreJson.decodeFromString(RoutineFireResult.serializer(), response.bodyString)
    }

    fun headers(routineToken: String): Map<String, String> = linkedMapOf(
        "anthropic-version" to AuthHeaders.ANTHROPIC_VERSION,
        "Content-Type" to "application/json",
        "User-Agent" to userAgent,
        "Authorization" to "Bearer ${routineToken.trim()}",
    )

    companion object {
        fun isValidTriggerId(id: String) = id.trim().startsWith("trig_") && id.trim().length > 5
    }
}
