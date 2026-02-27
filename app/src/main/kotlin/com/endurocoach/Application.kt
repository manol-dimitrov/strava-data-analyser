package com.endurocoach

import com.endurocoach.config.readRuntimeConfig
import com.endurocoach.data.EncryptedFileTokenStore
import com.endurocoach.data.EncryptedFileAthleteProfileStore
import com.endurocoach.domain.Activity
import com.endurocoach.domain.LlmChatClient
import com.endurocoach.domain.LlmStructuredClient
import com.endurocoach.domain.LoadSnapshot
import com.endurocoach.domain.WorkoutChatRequest
import com.endurocoach.domain.WorkoutRequest
import com.endurocoach.metrics.BanisterTrimpCalculator
import com.endurocoach.metrics.DemoActivityGenerator
import com.endurocoach.metrics.LoadSeriesService
import com.endurocoach.llm.GeminiClient
import com.endurocoach.llm.GeminiConfig
import com.endurocoach.llm.PerplexityClient
import com.endurocoach.llm.PerplexityConfig
import com.endurocoach.routes.DashboardDependencies
import com.endurocoach.routes.McpDependencies
import com.endurocoach.routes.installDashboardRoutes
import com.endurocoach.routes.installMcpRoutes
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

@Serializable
data class AthleteProfileResponse(
    val sportFocus: String,
    val targetEventName: String,
    val targetEventDate: String,
    val maxHr: Int,
    val restingHr: Int,
    val onboardingCompleted: Boolean
)

@Serializable
data class WorkoutPlanResponse(
    val session: String,
    val coachReasoning: String,
    val generatedAt: String?,
    val source: String
)

@Serializable
data class WorkoutResponse(val workout: WorkoutPlanResponse?)

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }

    val runtimeConfig = readRuntimeConfig()
    val httpClient = HttpClientFactory.create()
    val tokenStore = EncryptedFileTokenStore()
    val athleteProfileStore = EncryptedFileAthleteProfileStore()
    val stravaConfig = StravaConfig.fromEnv()
    val llmClient = createLlmClient(runtimeConfig, httpClient)
    // GeminiClient implements both LlmStructuredClient and LlmChatClient;
    // fall back to a stub for other providers.
    val chatClient: LlmChatClient = (llmClient as? LlmChatClient)
        ?: UnavailableChatClient("Chat not available with the current LLM provider")
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
                chatClient = chatClient,
                philosophyRulePacks = runtimeConfig.philosophyRulePacks,
                sessionRegistry = sessionRegistry,
                stravaConfigured = stravaConfig != null,
                tokenStore = tokenStore,
                athleteProfileStore = athleteProfileStore,
                loadProvider = { repo, maxHr, restingHr ->
                    buildLoadSnapshot(
                        repository = repo,
                        days = 45,
                        maxHr = maxHr,
                        restingHr = restingHr
                    )
                }
            )
        )

        installMcpRoutes(
            McpDependencies(
                sessionRegistry = sessionRegistry,
                loadProvider = { repo, days, maxHr, restingHr ->
                    buildLoadSnapshot(repository = repo, days = days, maxHr = maxHr, restingHr = restingHr)
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
<link rel="preconnect" href="https://fonts.googleapis.com" />
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
<link href="https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:opsz,wght@10..48,700;10..48,800&family=IBM+Plex+Sans:wght@400;500;600&display=swap" rel="stylesheet" />
<style>
    :root {
        --bg: #13181F;
        --surface: #1E2535;
        --line-soft: #2B3652;
        --text: #EDF0F7;
        --text-soft: #A8B8CC;
        --text-muted: #6E83A0;
        --violet: #9B91FF;
        --max-w: 680px;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
        font-family: 'IBM Plex Sans', sans-serif;
        background:
            radial-gradient(860px 480px at 108% -8%, rgba(63,108,160,.14), transparent 60%),
            linear-gradient(180deg, #161D2B 0%, var(--bg) 100%);
        color: var(--text);
        min-height: 100vh;
        line-height: 1.7;
    }
    .legal-topbar {
        max-width: var(--max-w);
        margin: 20px auto 0;
        padding: 0 20px;
    }
    .back-link {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        color: var(--text-muted);
        text-decoration: none;
        font-size: 13px;
        font-weight: 500;
        transition: color .15s ease;
    }
    .back-link:hover { color: var(--text-soft); }
    .legal-body {
        max-width: var(--max-w);
        margin: 32px auto 80px;
        padding: 0 20px;
    }
    h1 {
        font-family: 'Bricolage Grotesque', sans-serif;
        font-size: 32px;
        font-weight: 800;
        letter-spacing: -.02em;
        margin-bottom: 6px;
    }
    .effective { color: var(--text-muted); font-size: 13px; margin-bottom: 36px; }
    h2 {
        font-family: 'Bricolage Grotesque', sans-serif;
        font-size: 16px;
        font-weight: 700;
        color: var(--text-soft);
        margin-top: 32px;
        margin-bottom: 8px;
        padding-bottom: 7px;
        border-bottom: 1px solid var(--line-soft);
    }
    p { font-size: 14px; color: var(--text-soft); margin-top: 8px; }
    a { color: var(--violet); text-decoration: none; }
    a:hover { text-decoration: underline; }
</style>
</head>
<body>
<div class="legal-topbar"><a class="back-link" href="/">&larr; Back to Maestro</a></div>
<div class="legal-body">
<h1>Privacy Policy</h1>
<p class="effective">Effective date: February 2026</p>

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
</div>
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
<link rel="preconnect" href="https://fonts.googleapis.com" />
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
<link href="https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:opsz,wght@10..48,700;10..48,800&family=IBM+Plex+Sans:wght@400;500;600&display=swap" rel="stylesheet" />
<style>
    :root {
        --bg: #13181F;
        --surface: #1E2535;
        --line-soft: #2B3652;
        --text: #EDF0F7;
        --text-soft: #A8B8CC;
        --text-muted: #6E83A0;
        --violet: #9B91FF;
        --max-w: 680px;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
        font-family: 'IBM Plex Sans', sans-serif;
        background:
            radial-gradient(860px 480px at 108% -8%, rgba(63,108,160,.14), transparent 60%),
            linear-gradient(180deg, #161D2B 0%, var(--bg) 100%);
        color: var(--text);
        min-height: 100vh;
        line-height: 1.7;
    }
    .legal-topbar {
        max-width: var(--max-w);
        margin: 20px auto 0;
        padding: 0 20px;
    }
    .back-link {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        color: var(--text-muted);
        text-decoration: none;
        font-size: 13px;
        font-weight: 500;
        transition: color .15s ease;
    }
    .back-link:hover { color: var(--text-soft); }
    .legal-body {
        max-width: var(--max-w);
        margin: 32px auto 80px;
        padding: 0 20px;
    }
    h1 {
        font-family: 'Bricolage Grotesque', sans-serif;
        font-size: 32px;
        font-weight: 800;
        letter-spacing: -.02em;
        margin-bottom: 6px;
    }
    .effective { color: var(--text-muted); font-size: 13px; margin-bottom: 36px; }
    h2 {
        font-family: 'Bricolage Grotesque', sans-serif;
        font-size: 16px;
        font-weight: 700;
        color: var(--text-soft);
        margin-top: 32px;
        margin-bottom: 8px;
        padding-bottom: 7px;
        border-bottom: 1px solid var(--line-soft);
    }
    p { font-size: 14px; color: var(--text-soft); margin-top: 8px; }
    a { color: var(--violet); text-decoration: none; }
    a:hover { text-decoration: underline; }
</style>
</head>
<body>
<div class="legal-topbar"><a class="back-link" href="/">&larr; Back to Maestro</a></div>
<div class="legal-body">
<h1>Terms of Service</h1>
<p class="effective">Effective date: February 2026</p>

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
</div>
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
                    // Cache the Strava athlete ID for this session so the profile
                    // can be loaded / saved without re-fetching on every request.
                    runCatching { oauth.fetchAthleteId() }
                        .onSuccess { id -> session.stravaAthleteId = id }
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

        get("/api/athlete") {
            val sessionId = call.request.cookies[SESSION_COOKIE]
            val session = sessionRegistry.getOrCreate(sessionId)
            val state = session.dashboardState.read()
            call.respond(
                AthleteProfileResponse(
                    sportFocus = state.onboarding.sportFocus,
                    targetEventName = state.onboarding.targetEventName,
                    targetEventDate = state.onboarding.targetEventDate,
                    maxHr = state.maxHr,
                    restingHr = state.restingHr,
                    onboardingCompleted = state.onboarding.completed
                )
            )
        }

        get("/api/workout") {
            val sessionId = call.request.cookies[SESSION_COOKIE]
            val session = sessionRegistry.getOrCreate(sessionId)
            val state = session.dashboardState.read()
            val workout = state.latestWorkout
            val plan = workout?.let {
                WorkoutPlanResponse(
                    session = it.session,
                    coachReasoning = it.coachReasoning,
                    generatedAt = state.generatedAt?.toString(),
                    source = state.source
                )
            }
            call.respond(WorkoutResponse(workout = plan))
        }

        get("/robots.txt") {
            call.respondText(
                """
                User-agent: *
                Allow: /

                User-agent: GPTBot
                Allow: /

                User-agent: ClaudeBot
                Allow: /

                User-agent: PerplexityBot
                Allow: /

                Sitemap: /llms.txt
                """.trimIndent(),
                io.ktor.http.ContentType.Text.Plain
            )
        }

        get("/llms.txt") {
            call.respondText(
                """
                # Maestro — AI Endurance Coach

                > Maestro analyses your Strava training data and prescribes daily workouts using the Banister impulse-response model and AI language models.

                ## Docs

                - [Privacy Policy](/privacy)
                - [Terms of Service](/terms)

                ## MCP Server

                - [POST /mcp](/mcp): Model Context Protocol (MCP) endpoint implementing the Streamable-HTTP transport (protocol version 2024-11-05). Supports `initialize`, `tools/list`, and `tools/call`. Each tool accepts an optional `sessionId` argument (value of the `enduro_session` cookie); omit it to receive demo data. Tools: `get_training_load`, `get_athlete_profile`, `get_workout_plan`.

                ## REST API

                - [GET /api/load](/api/load): Training load metrics (CTL, ATL, TSB, ACWR, monotony, CTL ramp rate, recent volume) with a daily time-series. Query params: `days` (7–120, default 45), `maxHr` (120–230, default 190), `restingHr` (30–90, default 50). Returns JSON `LoadSnapshotResponse`.
                - [GET /api/athlete](/api/athlete): Current athlete profile for the session — sport focus, target event name/date, heart-rate profile (maxHr, restingHr), and whether onboarding is complete. Returns JSON `AthleteProfileResponse`.
                - [GET /api/workout](/api/workout): Most recently generated workout plan for the session — session prescription, coach reasoning, generation timestamp, and data source. Returns JSON `WorkoutPlanResponse`, or `{"workout":null}` when no plan has been generated yet.
                - [GET /api/strava/status](/api/strava/status): Whether the current session has a connected Strava account. Returns `{"connected": true|false}`.
                - [GET /health](/health): Liveness probe. Returns plain text `maestro scaffold is running`.
                """.trimIndent(),
                io.ktor.http.ContentType.Text.Plain
            )
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

private class UnavailableChatClient(
    private val reason: String
) : LlmChatClient {
    override suspend fun chat(request: WorkoutChatRequest) = error(reason)
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
