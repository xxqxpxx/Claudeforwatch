package com.claudeforwatch.ui.vm

import com.claudeforwatch.core.api.ApiException
import com.claudeforwatch.core.auth.OAuthException
import kotlinx.coroutines.CancellationException

/** Short, secret-free copy for a watch screen. */
fun Throwable.userMessage(): String = when (this) {
    is ApiException -> message ?: "Something went wrong."
    // Parsing failures mean the unofficial API changed shape; never show raw JSON on the watch.
    is kotlinx.serialization.SerializationException -> "Claude sent a reply this app can't read yet. Update the app."
    is OAuthException -> message ?: "Sign-in failed."
    is java.io.IOException -> "No connection. Check the watch's network."
    is IllegalArgumentException -> message ?: "Invalid input."
    is IllegalStateException -> message ?: "Something went wrong."
    else -> "Something went wrong."
}

/**
 * Rate-limit copy for chat, plus the way out when chat is running on the Claude-account token:
 * an API key (Max and Team plans include monthly API credits) is a separate, supported pool.
 */
fun Throwable.chatMessage(onClaudeAccountWithoutKey: Boolean): String {
    val base = userMessage()
    return if (this is ApiException.RateLimited && onClaudeAccountWithoutKey) {
        "$base Tip: send an API key from the phone app; chat then uses your API credits."
    } else base
}

/** Runs [block], turning failures (not cancellation) into a user message. */
suspend fun <T> attempt(onError: (String) -> Unit, block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    onError(e.userMessage())
    null
}

/** Stream errors (`error` SSE events) mapped like HTTP ones (PROTOCOL §4). */
fun streamErrorMessage(type: String?, message: String?): String = when (type) {
    "overloaded_error" -> "Claude is overloaded. Try again in a moment."
    "rate_limit_error" -> com.claudeforwatch.core.api.rateLimitCopy(null, message, null)
    else -> message?.take(160) ?: "The reply stopped early."
}
