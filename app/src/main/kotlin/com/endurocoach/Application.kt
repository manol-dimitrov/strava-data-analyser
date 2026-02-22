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
import io.ktor.server.http.content.staticResources
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
        staticResources("/assets", "assets")

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
            call.respondText("maestro scaffold is running")
        }

        get("/privacy") {
            val html = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>Privacy Policy — Maestro</title>
<style>
    body { font-family: 'Inter', -apple-system, sans-serif; background: #030408; color: #F0F4FA; max-width: 680px; margin: 0 auto; padding: 40px 20px; line-height: 1.75; }
    h1 { font-size: 28px; margin-bottom: 8px; }
    h2 { font-size: 18px; margin-top: 28px; margin-bottom: 8px; color: #C8D6F0; }
    p { font-size: 14px; color: #9EAEC6; }
    a { color: #C8D6F0; }
    .back { display: inline-block; margin-top: 32px; color: #C8D6F0; text-decoration: none; font-size: 13px; }
</style>
</head>
<body>
<h1>Privacy Policy</h1>
<p><strong>Effective date:</strong> February 2026</p>

<h2>What data we access</h2>
<p>When you connect your Strava account, Maestro requests read-only access to your activity data (distance, duration, heart rate) from the last 45 days. We access your athlete profile name solely for display purposes.</p>

<h2>How data is used</h2>
<p>Your activity data is used exclusively to compute training load metrics (CTL, ATL, TSB, ACWR) and generate AI-powered workout prescriptions. Data is processed in-memory and cached for up to 1 hour to reduce API calls.</p>

<h2>What we store</h2>
<p>We store an encrypted OAuth token locally to maintain your Strava connection. We do not store your activity data, personal information, or any training history permanently. No data is written to any external database.</p>

<h2>Data sharing</h2>
<p>We do not sell, share, or transfer your data to any third party. Your activity data is sent to Google Gemini's API solely for the purpose of generating personalised workout prescriptions. No personally identifiable information is included in these requests.</p>

<h2>Disconnecting</h2>
<p>You can disconnect your Strava account at any time using the "Disconnect" button in the dashboard. This immediately deletes your stored OAuth token and clears all cached data. You can also revoke access from <a href="https://www.strava.com/settings/apps">Strava's application settings</a>.</p>

<h2>Contact</h2>
<p>For privacy questions, open an issue on the <a href="https://github.com/manol-dimitrov/strava-data-analyser">project repository</a>.</p>

<a class="back" href="/">&larr; Back to dashboard</a>
</body>
</html>
""".trimIndent()
            call.respondText(html, io.ktor.http.ContentType.Text.Html)
        }

        get("/terms") {
            val html = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>Terms of Service — Maestro</title>
<style>
    body { font-family: 'Inter', -apple-system, sans-serif; background: #030408; color: #F0F4FA; max-width: 680px; margin: 0 auto; padding: 40px 20px; line-height: 1.75; }
    h1 { font-size: 28px; margin-bottom: 8px; }
    h2 { font-size: 18px; margin-top: 28px; margin-bottom: 8px; color: #C8D6F0; }
    p { font-size: 14px; color: #9EAEC6; }
    a { color: #C8D6F0; }
    .back { display: inline-block; margin-top: 32px; color: #C8D6F0; text-decoration: none; font-size: 13px; }
</style>
</head>
<body>
<h1>Terms of Service</h1>
<p><strong>Effective date:</strong> February 2026</p>

<h2>Service description</h2>
<p>Maestro is a non-commercial, open-source AI endurance coaching tool. It analyses training data from Strava and generates workout prescriptions using the Banister impulse-response model and AI language models.</p>

<h2>Not medical advice</h2>
<p>Maestro provides training suggestions based on mathematical models and AI inference. It is not a substitute for professional medical advice, diagnosis, or treatment. Always consult a qualified healthcare provider before making changes to your training programme.</p>

<h2>Data and Strava</h2>
<p>Maestro accesses your Strava data in accordance with the <a href="https://www.strava.com/legal/api">Strava API Agreement</a>. You can revoke access at any time. See our <a href="/privacy">Privacy Policy</a> for details on data handling.</p>

<h2>Availability</h2>
<p>Maestro is provided "as is" without warranty. We do not guarantee uptime, accuracy of training prescriptions, or availability of any specific feature.</p>

<h2>Intellectual property</h2>
<p>Strava and the Strava logo are trademarks of Strava, Inc. Maestro is not affiliated with or endorsed by Strava.</p>

<a class="back" href="/">&larr; Back to dashboard</a>
</body>
</html>
""".trimIndent()
            call.respondText(html, io.ktor.http.ContentType.Text.Html)
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
                val state = sessionRegistry.issueOAuthState(session.id)
                call.respond(mapOf("url" to oauth.buildAuthorizationUrl(state = state)))
            }
        }

        get("/api/strava/exchange") {
            val code = call.request.queryParameters["code"]
            val state = call.request.queryParameters["state"]
            val sessionId = call.request.cookies[SESSION_COOKIE]

            if (sessionId.isNullOrBlank()) {
                call.respond(mapOf("ok" to false, "error" to "Missing session cookie. Restart OAuth from this browser."))
                return@get
            }

            val session = sessionRegistry.get(sessionId)
                ?: run {
                    call.respond(mapOf("ok" to false, "error" to "Session expired. Restart OAuth from dashboard."))
                    return@get
                }

            val oauth = session.oauthService

            if (oauth == null) {
                call.respond(mapOf("ok" to false, "error" to "Strava is not configured"))
                return@get
            }

            if (code.isNullOrBlank()) {
                call.respond(mapOf("ok" to false, "error" to "Missing code query parameter"))
                return@get
            }

            if (state.isNullOrBlank()) {
                call.respond(mapOf("ok" to false, "error" to "Missing OAuth state parameter"))
                return@get
            }

            if (!sessionRegistry.consumeOAuthState(session.id, state)) {
                call.respond(mapOf("ok" to false, "error" to "Invalid or expired OAuth state. Restart OAuth from dashboard."))
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
                val state = sessionRegistry.issueOAuthState(session.id)
                call.respondRedirect(oauth.buildAuthorizationUrl(state = state))
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
