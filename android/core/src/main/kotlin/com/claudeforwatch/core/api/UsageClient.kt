package com.claudeforwatch.core.api

import com.claudeforwatch.core.CoreJson
import com.claudeforwatch.core.auth.Endpoint
import com.claudeforwatch.core.model.ProfileDto
import com.claudeforwatch.core.model.UsageDto

/** Subscription usage and profile (docs/PROTOCOL.md §2 "Usage"/"Profile", unofficial). */
class UsageClient(private val transport: ApiTransport, private val baseUrl: String = ApiTransport.API_BASE) {
    suspend fun usage(): UsageDto {
        val r = transport.send(Endpoint.OAuthAccount, "GET", "$baseUrl/api/oauth/usage")
        return CoreJson.decodeFromString(UsageDto.serializer(), r.bodyString)
    }

    suspend fun profile(): ProfileDto {
        val r = transport.send(Endpoint.OAuthAccount, "GET", "$baseUrl/api/oauth/profile")
        return CoreJson.decodeFromString(ProfileDto.serializer(), r.bodyString)
    }
}
