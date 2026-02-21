package com.endurocoach

import com.endurocoach.config.readRuntimeConfig
import com.endurocoach.data.EncryptedFileTokenStore
import com.endurocoach.domain.Activity
import com.endurocoach.domain.LlmStructuredClient
import com.endurocoach.domain.LoadSnapshot
import com.endurocoach.domain.WorkoutRequest
import com.endurocoach.metrics.BanisterTrimpCalculator
import com.endurocoach.metrics.DemoActivityGenerator
import com.endurocoach.metrics.LoadSeriesService
import com.endurocoach.llm.GeminiClient
import com.endurocoach.llm.GeminiConfig
import com.endurocoach.llm.PerplexityClient
import com.endurocoach.llm.PerplexityConfig
import com.endurocoach.routes.DashboardDependencies
import com.endurocoach.routes.installDashboardRoutes
import com.endurocoach.session.SESSION_COOKIE
import com.endurocoach.session.SESSION_MAX_AGE
import com.endurocoach.session.SessionRegistry
import com.endurocoach.strava.CachedActivityRepository
import com.endurocoach.strava.HttpClientFactory
import com.endurocoach.strava.StravaConfig
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

@Serializable
data class LoadPointResponse(
    val date: String,
    val trimp: Double,
    val ctl: Double,
    val atl: Double,
    val tsb: Double
)

@Serializable
data class LoadSnapshotResponse(
    val source: String,
    val date: String,
    val ctl: Double,
    val atl: Double,
    val tsb: Double,
    val acwr: Double,
    val monotony: Double,
    val ctlRampRate: Double,
    val recentVolumeMinutes: Double,
    val series: List<LoadPointResponse>
)

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }

    val runtimeConfig = readRuntimeConfig()
    val httpClient = HttpClientFactory.create()
    val tokenStore = EncryptedFileTokenStore()
    val stravaConfig = StravaConfig.fromEnv()
    val llmClient = createLlmClient(runtimeConfig, httpClient)
    val dashboardTemplate = loadTemplate("templates/dashboard.html")
    val sessionRegistry = SessionRegistry(
        stravaConfig = stravaConfig,
        tokenStore = tokenStore,
        httpClient = httpClient
    )

    routing {
        installDashboardRoutes(
            DashboardDependencies(
                templateHtml = dashboardTemplate,
                llmClient = llmClient,
                philosophyRulePacks = runtimeConfig.philosophyRulePacks,
                sessionRegistry = sessionRegistry,
                stravaConfigured = stravaConfig != null,
                tokenStore = tokenStore,
                loadProvider = { repo ->
                    buildLoadSnapshot(
                        repository = repo,
                        days = 45,
                        maxHr = 190,
                        restingHr = 50
                    )
                }
            )
        )

        get("/health") {
            call.respondText("enduro-coach scaffold is running")
        }

        get("/api/strava/status") {
            if (stravaConfig == null) {
                call.respond(mapOf("connected" to false, "reason" to "Missing STRAVA_* environment variables"))
            } else {
                val sessionId = call.request.cookies[SESSION_COOKIE]
                val session = sessionRegistry.getOrCreate(sessionId)
                val hasToken = tokenStore.loadToken(session.tokenStoreKey) != null
                call.respond(mapOf("connected" to hasToken))
            }
        }

        get("/api/strava/auth-url") {
            val sessionId = call.request.cookies[SESSION_COOKIE]
            val session = sessionRegistry.getOrCreate(sessionId)
            if (sessionId != session.id) {
                call.response.cookies.append(name = SESSION_COOKIE, value = session.id, path = "/", maxAge = SESSION_MAX_AGE)
            }
            val oauth = session.oauthService
            if (oauth == null) {
                call.respond(mapOf("error" to "Strava is not configured"))
            } else {
                call.respond(mapOf("url" to oauth.buildAuthorizationUrl(state = session.id)))
            }
        }

        get("/api/strava/exchange") {
            val code = call.request.queryParameters["code"]
            val state = call.request.queryParameters["state"]
            val session = sessionRegistry.getOrCreate(state)
            val oauth = session.oauthService

            if (oauth == null) {
                call.respond(mapOf("ok" to false, "error" to "Strava is not configured"))
                return@get
            }

            if (code.isNullOrBlank()) {
                call.respond(mapOf("ok" to false, "error" to "Missing code query parameter"))
                return@get
            }

            runCatching { oauth.exchangeCode(code) }
                .onSuccess {
                    call.response.cookies.append(name = SESSION_COOKIE, value = session.id, path = "/", maxAge = SESSION_MAX_AGE)
                    call.respondRedirect("/")
                }
                .onFailure { call.respond(mapOf("ok" to false, "error" to (it.message ?: "Token exchange failed"))) }
        }

        get("/api/strava/connect") {
            val sessionId = call.request.cookies[SESSION_COOKIE]
            val session = sessionRegistry.getOrCreate(sessionId)
            if (sessionId != session.id) {
                call.response.cookies.append(name = SESSION_COOKIE, value = session.id, path = "/", maxAge = SESSION_MAX_AGE)
            }
            val oauth = session.oauthService
            if (oauth == null) {
                call.respond(mapOf("ok" to false, "error" to "Strava is not configured"))
            } else {
                call.respondRedirect(oauth.buildAuthorizationUrl(state = session.id))
            }
        }

        get("/api/strava/disconnect") {
            val sessionId = call.request.cookies[SESSION_COOKIE]
            if (sessionId != null) {
                val session = sessionRegistry.get(sessionId)
                if (session != null) {
                    tokenStore.deleteToken(session.tokenStoreKey)
                    session.activityRepository?.invalidate()
                }
            }
            // Expire the session cookie so a fresh session is created on next visit
            call.response.cookies.append(name = SESSION_COOKIE, value = "", path = "/", maxAge = 0L)
            call.respondRedirect("/")
        }

        get("/api/load") {
            val days = call.request.queryParameters["days"]?.toIntOrNull()?.coerceIn(7, 120) ?: 45
            val maxHr = call.request.queryParameters["maxHr"]?.toIntOrNull()?.coerceIn(120, 230) ?: 190
            val restingHr = call.request.queryParameters["restingHr"]?.toIntOrNull()?.coerceIn(30, 90) ?: 50

            val sessionId = call.request.cookies[SESSION_COOKIE]
            val session = sessionRegistry.getOrCreate(sessionId)

            val sourceAndSnapshot = buildLoadSnapshot(
                repository = session.activityRepository,
                days = days,
                maxHr = maxHr,
                restingHr = restingHr
            )

            val source = sourceAndSnapshot.first
            val snapshot = sourceAndSnapshot.second
            // sourceAndSnapshot.third = activities (not needed for JSON API)

            val response = LoadSnapshotResponse(
                source = source,
                date = snapshot.date.toString(),
                ctl = snapshot.ctl,
                atl = snapshot.atl,
                tsb = snapshot.tsb,
                acwr = snapshot.acwr,
                monotony = snapshot.monotony,
                ctlRampRate = snapshot.ctlRampRate,
                recentVolumeMinutes = snapshot.recentVolumeMinutes,
                series = snapshot.series.map {
                    LoadPointResponse(
                        date = it.date.toString(),
                        trimp = it.trimp,
                        ctl = it.ctl,
                        atl = it.atl,
                        tsb = it.tsb
                    )
                }
            )

            call.respond(response)
        }

        get("/api/demo-load") {
            call.respond(mapOf("notice" to "Deprecated endpoint; use /api/load"))
        }
    }
}

private suspend fun buildLoadSnapshot(
    repository: CachedActivityRepository?,
    days: Int,
    maxHr: Int,
    restingHr: Int
): Triple<String, LoadSnapshot, List<Activity>> {
    val sourceWithActivities = selectActivities(repository = repository, days = days)
    val source = sourceWithActivities.first
    val activities = sourceWithActivities.second
    val trimpCalculator = BanisterTrimpCalculator(maxHeartRate = maxHr, restingHeartRate = restingHr)
    val snapshot = LoadSeriesService(trimpCalculator).buildSnapshot(activities = activities, days = days)
    return Triple(source, snapshot, activities)
}

private fun createLlmClient(
    runtimeConfig: com.endurocoach.config.AppRuntimeConfig,
    httpClient: io.ktor.client.HttpClient
): LlmStructuredClient {
    return when (runtimeConfig.llmProvider.lowercase()) {
        "gemini" -> {
            val config = GeminiConfig.fromEnv(
                apiKeyEnv = runtimeConfig.llmApiKeyEnv,
                model = runtimeConfig.llmModel,
                baseUrl = runtimeConfig.llmBaseUrl
            ) ?: return UnavailableLlmClient(
                "Gemini is not configured. Set ${runtimeConfig.llmApiKeyEnv} in environment."
            )
            GeminiClient(config = config, httpClient = httpClient)
        }
        "perplexity" -> {
            val config = PerplexityConfig.fromEnv(
                apiKeyEnv = runtimeConfig.llmApiKeyEnv,
                model = runtimeConfig.llmModel,
                baseUrl = runtimeConfig.llmBaseUrl
            ) ?: return UnavailableLlmClient(
                "Perplexity is not configured. Set ${runtimeConfig.llmApiKeyEnv} in environment."
            )
            PerplexityClient(config = config, httpClient = httpClient)
        }
        else -> UnavailableLlmClient("Unsupported LLM provider: ${runtimeConfig.llmProvider}")
    }
}

private fun loadTemplate(path: String): String {
    val stream = Application::class.java.classLoader.getResourceAsStream(path)
        ?: error("Missing template resource: $path")
    return stream.bufferedReader().use { it.readText() }
}

private class UnavailableLlmClient(
    private val reason: String
) : LlmStructuredClient {
    override suspend fun generateWorkoutPlan(request: WorkoutRequest) = error(reason)
}

private suspend fun selectActivities(
    repository: CachedActivityRepository?,
    days: Int
): Pair<String, List<Activity>> {
    if (repository == null) {
        return "demo" to DemoActivityGenerator.generate(days = days)
    }

    return runCatching { repository.getActivitiesLastDays(days) }
        .map { list -> "strava" to list }
        .getOrElse {
            "strava" to emptyList()
        }
}
