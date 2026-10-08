package com.claudeforwatch.phone.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections

/**
 * One-shot HTTP listener on 127.0.0.1 for the OAuth redirect `http://localhost:<port>/callback`
 * (PROTOCOL §2; same approach as scripts/watch-login.py). Only loopback is bound, so nothing off
 * the phone can reach it. Never logs request lines (they carry the authorization code).
 */
class LoopbackCallbackServer private constructor(private val socket: ServerSocket) : Closeable {
    private val clients: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf())

    val port: Int get() = socket.localPort

    /**
     * Suspends until the browser requests `GET /callback?…`, answers it with a small page and
     * returns the request target (`/callback?code=…&state=…`). [accept] decides whether the page
     * says "done" or "failed" (e.g. state mismatch). Other paths get a 404 and are ignored.
     * The socket is closed when this returns or is cancelled.
     */
    suspend fun awaitCallback(accept: (target: String) -> Boolean): String = try {
        coroutineScope {
            val done = CompletableDeferred<String>()
            val acceptor = launch(Dispatchers.IO) {
                socket.soTimeout = ACCEPT_POLL_MILLIS
                while (isActive) {
                    val client = try {
                        socket.accept()
                    } catch (e: SocketTimeoutException) {
                        continue
                    } catch (e: IOException) {
                        done.completeExceptionally(e)
                        break
                    }
                    clients += client
                    // Browsers open speculative idle connections; serve each one independently.
                    launch(Dispatchers.IO) {
                        val target = try {
                            serve(client, accept)
                        } catch (e: IOException) {
                            null
                        } finally {
                            clients -= client
                            runCatching { client.close() }
                        }
                        if (target != null) done.complete(target)
                    }
                }
            }
            try {
                done.await()
            } finally {
                close() // unblocks accept() and any idle reads so the scope can finish
                acceptor.cancel()
            }
        }
    } finally {
        close()
    }

    private fun serve(client: Socket, accept: (String) -> Boolean): String? {
        client.soTimeout = READ_TIMEOUT_MILLIS
        val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = reader.readLine() ?: return null
        var headers = 0
        while (headers++ < MAX_HEADER_LINES) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val parts = requestLine.split(' ')
        if (parts.size < 3 || parts[0] != "GET") {
            respond(client, 405, "Method not allowed", "")
            return null
        }
        val target = parts[1]
        if (target.substringBefore('?') != "/callback") {
            respond(client, 404, "Not found", "")
            return null
        }
        val ok = runCatching { accept(target) }.getOrDefault(false)
        respond(
            client, 200, "OK",
            if (ok) page("Done", "Return to the Claude for Watch app.")
            else page("Sign-in failed", "Return to the Claude for Watch app and try again."),
        )
        return target
    }

    private fun respond(client: Socket, status: Int, reason: String, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "Connection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            write(body)
            flush()
        }
    }

    private fun page(title: String, text: String) =
        "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>" +
            "<title>Claude for Watch</title>" +
            "<body style='font:18px system-ui,sans-serif;margin:3em 1.5em;text-align:center'>" +
            "<h2>$title</h2><p>$text</p></body>"

    override fun close() {
        runCatching { socket.close() }
        synchronized(clients) {
            clients.forEach { runCatching { it.close() } }
            clients.clear()
        }
    }

    companion object {
        private const val ACCEPT_POLL_MILLIS = 500
        private const val READ_TIMEOUT_MILLIS = 10_000
        private const val MAX_HEADER_LINES = 100

        /** Binds 127.0.0.1 on a free port. Call off the main thread. */
        fun start(): LoopbackCallbackServer =
            LoopbackCallbackServer(ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")))
    }
}
