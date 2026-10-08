package com.claudeforwatch.core.net

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.fold

/** A transport-agnostic HTTP request. Header names are sent as given. */
class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
) {
    val bodyString: String? get() = body?.toString(Charsets.UTF_8)

    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** Never prints header values or the body: they can carry secrets. */
    override fun toString(): String = "HttpRequest($method $url, headers=${headers.keys})"

    companion object {
        fun get(url: String, headers: Map<String, String>) = HttpRequest("GET", url, headers)
        fun post(url: String, headers: Map<String, String>, body: String) =
            HttpRequest("POST", url, headers, body.toByteArray(Charsets.UTF_8))
    }
}

/** A fully buffered response. Header names are lower-cased by adapters. */
class HttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    val bodyString: String get() = body.toString(Charsets.UTF_8)
    val isSuccessful: Boolean get() = status in 200..299
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    override fun toString(): String = "HttpResponse($status, ${body.size} bytes)"
}

/**
 * A streaming response. [body] is cold and single-use: collect it exactly once (or call
 * [readText] for an error body). Cancelling the collector must abort the underlying call.
 */
class HttpStream(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: Flow<ByteArray> = emptyFlow(),
) {
    val isSuccessful: Boolean get() = status in 200..299
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** Buffers the remaining body as UTF-8 text (used for error bodies). */
    suspend fun readText(): String =
        body.fold(java.io.ByteArrayOutputStream()) { acc, chunk -> acc.apply { write(chunk) } }
            .toString(Charsets.UTF_8.name())
}

/** Implemented by the platform (OkHttp on Wear OS) and by [StubHttpClient] in tests. */
interface HttpClient {
    /** Performs a request and buffers the response body. Throws [java.io.IOException] on transport failure. */
    suspend fun request(request: HttpRequest): HttpResponse

    /** Opens a streaming request (SSE). Headers are available before the body is consumed. */
    suspend fun stream(request: HttpRequest): HttpStream
}

/**
 * Scriptable fake for tests and previews. Each call is matched against the registered
 * handlers in order; the first whose predicate matches produces the response.
 */
class StubHttpClient : HttpClient {
    class Route(
        val matches: (HttpRequest) -> Boolean,
        val respond: suspend (HttpRequest) -> HttpResponse,
        val streamChunks: (suspend (HttpRequest) -> Pair<Int, List<ByteArray>>)? = null,
    )

    private val routes = mutableListOf<Route>()
    private val _requests = mutableListOf<HttpRequest>()
    val requests: List<HttpRequest> get() = synchronized(_requests) { _requests.toList() }

    fun on(matches: (HttpRequest) -> Boolean, respond: suspend (HttpRequest) -> HttpResponse): StubHttpClient =
        apply { routes += Route(matches, respond) }

    fun onUrl(prefix: String, status: Int = 200, body: String = "{}", headers: Map<String, String> = emptyMap()) =
        on({ it.url.startsWith(prefix) }) { HttpResponse(status, headers, body.toByteArray()) }

    /** Streams [chunks] (already split as the test wants) for requests whose URL starts with [prefix]. */
    fun onStream(prefix: String, status: Int = 200, chunks: (HttpRequest) -> List<String>): StubHttpClient = apply {
        routes += Route(
            matches = { it.url.startsWith(prefix) },
            respond = { HttpResponse(status, emptyMap(), chunks(it).joinToString("").toByteArray()) },
            streamChunks = { status to chunks(it).map(String::toByteArray) },
        )
    }

    private fun route(request: HttpRequest): Route {
        synchronized(_requests) { _requests += request }
        return routes.firstOrNull { it.matches(request) }
            ?: throw java.io.IOException("StubHttpClient: no route for ${request.method} ${request.url}")
    }

    override suspend fun request(request: HttpRequest): HttpResponse = route(request).respond(request)

    override suspend fun stream(request: HttpRequest): HttpStream {
        val r = route(request)
        val streamer = r.streamChunks
        if (streamer == null) {
            val response = r.respond(request)
            return HttpStream(response.status, response.headers, flow { emit(response.body) })
        }
        val (status, chunks) = streamer(request)
        return HttpStream(status, emptyMap(), flow { chunks.forEach { emit(it) } })
    }
}
