package com.claudeforwatch.phone.platform

import com.claudeforwatch.core.net.HttpClient
import com.claudeforwatch.core.net.HttpRequest
import com.claudeforwatch.core.net.HttpResponse
import com.claudeforwatch.core.net.HttpStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * Copy of the wear module's adapter (android/wear/.../platform/OkHttpClientAdapter.kt); `core`
 * stays free of Android/OkHttp dependencies, so each app carries its own transport.
 *
 * OkHttp transport for the core [HttpClient]. Streams use a raw body read with
 * `readTimeout(0)` so SSE parsing stays in the shared, unit-tested core parser.
 * No logging interceptor: requests carry secrets.
 */
class OkHttpClientAdapter(base: OkHttpClient = defaultClient()) : HttpClient {
    private val client = base
    private val streamClient = base.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    override suspend fun request(request: HttpRequest): HttpResponse {
        val response = client.newCall(request.toOkHttp()).await()
        return withContext(Dispatchers.IO) {
            response.use { HttpResponse(it.code, it.headerMap(), it.body.bytes()) }
        }
    }

    override suspend fun stream(request: HttpRequest): HttpStream {
        val call = streamClient.newCall(request.toOkHttp())
        val response = call.await()
        return HttpStream(response.code, response.headerMap(), bodyFlow(call, response))
    }

    /** Emits raw chunks as they arrive; cancelling the collector cancels the call. */
    private fun bodyFlow(call: Call, response: Response): Flow<ByteArray> = channelFlow {
        launch(Dispatchers.IO) {
            try {
                response.use {
                    val source = it.body.source()
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val n = source.read(buffer)
                        if (n == -1) break
                        send(buffer.copyOf(n))
                    }
                }
                channel.close()
            } catch (e: IOException) {
                channel.close(e)
            }
        }
        awaitClose { call.cancel() }
    }

    private fun HttpRequest.toOkHttp(): Request {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        val mediaType = (header("Content-Type") ?: "application/json").toMediaTypeOrNull()
        val requestBody = body?.toRequestBody(mediaType)
        builder.method(method, requestBody ?: if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody(mediaType) else null)
        return builder.build()
    }

    private fun Response.headerMap(): Map<String, String> =
        headers.names().associate { it.lowercase() to (headers[it] ?: "") }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false) // never silently resend a completion request
            .build()
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }
    })
}
