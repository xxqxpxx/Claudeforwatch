package com.claudeforwatch.phone

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.claudeforwatch.core.CoreInfo
import com.claudeforwatch.core.api.ApiException
import com.claudeforwatch.core.auth.AccountCompletion
import com.claudeforwatch.core.auth.OAuthClient
import com.claudeforwatch.core.auth.OAuthConfig
import com.claudeforwatch.core.auth.OAuthException
import com.claudeforwatch.core.auth.PendingAuthorization
import com.claudeforwatch.core.auth.Pkce
import com.claudeforwatch.core.auth.ProvisionMessage
import com.claudeforwatch.core.auth.Provisioning
import com.claudeforwatch.phone.platform.LoopbackCallbackServer
import com.claudeforwatch.phone.platform.OkHttpClientAdapter
import com.claudeforwatch.phone.platform.WatchLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Base64

sealed interface WatchesState {
    data object Loading : WatchesState
    data class Ready(val watches: List<WatchLink.Watch>) : WatchesState
    data class Unavailable(val message: String) : WatchesState
}

sealed interface SignInState {
    data object Idle : SignInState

    /** Custom Tab open, loopback listening. [showPaste] reveals the platform.claude.com fallback. */
    data class Waiting(val showPaste: Boolean) : SignInState
    data object Exchanging : SignInState

    /** A complete §1.1 record is held in memory (never displayed); [who] is the account email. */
    data class Ready(val who: String) : SignInState
    data class Failed(val message: String) : SignInState
}

/** One watch's delivery. [detail]: "Sending…", the watch's `ok: …` / `error: …` line, or a phone-side failure. */
data class Delivery(val nodeId: String, val name: String, val kind: ProvisionMessage.Kind?, val status: Status, val detail: String) {
    enum class Status { Sending, Waiting, Ok, Error }
}

data class UiState(
    val watches: WatchesState = WatchesState.Loading,
    val signIn: SignInState = SignInState.Idle,
    val apiKeyError: String? = null,
    val deliveries: List<Delivery> = emptyList(),
    val notice: String? = null,
)

/**
 * Holds everything in memory only: the PKCE material, the credentials record and the typed API
 * key disappear with the process. Nothing is written to disk or logged.
 */
class PhoneViewModel(app: Application) : AndroidViewModel(app) {
    private val userAgent = CoreInfo.userAgent(BuildConfig.VERSION_NAME, "Android phone")
    private val http = OkHttpClientAdapter()
    private val oauth = OAuthClient(http, userAgent = userAgent)
    val link = WatchLink(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** URLs for the Activity to open in a Custom Tab. */
    private val _openUrl = Channel<String>(Channel.BUFFERED)
    val openUrl = _openUrl.receiveAsFlow()

    /** Text fields live here, not in saved instance state, so they never leave memory. */
    var apiKeyInput by mutableStateOf("")
    var codeInput by mutableStateOf("")

    private var loopbackPending: PendingAuthorization? = null
    private var manualPending: PendingAuthorization? = null
    private var signInJob: Job? = null
    private var tabOpened = false
    private var record: ProvisionMessage.Payload? = null
    private val replyTimeouts = mutableMapOf<String, Job>()

    // ------------------------------------------------------------------ watches

    fun refreshWatches() {
        viewModelScope.launch {
            _ui.update { it.copy(watches = WatchesState.Loading) }
            val state = try {
                WatchesState.Ready(link.connectedWatches())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WatchesState.Unavailable(wearUnavailableMessage(e))
            }
            _ui.update { it.copy(watches = state) }
        }
    }

    // ------------------------------------------------------------------ Claude sign-in (personal)

    fun startSignIn() {
        if (!BuildConfig.PERSONAL_MODE) return
        cancelSignIn()
        record = null
        codeInput = ""
        tabOpened = false
        _ui.update { it.copy(signIn = SignInState.Waiting(showPaste = false), notice = null) }
        signInJob = viewModelScope.launch {
            val pkce = Pkce.generate()
            val server = try {
                withContext(Dispatchers.IO) { LoopbackCallbackServer.start() }
            } catch (e: Exception) {
                null // no loopback (unlikely): the paste flow still works
            }
            // Same verifier/state for both redirect variants, like scripts/watch-login.py.
            manualPending = oauth.startAuthorization(pkce, OAuthConfig.REDIRECT_URI)
            if (server == null) {
                _ui.update { it.copy(signIn = SignInState.Waiting(showPaste = true)) }
                return@launch
            }
            val local = oauth.startAuthorization(pkce, OAuthClient.loopbackRedirectUri(server.port))
            loopbackPending = local
            _openUrl.send(local.authorizeUrl)
            val target = try {
                withTimeout(OAuthConfig.PENDING_TTL_MILLIS) {
                    server.awaitCallback { t -> runCatching { OAuthClient.parseCode(callbackUrl(server.port, t), pkce.state) }.isSuccess }
                }
            } catch (e: TimeoutCancellationException) {
                _ui.update { it.copy(signIn = SignInState.Failed(OAuthException.Expired().message!!)) }
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Loopback broke; the paste fallback is still possible.
                _ui.update { it.copy(signIn = SignInState.Waiting(showPaste = true)) }
                return@launch
            } finally {
                server.close()
            }
            val oauthError = queryParam(target, "error")
            if (oauthError != null) {
                _ui.update { it.copy(signIn = SignInState.Failed("Sign-in was cancelled or refused ($oauthError).")) }
                return@launch
            }
            finishSignIn { oauth.exchange(callbackUrl(server.port, target), local) }
        }
    }

    /** The Activity opened the authorize URL; reveal the paste fallback once the user comes back. */
    fun onTabOpened() {
        tabOpened = true
    }

    fun onResumed() {
        val s = _ui.value.signIn
        if (tabOpened && s is SignInState.Waiting && !s.showPaste) {
            _ui.update { it.copy(signIn = SignInState.Waiting(showPaste = true)) }
        }
    }

    fun showPasteFallback() {
        if (_ui.value.signIn is SignInState.Waiting) _ui.update { it.copy(signIn = SignInState.Waiting(showPaste = true)) }
    }

    /** Opens the platform.claude.com redirect variant, whose page shows `code#state`. */
    fun openCodePage() {
        val url = manualPending?.authorizeUrl ?: return
        viewModelScope.launch { _openUrl.send(url) }
    }

    fun submitPastedCode() {
        val pending = manualPending ?: return
        val raw = codeInput
        if (raw.isBlank()) return
        signInJob?.cancel() // stop the loopback; the pasted code wins
        signInJob = viewModelScope.launch {
            finishSignIn { oauth.exchange(Provisioning.codeWithState(raw, pending.pkce.state), pending) }
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        signInJob = null
        loopbackPending = null
        manualPending = null
        _ui.update { it.copy(signIn = SignInState.Idle) }
    }

    /** Drops the in-memory credentials record. */
    fun forgetRecord() {
        record = null
        codeInput = ""
        _ui.update { it.copy(signIn = SignInState.Idle) }
    }

    fun resendRecord() {
        val payload = record ?: return
        sendToWatches(payload)
    }

    private suspend fun finishSignIn(exchange: suspend () -> com.claudeforwatch.core.auth.Credentials) {
        _ui.update { it.copy(signIn = SignInState.Exchanging) }
        try {
            val exchanged = exchange()
            val creds = AccountCompletion.complete(exchanged, oauth, http, userAgent)
            val b64 = Base64.getEncoder().encodeToString(creds.encode().toByteArray(Charsets.UTF_8))
            val payload = ProvisionMessage.Payload(ProvisionMessage.Kind.Credentials, b64)
            record = payload
            loopbackPending = null
            manualPending = null
            codeInput = ""
            _ui.update { it.copy(signIn = SignInState.Ready(creds.accountEmail ?: "your Claude account")) }
            sendToWatches(payload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _ui.update { it.copy(signIn = SignInState.Failed(e.userMessage())) }
        }
    }

    // ------------------------------------------------------------------ API key (all flavors)

    fun sendApiKey() {
        val key = try {
            Provisioning.apiKey(apiKeyInput)
        } catch (e: IllegalArgumentException) {
            _ui.update { it.copy(apiKeyError = "That doesn't look like an Anthropic API key (sk-ant-…).") }
            return
        }
        _ui.update { it.copy(apiKeyError = null) }
        sendToWatches(ProvisionMessage.Payload(ProvisionMessage.Kind.ApiKey, key))
    }

    fun onApiKeyChanged(value: String) {
        apiKeyInput = value
        if (_ui.value.apiKeyError != null) _ui.update { it.copy(apiKeyError = null) }
    }

    // ------------------------------------------------------------------ delivery

    private fun sendToWatches(payload: ProvisionMessage.Payload) {
        viewModelScope.launch {
            val watches = try {
                link.connectedWatches()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = wearUnavailableMessage(e)
                _ui.update { it.copy(watches = WatchesState.Unavailable(msg), notice = "Not sent. $msg") }
                return@launch
            }
            _ui.update { it.copy(watches = WatchesState.Ready(watches)) }
            if (watches.isEmpty()) {
                _ui.update {
                    it.copy(notice = "Not sent: no watch is connected. Check that the watch is paired and nearby, then try again.")
                }
                return@launch
            }
            _ui.update { s ->
                s.copy(
                    notice = null,
                    deliveries = watches.map { Delivery(it.id, it.name, payload.kind, Delivery.Status.Sending, "Sending…") },
                )
            }
            for (w in watches) {
                try {
                    link.send(w.id, payload)
                    if (_ui.value.deliveries.firstOrNull { it.nodeId == w.id }?.status == Delivery.Status.Sending) {
                        updateDelivery(w.id, Delivery.Status.Waiting, "Sent. Waiting for the watch…")
                    }
                    replyTimeouts.remove(w.id)?.cancel()
                    replyTimeouts[w.id] = viewModelScope.launch {
                        delay(REPLY_TIMEOUT_MILLIS)
                        val d = _ui.value.deliveries.firstOrNull { it.nodeId == w.id }
                        if (d?.status == Delivery.Status.Waiting) {
                            updateDelivery(
                                w.id, Delivery.Status.Error,
                                "No reply. Is Claude for Watch installed on this watch, same build (store or personal) and signed with the same key as this app?",
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    updateDelivery(w.id, Delivery.Status.Error, "Couldn't reach the watch (${e.javaClass.simpleName}).")
                }
            }
        }
    }

    /** Called by the Activity's MessageClient listener (main thread) for `/claudeforwatch/provision/result`. */
    fun onWatchResult(nodeId: String, text: String) {
        replyTimeouts.remove(nodeId)?.cancel()
        val status = if (text.startsWith("ok:")) Delivery.Status.Ok else Delivery.Status.Error
        val line = text.take(MAX_RESULT_CHARS)
        val known = _ui.value.deliveries.firstOrNull { it.nodeId == nodeId }
        if (known == null) {
            _ui.update { it.copy(deliveries = it.deliveries + Delivery(nodeId, "Watch", null, status, line)) }
        } else {
            updateDelivery(nodeId, status, line)
        }
        if (status == Delivery.Status.Ok && known?.kind == ProvisionMessage.Kind.ApiKey) apiKeyInput = ""
    }

    private fun updateDelivery(nodeId: String, status: Delivery.Status, detail: String) {
        _ui.update { s -> s.copy(deliveries = s.deliveries.map { if (it.nodeId == nodeId) it.copy(status = status, detail = detail) else it }) }
    }

    override fun onCleared() {
        record = null
        apiKeyInput = ""
        codeInput = ""
    }

    private companion object {
        const val REPLY_TIMEOUT_MILLIS = 45_000L
        const val MAX_RESULT_CHARS = 300

        fun callbackUrl(port: Int, target: String) = "http://localhost:$port$target"

        /** OAuth `error` codes only (e.g. access_denied); never the code itself. */
        fun queryParam(target: String, name: String): String? =
            target.substringAfter('?', "").split('&')
                .firstOrNull { it.substringBefore('=') == name }
                ?.substringAfter('=', "")
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                ?.takeIf { it.isNotBlank() }
                ?.take(80)

        fun wearUnavailableMessage(e: Exception): String =
            if (e is com.google.android.gms.common.api.ApiException) {
                "Wear OS isn't set up on this phone. Pair your watch with the Wear OS app (or Galaxy Wearable) first."
            } else {
                "Couldn't reach the Wear OS service on this phone."
            }

        fun Throwable.userMessage(): String = when (this) {
            is OAuthException -> message ?: "Sign-in failed."
            is ApiException -> message ?: "Something went wrong."
            is java.io.IOException -> "No connection. Check the phone's network."
            is IllegalArgumentException -> message ?: "Invalid input."
            is IllegalStateException -> message ?: "Something went wrong."
            else -> "Something went wrong."
        }
    }
}
