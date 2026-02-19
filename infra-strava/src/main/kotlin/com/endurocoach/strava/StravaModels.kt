package com.endurocoach.strava

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StravaToken(
    @SerialName("token_type") val tokenType: String,
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_at") val expiresAt: Long
)

@Serializable
data class StravaTokenExchangeRequest(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
    val code: String,
    @SerialName("grant_type") val grantType: String = "authorization_code"
)

@Serializable
data class StravaTokenRefreshRequest(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
    @SerialName("grant_type") val grantType: String = "refresh_token",
    @SerialName("refresh_token") val refreshToken: String
)

@Serializable
data class StravaActivityDto(
    val id: Long,
    val type: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("elapsed_time") val elapsedTimeSeconds: Int,
    @SerialName("average_heartrate") val averageHeartRate: Double? = null
)
