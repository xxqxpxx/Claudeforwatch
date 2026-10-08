package com.claudeforwatch.core

import kotlinx.serialization.json.Json

/** The single Json configuration used by the core: tolerant of unknown/extra fields (APIs evolve). */
val CoreJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
    isLenient = true
}

/** Version string reported in `User-Agent` (docs/PROTOCOL.md §3). The app overrides it at start. */
object CoreInfo {
    const val VERSION = "0.1.0"
    fun userAgent(version: String = VERSION, platform: String = "Wear OS") = "ClaudeForWatch/$version ($platform)"
}
