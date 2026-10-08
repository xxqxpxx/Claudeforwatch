package com.claudeforwatch.ui.vm

import com.claudeforwatch.core.api.ApiException
import com.claudeforwatch.core.auth.OAuthException
import kotlinx.coroutines.CancellationException

/** Short, secret-free copy for a watch screen. */
fun Throwable.userMessage(): String = when (this) {
    is ApiException -> message ?: "Something went wrong."
    is OAuthException -> message ?: "Sign-in failed."
    is java.io.IOException -> "No connection. Check the watch's network."
    is IllegalArgumentException -> message ?: "Invalid input."
    is IllegalStateException -> message ?: "Something went wrong."
    else -> "Something went wrong."
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
    "rate_limit_error" -> "Rate limited. Try again soon."
    else -> message?.take(160) ?: "The reply stopped early."
}
