package com.claudeforwatch.core.api

import com.claudeforwatch.core.auth.AuthHeaders
import com.claudeforwatch.core.auth.AuthMode
import com.claudeforwatch.core.auth.AuthProvider
import com.claudeforwatch.core.auth.Endpoint
import com.claudeforwatch.core.net.HttpClient
import com.claudeforwatch.core.net.HttpRequest
import com.claudeforwatch.core.net.HttpResponse
import com.claudeforwatch.core.net.HttpStream
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Authorized requests with the PROTOCOL rules: refresh once on 401 (claudeAccount only), map
 * errors to [ApiException], never retry anything else (a completion may already be billed).
 */
class ApiTransport(
    val http: HttpClient,
    val auth: AuthProvider,
    /**
     * Receives one line per failed request: status, method, path, error type and message,
     * request-id and rate-limit headers. Never tokens, keys or bodies beyond the error text.
     */
    private val diagnostics: (String) -> Unit = {},
) {

    suspend fun send(
        endpoint: Endpoint,
        method: String,
        url: String,
        body: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        acceptStatus: (Int) -> Boolean = { it in 200..299 },
    ): HttpResponse {
        var creds = auth.validCredentials()
        var response = transport { http.request(build(endpoint, creds, method, url, body, extraHeaders)) }
        if (response.status == 401 && AuthHeaders.usesOAuth(endpoint, creds)) {
            creds = auth.refresh(staleAccessToken = creds.accessToken)
            response = transport { http.request(build(endpoint, creds, method, url, body, extraHeaders)) }
        }
        if (!acceptStatus(response.status)) {
            report(method, url, response.status, response.bodyString, response::header)
            throw ApiErrors.from(response.status, response::header, response.bodyString, AuthHeaders.effectiveMode(endpoint, creds))
        }
        return response
    }

    suspend fun stream(
        endpoint: Endpoint,
        method: String,
        url: String,
        body: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpStream {
        var creds = auth.validCredentials()
        val headers = extraHeaders + ("Accept" to "text/event-stream")
        var stream = transport { http.stream(build(endpoint, creds, method, url, body, headers)) }
        if (stream.status == 401 && AuthHeaders.usesOAuth(endpoint, creds)) {
            runCatching { stream.readText() } // drain/close the rejected response
            creds = auth.refresh(staleAccessToken = creds.accessToken)
            stream = transport { http.stream(build(endpoint, creds, method, url, body, headers)) }
        }
        if (!stream.isSuccessful) {
            val text = runCatching { stream.readText() }.getOrDefault("")
            report(method, url, stream.status, text, stream::header)
            throw ApiErrors.from(stream.status, stream::header, text, AuthHeaders.effectiveMode(endpoint, creds))
        }
        return stream
    }

    private fun report(method: String, url: String, status: Int, body: String, header: (String) -> String?) {
        runCatching {
            val (type, message) = ApiErrors.parseError(body)
            val path = url.substringAfter("://").substringAfter('/', "").substringBefore('?')
            val extras = ApiErrors.RATE_LIMIT_HEADERS.mapNotNull { name -> header(name)?.let { "$name=$it" } }
            diagnostics("HTTP $status $method /$path type=$type message=${message?.take(200)} ${extras.joinToString(" ")}".trim())
        }
    }

    private fun build(
        endpoint: Endpoint,
        creds: com.claudeforwatch.core.auth.Credentials,
        method: String,
        url: String,
        body: String?,
        extra: Map<String, String>,
    ) = HttpRequest(
        method = method,
        url = url,
        headers = AuthHeaders.build(endpoint, creds, auth.userAgent) + extra,
        body = body?.toByteArray(Charsets.UTF_8),
    )

    private inline fun <T> transport(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw ApiException.Network(e)
    }

    companion object {
        const val API_BASE = "https://api.anthropic.com"
    }
}
