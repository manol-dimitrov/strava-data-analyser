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
import com.endurocoach.routes.DashboardStateStore
import com.endurocoach.routes.installDashboardRoutes
import com.endurocoach.strava.CachedActivityRepository
import com.endurocoach.strava.HttpClientFactory
import com.endurocoach.strava.StravaActivityRepository
import com.endurocoach.strava.StravaConfig
import com.endurocoach.strava.StravaOAuthService
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
    val stravaOAuthService = stravaConfig?.let {
        StravaOAuthService(
            config = it,
            tokenStore = tokenStore,
            httpClient = httpClient
        )
    }
    val stravaActivityRepository = stravaOAuthService?.let {
        CachedActivityRepository(
            delegate = StravaActivityRepository(
                authService = it,
                httpClient = httpClient
            )
        )
    }
    val llmClient = createLlmClient(runtimeConfig, httpClient)
    val dashboardStateStore = DashboardStateStore()
    val dashboardTemplate = loadTemplate("templates/dashboard.html")

    routing {
        installDashboardRoutes(
            DashboardDependencies(
                templateHtml = dashboardTemplate,
                stateStore = dashboardStateStore,
                llmClient = llmClient,
                philosophyRulePacks = runtimeConfig.philosophyRulePacks,
                stravaConnected = stravaConfig != null,
                stravaAuthUrl = stravaOAuthService?.buildAuthorizationUrl(),
                loadProvider = {
                    buildLoadSnapshot(
                        repository = stravaActivityRepository,
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
                call.respond(mapOf("connected" to true))
            }
        }

        get("/api/strava/auth-url") {
            val oauth = stravaOAuthService
            if (oauth == null) {
                call.respond(mapOf("error" to "Strava is not configured"))
            } else {
                call.respond(mapOf("url" to oauth.buildAuthorizationUrl()))
            }
        }

        get("/api/strava/exchange") {
            val oauth = stravaOAuthService
            val code = call.request.queryParameters["code"]

            if (oauth == null) {
                call.respond(mapOf("ok" to false, "error" to "Strava is not configured"))
                return@get
            }

            if (code.isNullOrBlank()) {
                call.respond(mapOf("ok" to false, "error" to "Missing code query parameter"))
                return@get
            }

            runCatching { oauth.exchangeCode(code) }
                .onSuccess { call.respondRedirect("/") }
                .onFailure { call.respond(mapOf("ok" to false, "error" to (it.message ?: "Token exchange failed"))) }
        }

        get("/api/strava/connect") {
            val oauth = stravaOAuthService
            if (oauth == null) {
                call.respond(mapOf("ok" to false, "error" to "Strava is not configured"))
            } else {
                call.respondRedirect(oauth.buildAuthorizationUrl())
            }
        }

        get("/api/load") {
            val days = call.request.queryParameters["days"]?.toIntOrNull()?.coerceIn(7, 120) ?: 45
            val maxHr = call.request.queryParameters["maxHr"]?.toIntOrNull()?.coerceIn(120, 230) ?: 190
            val restingHr = call.request.queryParameters["restingHr"]?.toIntOrNull()?.coerceIn(30, 90) ?: 50

            val sourceAndSnapshot = buildLoadSnapshot(
                repository = stravaActivityRepository,
                days = days,
                maxHr = maxHr,
                restingHr = restingHr
            )

            val source = sourceAndSnapshot.first
            val snapshot = sourceAndSnapshot.second

            val response = LoadSnapshotResponse(
                source = source,
                date = snapshot.date.toString(),
                ctl = snapshot.ctl,
                atl = snapshot.atl,
                tsb = snapshot.tsb,
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
): Pair<String, LoadSnapshot> {
    val sourceWithActivities = selectActivities(repository = repository, days = days)
    val source = sourceWithActivities.first
    val activities = sourceWithActivities.second
    val trimpCalculator = BanisterTrimpCalculator(maxHeartRate = maxHr, restingHeartRate = restingHr)
    val snapshot = LoadSeriesService(trimpCalculator).buildSnapshot(activities = activities, days = days)
    return source to snapshot
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
        .map { list ->
            if (list.isEmpty()) {
                "demo" to DemoActivityGenerator.generate(days = days)
            } else {
                "strava" to list
            }
        }
        .getOrElse {
            "demo" to DemoActivityGenerator.generate(days = days)
        }
}
