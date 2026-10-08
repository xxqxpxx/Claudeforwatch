package com.claudeforwatch.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Messages API streaming event bodies (docs/PROTOCOL.md §4, official API). Only the fields the
 * watch uses are modelled; everything else is ignored.
 */

@Serializable
data class MessageStartEvent(val message: StartedMessage? = null) {
    @Serializable data class StartedMessage(val id: String? = null, val model: String? = null)
}

@Serializable
data class ContentBlockDeltaEvent(val index: Int = 0, val delta: Delta? = null) {
    @Serializable data class Delta(val type: String? = null, val text: String? = null)
}

@Serializable
data class MessageDeltaEvent(val delta: Delta? = null, val usage: Usage? = null) {
    @Serializable data class Delta(@SerialName("stop_reason") val stopReason: String? = null)
    @Serializable data class Usage(@SerialName("output_tokens") val outputTokens: Int? = null)
}

@Serializable
data class StreamErrorEvent(val error: ErrorBody? = null) {
    @Serializable data class ErrorBody(val type: String? = null, val message: String? = null)
}
