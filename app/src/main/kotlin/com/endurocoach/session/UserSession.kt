package com.endurocoach.session

import com.endurocoach.domain.TokenStore
import com.endurocoach.routes.DashboardStateStore
import com.endurocoach.strava.CachedActivityRepository
import com.endurocoach.strava.StravaActivityRepository
import com.endurocoach.strava.StravaConfig
import com.endurocoach.strava.StravaOAuthService
import io.ktor.client.HttpClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Cookie name used to track user sessions. */
const val SESSION_COOKIE = "enduro_session"

/** Session cookie max-age in seconds (30 days). */
const val SESSION_MAX_AGE = 2592000L

/**
 * Per-user in-memory session holding dashboard state and Strava credentials.
 * Each user who visits the app gets their own session, with an independent
 * Strava OAuth flow and activity cache.
 */
class UserSession(
    val id: String,
    val dashboardState: DashboardStateStore = DashboardStateStore(),
    val tokenStoreKey: String,
    val oauthService: StravaOAuthService?,
    val activityRepository: CachedActivityRepository?
)

/**
 * Thread-safe registry of active user sessions.
 *
 * Each session receives its own [StravaOAuthService] (with a unique token-store key)
 * and [CachedActivityRepository] so that multiple users can connect their own
 * Strava accounts simultaneously.
 */
class SessionRegistry(
    private val stravaConfig: StravaConfig?,
    private val tokenStore: TokenStore,
    private val httpClient: HttpClient
) {
    private val sessions = ConcurrentHashMap<String, UserSession>()

    /** Return the existing session for [sessionId], or create a brand-new one. */
    fun getOrCreate(sessionId: String?): UserSession {
        if (sessionId != null) {
            sessions[sessionId]?.let { return it }
        }
        val id = sessionId ?: UUID.randomUUID().toString()
        return sessions.computeIfAbsent(id) { buildSession(it) }
    }

    /** Lookup only — returns null when the session does not exist. */
    fun get(sessionId: String): UserSession? = sessions[sessionId]

    private fun buildSession(id: String): UserSession {
        val key = "strava_token_$id"
        val oauth = stravaConfig?.let {
            StravaOAuthService(
                config = it,
                tokenStore = tokenStore,
                httpClient = httpClient,
                tokenStoreKey = key
            )
        }
        val repo = oauth?.let {
            CachedActivityRepository(
                delegate = StravaActivityRepository(authService = it, httpClient = httpClient)
            )
        }
        return UserSession(
            id = id,
            tokenStoreKey = key,
            oauthService = oauth,
            activityRepository = repo
        )
    }
}
