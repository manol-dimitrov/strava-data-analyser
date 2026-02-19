package com.endurocoach.strava

import com.endurocoach.domain.TokenStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.DefaultJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

class StravaOAuthService(
    private val config: StravaConfig,
    private val tokenStore: TokenStore,
    private val httpClient: HttpClient,
    private val tokenStoreKey: String = "strava_token"
) {
    private val json: Json = DefaultJson

    fun buildAuthorizationUrl(state: String = "enduro-coach"): String {
        return URLBuilder("https://www.strava.com/oauth/authorize").apply {
            parameters.append("client_id", config.clientId)
            parameters.append("response_type", "code")
            parameters.append("redirect_uri", config.redirectUri)
            parameters.append("scope", config.scope)
            parameters.append("approval_prompt", "auto")
            parameters.append("state", state)
        }.buildString()
    }

    suspend fun exchangeCode(code: String): StravaToken {
        val token = httpClient.post("https://www.strava.com/oauth/token") {
            contentType(ContentType.Application.Json)
            setBody(
                StravaTokenExchangeRequest(
                    clientId = config.clientId,
                    clientSecret = config.clientSecret,
                    code = code
                )
            )
        }.body<StravaToken>()

        persistToken(token)
        return token
    }

    suspend fun validAccessToken(): String {
        val token = loadToken() ?: error("No Strava token present. Complete OAuth first.")
        val now = Instant.now().epochSecond

        if (token.expiresAt <= now + 60) {
            val refreshed = refreshToken(token.refreshToken)
            persistToken(refreshed)
            return refreshed.accessToken
        }

        return token.accessToken
    }

    private suspend fun refreshToken(refreshToken: String): StravaToken {
        return httpClient.post("https://www.strava.com/oauth/token") {
            contentType(ContentType.Application.Json)
            setBody(
                StravaTokenRefreshRequest(
                    clientId = config.clientId,
                    clientSecret = config.clientSecret,
                    refreshToken = refreshToken
                )
            )
        }.body()
    }

    private fun loadToken(): StravaToken? {
        val payload = tokenStore.loadToken(tokenStoreKey) ?: return null
        return runCatching { json.decodeFromString<StravaToken>(payload) }.getOrNull()
    }

    private fun persistToken(token: StravaToken) {
        tokenStore.saveToken(tokenStoreKey, json.encodeToString(token))
    }
}
