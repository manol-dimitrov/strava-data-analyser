package com.endurocoach.routes

import com.endurocoach.domain.DailyCheckIn
import com.endurocoach.domain.LlmStructuredClient
import com.endurocoach.domain.LoadSnapshot
import com.endurocoach.domain.TokenStore
import com.endurocoach.domain.WorkoutPlan
import com.endurocoach.domain.WorkoutRequest
import com.endurocoach.session.SESSION_COOKIE
import com.endurocoach.session.SESSION_MAX_AGE
import com.endurocoach.session.SessionRegistry
import com.endurocoach.strava.CachedActivityRepository
import io.ktor.http.ContentType
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Instant

data class DashboardState(
    val checkIn: DailyCheckIn = DailyCheckIn(
        legFeeling = 6,
        mentalReadiness = 6,
        timeAvailableMinutes = 60,
        coachingPhilosophy = "balanced"
    ),
    val latestWorkout: WorkoutPlan? = null,
    val latestError: String? = null,
    val source: String = "demo",
    val generatedAt: Instant? = null
)

class DashboardStateStore {
    private var state: DashboardState = DashboardState()

    @Synchronized
    fun read(): DashboardState = state

    @Synchronized
    fun updateCheckIn(checkIn: DailyCheckIn) {
        state = state.copy(checkIn = checkIn)
    }

    @Synchronized
    fun updateWorkout(snapshotSource: String, workout: WorkoutPlan) {
        state = state.copy(
            source = snapshotSource,
            latestWorkout = workout,
            latestError = null,
            generatedAt = Instant.now()
        )
    }

    @Synchronized
    fun updateError(snapshotSource: String, error: String) {
        state = state.copy(
            source = snapshotSource,
            latestError = error,
            generatedAt = Instant.now()
        )
    }
}

data class DashboardDependencies(
    val templateHtml: String,
    val llmClient: LlmStructuredClient,
    val philosophyRulePacks: Map<String, String>,
    val sessionRegistry: SessionRegistry,
    val stravaConfigured: Boolean = false,
    val tokenStore: TokenStore,
    val loadProvider: suspend (CachedActivityRepository?) -> Pair<String, LoadSnapshot>
)

fun Route.installDashboardRoutes(dependencies: DashboardDependencies) {
    get("/") {
        val sessionId = call.request.cookies[SESSION_COOKIE]
        val session = dependencies.sessionRegistry.getOrCreate(sessionId)
        if (sessionId != session.id) {
            call.response.cookies.append(name = SESSION_COOKIE, value = session.id, path = "/", maxAge = SESSION_MAX_AGE)
        }

        // Check for a valid persisted token
        val hasToken = dependencies.tokenStore.loadToken(session.tokenStoreKey) != null
        var stravaConnected = dependencies.stravaConfigured && hasToken

        // If we think we have a token, validate it (catches revoked / expired-unrefreshable tokens)
        if (stravaConnected && session.oauthService != null) {
            stravaConnected = runCatching { session.oauthService.validAccessToken() }
                .map { true }
                .getOrElse {
                    // Token is stale — clean it up
                    dependencies.tokenStore.deleteToken(session.tokenStoreKey)
                    session.activityRepository?.invalidate()
                    false
                }
        }

        // ----- GATE: unauthenticated users see the welcome hero -----
        if (!stravaConnected) {
            val stravaAuthUrl = session.oauthService?.buildAuthorizationUrl(state = session.id)
            val html = renderWelcome(
                template = dependencies.templateHtml,
                stravaConfigured = dependencies.stravaConfigured,
                stravaAuthUrl = stravaAuthUrl
            )
            call.respondText(html, ContentType.Text.Html)
            return@get
        }

        // ----- Authenticated: load data and render the full dashboard -----
        val state = session.dashboardState.read()
        val (source, snapshot) = dependencies.loadProvider(session.activityRepository)

        val html = renderDashboard(
            template = dependencies.templateHtml,
            state = state,
            source = source,
            snapshot = snapshot,
            philosophyRulePacks = dependencies.philosophyRulePacks,
            stravaConnected = true,
            stravaAuthUrl = null
        )

        call.respondText(html, ContentType.Text.Html)
    }

    post("/dashboard/checkin") {
        val sessionId = call.request.cookies[SESSION_COOKIE]
        val session = dependencies.sessionRegistry.getOrCreate(sessionId)
        if (sessionId != session.id) {
            call.response.cookies.append(name = SESSION_COOKIE, value = session.id, path = "/", maxAge = SESSION_MAX_AGE)
        }
        val parsed = parseCheckIn(
            params = call.receiveParameters(),
            philosophyRulePacks = dependencies.philosophyRulePacks
        )
        session.dashboardState.updateCheckIn(parsed)
        call.respondRedirect("/")
    }

    post("/dashboard/generate") {
        val sessionId = call.request.cookies[SESSION_COOKIE]
        val session = dependencies.sessionRegistry.getOrCreate(sessionId)
        if (sessionId != session.id) {
            call.response.cookies.append(name = SESSION_COOKIE, value = session.id, path = "/", maxAge = SESSION_MAX_AGE)
        }
        val parsed = parseCheckIn(
            params = call.receiveParameters(),
            philosophyRulePacks = dependencies.philosophyRulePacks
        )
        session.dashboardState.updateCheckIn(parsed)

        val (source, snapshot) = dependencies.loadProvider(session.activityRepository)
        val request = WorkoutRequest(
            checkIn = parsed.copy(
                coachingPhilosophy = dependencies.philosophyRulePacks[parsed.coachingPhilosophy]
                    ?: dependencies.philosophyRulePacks.getValue("balanced")
            ),
            currentTsb = snapshot.tsb,
            currentCtl = snapshot.ctl,
            currentAtl = snapshot.atl,
            recentVolumeMinutes = snapshot.recentVolumeMinutes,
            acwr = snapshot.acwr,
            monotony = snapshot.monotony,
            ctlRampRate = snapshot.ctlRampRate,
            spike10 = snapshot.spike10,
            strain10 = snapshot.strain10
        )

        runCatching { dependencies.llmClient.generateWorkoutPlan(request) }
            .onSuccess { session.dashboardState.updateWorkout(source, it) }
            .onFailure {
                session.dashboardState.updateError(
                    source,
                    it.message ?: "Workout generation failed"
                )
            }

        call.respondRedirect("/")
    }
}

private fun parseCheckIn(
    params: io.ktor.http.Parameters,
    philosophyRulePacks: Map<String, String>
): DailyCheckIn {
    val selected = params["coachingPhilosophy"]
        ?.takeIf { philosophyRulePacks.containsKey(it) }
        ?: "balanced"

    return DailyCheckIn(
        legFeeling = params["legFeeling"]?.toIntOrNull()?.coerceIn(1, 10) ?: 6,
        mentalReadiness = params["mentalReadiness"]?.toIntOrNull()?.coerceIn(1, 10) ?: 6,
        timeAvailableMinutes = params["timeAvailableMinutes"]?.toIntOrNull()?.coerceIn(15, 240) ?: 60,
        coachingPhilosophy = selected
    )
}

/**
 * Render the welcome / landing page for unauthenticated visitors.
 * The template {{dashboardContent}} block is replaced with the welcome hero,
 * and all metric/chart tokens are replaced with empty strings so they don't
 * leak raw {{token}} text.
 */
private fun renderWelcome(
    template: String,
    stravaConfigured: Boolean,
    stravaAuthUrl: String?
): String {
    val connectButton = if (stravaAuthUrl != null) {
        """<a class="welcome-cta" href="${escapeHtml(stravaAuthUrl)}">
    <svg width="20" height="20" viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M13.5 6L10 13l-2-4H4l6 11 6-11h-2.5z" fill="currentColor"/></svg>
    Connect with Strava
</a>"""
    } else {
        """<p class="welcome-note">Strava integration is not configured. Set <code>STRAVA_CLIENT_ID</code> and <code>STRAVA_CLIENT_SECRET</code> environment variables to enable.</p>"""
    }

    val welcomeHtml = """
<div class="welcome-hero">
    <div class="welcome-icon">EC</div>
    <h1 class="welcome-title">Enduro Coach</h1>
    <p class="welcome-sub">Science-backed training load monitoring and AI-powered daily workout prescriptions — built on the Banister impulse-response model, Gabbett ACWR, and Foster strain metrics.</p>
    $connectButton
    <div class="welcome-features">
        <div class="wf"><span class="wf-icon">&#x1F4CA;</span><span>Fitness, fatigue &amp; readiness tracking</span></div>
        <div class="wf"><span class="wf-icon">&#x26A1;</span><span>10-day spike &amp; strain alerts</span></div>
        <div class="wf"><span class="wf-icon">&#x1F3CB;</span><span>AI-generated daily sessions</span></div>
        <div class="wf"><span class="wf-icon">&#x1F6B4;</span><span>All cardio activities supported</span></div>
    </div>
</div>
""".trimIndent()

    val welcomeStravaBar = if (!stravaConfigured) {
        """<span class="strava-status"><span class="dot disconnected"></span>Not configured</span>"""
    } else {
        """<span class="strava-status"><span class="dot disconnected"></span>Not connected</span>"""
    }

    return template
        .replace("{{stravaBar}}", welcomeStravaBar)
        .replace("{{dashboardContent}}", welcomeHtml)
        // Remove the authenticated dashboard content between markers
        .replace(Regex("<!-- DASHBOARD_START -->.*<!-- DASHBOARD_END -->", RegexOption.DOT_MATCHES_ALL), "")
        // Zero out any remaining tokens (metric values, badges, etc.)
        .replace(Regex("\\{\\{[a-zA-Z0-9]+}}"), "")
}

private fun renderDashboard(
    template: String,
    state: DashboardState,
    source: String,
    snapshot: LoadSnapshot,
    philosophyRulePacks: Map<String, String>,
    stravaConnected: Boolean = false,
    stravaAuthUrl: String? = null
): String {
    val options = philosophyRulePacks.keys.joinToString("\n") { key ->
        val selected = if (state.checkIn.coachingPhilosophy == key) "selected" else ""
        "<option value=\"${escapeHtml(key)}\" $selected>${escapeHtml(key.replaceFirstChar(Char::uppercaseChar))}</option>"
    }

    val workoutCard = state.latestWorkout?.let {
        """
<div class="workout-section"><div class="ws-header"><span class="ws-icon">&#x1F525;</span><span class="ws-label">Warm-up</span></div><p>${escapeHtml(it.warmup)}</p></div>
<div class="workout-section"><div class="ws-header"><span class="ws-icon">&#x26A1;</span><span class="ws-label">Main Set</span></div><p>${escapeHtml(it.mainSet)}</p></div>
<div class="workout-section"><div class="ws-header"><span class="ws-icon">&#x1F9CA;</span><span class="ws-label">Cool-down</span></div><p>${escapeHtml(it.cooldown)}</p></div>
<div class="reasoning-block"><div class="ws-header"><span class="ws-icon">&#x1F9E0;</span><span class="ws-label">Coach Reasoning</span></div><p>${escapeHtml(it.coachReasoning)}</p></div>
<p class="generated-at">Generated at: ${state.generatedAt ?: "-"}</p>
""".trimIndent()
    } ?: """<div class="workout-empty"><div class="empty-icon">&#x1F3C3;</div>No workout generated yet.<br/>Complete check-in and press <strong>Generate workout</strong>.</div>"""

    val errorCard = state.latestError?.let {
        "<div class=\"error\"><strong>Generation error:</strong> ${escapeHtml(it)}</div>"
    } ?: ""

    // TSB contextual interpretation (Mujika & Busso research-based ranges)
    val tsb = snapshot.tsb
    val tsbBadgeClass = when {
        tsb > 25 -> "fatigued"   // detraining risk
        tsb > 10 -> "fresh"      // tapered / race-ready
        tsb > -10 -> "neutral"   // productive training
        tsb > -25 -> "neutral"   // build phase — expected
        else -> "fatigued"       // overreaching risk
    }
    val tsbBadgeLabel = when {
        tsb > 25 -> "Detraining"
        tsb > 10 -> "Race-ready"
        tsb > 0 -> "Fresh"
        tsb > -10 -> "Productive"
        tsb > -25 -> "Building"
        else -> "Overreaching"
    }
    val tsbExplain = when {
        tsb > 25 -> "Highly positive \u2014 extended freshness may indicate insufficient training stimulus. Fitness (CTL) will erode if load isn\u2019t restored."
        tsb > 10 -> "Positive \u2014 classic taper zone. Fatigue has dissipated while fitness is retained. Ideal for racing or a quality breakthrough session."
        tsb > 0 -> "Mildly positive \u2014 fresh with fitness intact. A good window for moderate-to-hard work or a progressive long run."
        tsb > -10 -> "Near zero \u2014 the productive training sweet spot (Mujika & Busso). Load and recovery are balanced. This is where consistent adaptation happens."
        tsb > -25 -> "Moderately negative \u2014 normal during a build block. Functional overreaching is expected here. Monitor subjective readiness and keep recovery quality high."
        else -> "Deeply negative \u2014 sustained load at this level risks non-functional overreaching. Consider a planned unload week if this persists beyond 7\u201310 days."
    }

    // CTL contextual interpretation
    val ctl = snapshot.ctl
    val ctlBadgeClass = when {
        ctl > 60 -> "fresh"     // strong base
        ctl > 30 -> "neutral"   // moderate
        else -> "fatigued"      // developing
    }
    val ctlBadgeLabel = when {
        ctl > 80 -> "Very strong"
        ctl > 60 -> "Strong"
        ctl > 30 -> "Moderate"
        ctl > 15 -> "Building"
        else -> "Developing"
    }
    val ctlExplain = when {
        ctl > 60 -> "Solid chronic fitness base. Your body has adapted to sustained training loads. Maintain with consistent stimulus and periodised recovery."
        ctl > 30 -> "Moderate fitness level with room to grow. Progressive overload over the coming weeks will push this higher. Consistency is key."
        ctl > 15 -> "Fitness is building. Early adaptation phase \u2014 stay patient, keep sessions regular, and prioritise aerobic base work."
        else -> "Low chronic load \u2014 either early in a training block or returning from a break. Build gradually with easy volume."
    }

    // ATL contextual interpretation (relative to CTL — ACWR-informed)
    val atl = snapshot.atl
    val acwr = snapshot.acwr
    val atlBadgeClass = when {
        acwr > 1.5 -> "fatigued"    // spike zone (Gabbett)
        acwr > 1.3 -> "neutral"     // high but manageable
        acwr > 0.8 -> "fresh"       // sweet spot (Gabbett)
        else -> "neutral"            // underloading
    }
    val atlBadgeLabel = when {
        acwr > 1.5 -> "Spike"
        acwr > 1.3 -> "Elevated"
        acwr > 0.8 -> "Sweet spot"
        else -> "Low"
    }
    val atlExplain = when {
        acwr > 1.5 -> "Acute load is spiking well above chronic fitness (ACWR > 1.5). Gabbett\u2019s research links this zone to elevated injury risk. Scale back intensity or volume."
        acwr > 1.3 -> "Acute load moderately exceeds chronic fitness (ACWR ${"%.2f".format(acwr)}). Acceptable during a planned build block, but don\u2019t sustain this for more than 1\u20132 weeks."
        acwr > 0.8 -> "Acute and chronic loads are well-balanced (ACWR ${"%.2f".format(acwr)}). This is Gabbett\u2019s \u2018sweet spot\u2019 (0.8\u20131.3) \u2014 optimal for progressive adaptation with managed injury risk."
        else -> "Low recent load relative to fitness (ACWR ${"%.2f".format(acwr)}). Recovery or taper phase. Freshness is building, but extended underloading will reduce fitness."
    }

    // Volume contextual interpretation
    val vol = snapshot.recentVolumeMinutes
    val volBadgeClass = when {
        vol > 420 -> "fatigued"  // > 7hrs
        vol > 240 -> "neutral"   // 4-7hrs
        else -> "fresh"          // < 4hrs
    }
    val volBadgeLabel = when {
        vol > 600 -> "Very high"
        vol > 420 -> "High"
        vol > 240 -> "Moderate"
        vol > 120 -> "Light"
        else -> "Minimal"
    }
    val volExplain = when {
        vol > 600 -> "Over 10 hours in 7 days \u2014 heavy training block. Ensure recovery quality matches the volume."
        vol > 420 -> "7+ hours in 7 days \u2014 solid volume. Watch for compounding fatigue across the week."
        vol > 240 -> "4\u20137 hours in 7 days \u2014 moderate volume with room to push if readiness supports it."
        vol > 120 -> "2\u20134 hours in 7 days \u2014 lighter week. Good for recovery or early base-building."
        else -> "Under 2 hours \u2014 very light. Either planned recovery or returning from a break."
    }

    val sourceBadge = if (source == "strava") {
        "<span class=\"badge strava-badge\">Strava</span>"
    } else {
        "<span class=\"badge demo-badge\">Demo data</span>"
    }

    // Topbar-inline strava status (no wrapping div — rendered inside .topbar-right)
    val stravaBar = if (stravaConnected) {
        val statusText = if (source == "strava") "Cardio activities synced" else "Connected — no recent activities"
        """<span class="strava-status"><span class="dot connected"></span>$statusText</span><a class="btn-disconnect" href="/api/strava/disconnect">Disconnect</a>"""
    } else if (stravaAuthUrl != null) {
        """<span class="strava-status"><span class="dot disconnected"></span>Not connected</span><a class="btn-connect" href="${escapeHtml(stravaAuthUrl)}">Connect Strava</a>"""
    } else {
        """<span class="strava-status"><span class="dot disconnected"></span>Demo mode</span>"""
    }

    // SVG load chart
    val loadChart = renderLoadChart(snapshot)

    // ACWR badge
    val acwrBadgeClass = when {
        acwr > 1.5 -> "fatigued"
        acwr > 1.3 -> "neutral"
        acwr > 0.8 -> "fresh"
        else -> "neutral"
    }
    val acwrBadgeLabel = when {
        acwr > 1.5 -> "Danger zone"
        acwr > 1.3 -> "Caution"
        acwr > 0.8 -> "Sweet spot"
        else -> "Underloading"
    }
    val acwrExplain = when {
        acwr > 1.5 -> "ACWR > 1.5 \u2014 acute load is spiking relative to your chronic base. Gabbett\u2019s research shows this range significantly elevates injury risk. Reduce load."
        acwr > 1.3 -> "ACWR in the upper range (1.3\u20131.5). Manageable during a planned overload, but don\u2019t sustain for more than 10 days without an unload."
        acwr > 0.8 -> "ACWR in the optimal zone (0.8\u20131.3). Training stimulus is progressive relative to your fitness base. This is where the best adaptation-to-risk ratio lives."
        else -> "ACWR below 0.8 \u2014 current load is well below your established fitness. Good for planned recovery; extended periods here will cause detraining."
    }

    // Monotony badge (Foster 1998)
    val mono = snapshot.monotony
    val monoBadgeClass = when {
        mono > 2.0 -> "fatigued"
        mono > 1.5 -> "neutral"
        else -> "fresh"
    }
    val monoBadgeLabel = when {
        mono > 2.5 -> "Very high"
        mono > 2.0 -> "High"
        mono > 1.5 -> "Moderate"
        else -> "Good variety"
    }
    val monoExplain = when {
        mono > 2.0 -> "Training monotony > 2.0 (Foster 1998). Daily loads are too uniform — polarise your training with hard/easy variation to reduce injury and illness risk."
        mono > 1.5 -> "Moderate monotony. Some variation exists, but consider adding more contrast between hard and easy days for better adaptation."
        else -> "Good training variation. Your hard and easy days are well-differentiated, which supports recovery and reduces overuse risk."
    }

    // 10-day spike ratio badge
    val spike10 = snapshot.spike10
    val spike10BadgeClass = when {
        spike10 > 1.5 -> "fatigued"
        spike10 > 1.3 -> "neutral"
        spike10 > 0.8 -> "fresh"
        else -> "neutral"
    }
    val spike10BadgeLabel = when {
        spike10 > 1.5 -> "Spike risk"
        spike10 > 1.3 -> "Elevated"
        spike10 > 0.8 -> "Normal"
        else -> "Low"
    }
    val spike10Explain = when {
        spike10 > 1.5 -> "10-day load is significantly above your daily average (×${{"%.2f".format(spike10)}}). Short-term spikes above 1.5× baseline elevate injury risk even when ACWR looks safe."
        spike10 > 1.3 -> "10-day load is moderately above baseline (×${{"%.2f".format(spike10)}}). Manageable during a planned build block but don't sustain for more than 1–2 weeks."
        spike10 > 0.8 -> "10-day load is well-matched to your training baseline (×${{"%.2f".format(spike10)}}). Good progressive loading."
        else -> "10-day load is below your typical baseline (×${{"%.2f".format(spike10)}}). Planned recovery or returning from a break."
    }

    // 10-day Foster strain badge
    val strain10 = snapshot.strain10
    val strain10BadgeClass = when {
        strain10 > 700 -> "fatigued"
        strain10 > 400 -> "neutral"
        else -> "fresh"
    }
    val strain10BadgeLabel = when {
        strain10 > 700 -> "High strain"
        strain10 > 400 -> "Moderate"
        else -> "Low"
    }
    val strain10Explain = when {
        strain10 > 700 -> "High 10-day strain (${{"%.0f".format(strain10)}}). Accumulated load combined with low variety is elevated. Prioritise hard/easy contrast and ensure sleep quality."
        strain10 > 400 -> "Moderate 10-day strain (${{"%.0f".format(strain10)}}). Training is productive but watch for compounding fatigue if this persists."
        else -> "Low strain (${{"%.0f".format(strain10)}}). Good hard/easy variation and manageable load over the recent 10 days."
    }

    // Hero card supplementary vars
    val tsbHeroClass = when {
        tsb > 10 -> "positive"
        tsb > 0 -> "neutral"
        tsb > -20 -> "negative-ok"
        else -> "negative-risk"
    }
    val tsbHeroMessage = when {
        tsb > 25 -> "Keep it steady — your fitness base may be fading"
        tsb > 10 -> "Primed to push hard or race"
        tsb > 0 -> "Fresh with solid fitness — a good day to perform"
        tsb > -10 -> "In the build zone — this is where improvement happens"
        tsb > -25 -> "Deep build phase — recovery quality matters now"
        else -> "Heavy load — factor in a recovery week soon"
    }
    val tsbHeroStatusBadge = "<div class=\"hero-status-badge $tsbBadgeClass\">$tsbBadgeLabel</div>"

    return template
        .replace("{{source}}", escapeHtml(source))
        .replace("{{ctl}}", format(snapshot.ctl))
        .replace("{{atl}}", format(snapshot.atl))
        .replace("{{tsb}}", format(snapshot.tsb))
        .replace("{{tsbBadge}}", "<span class=\"badge $tsbBadgeClass\">$tsbBadgeLabel</span>")
        .replace("{{tsbExplain}}", escapeHtml(tsbExplain))
        .replace("{{tsbHeroClass}}", tsbHeroClass)
        .replace("{{tsbHeroMessage}}", escapeHtml(tsbHeroMessage))
        .replace("{{tsbHeroStatusBadge}}", tsbHeroStatusBadge)
        .replace("{{ctlBadge}}", "<span class=\"badge $ctlBadgeClass\">$ctlBadgeLabel</span>")
        .replace("{{ctlExplain}}", escapeHtml(ctlExplain))
        .replace("{{atlBadge}}", "<span class=\"badge $atlBadgeClass\">$atlBadgeLabel</span>")
        .replace("{{atlExplain}}", escapeHtml(atlExplain))
        .replace("{{volumeBadge}}", "<span class=\"badge $volBadgeClass\">$volBadgeLabel</span>")
        .replace("{{volumeExplain}}", escapeHtml(volExplain))
        .replace("{{sourceBadge}}", sourceBadge)
        .replace("{{stravaBar}}", stravaBar)
        .replace("{{loadChart}}", loadChart)
        .replace("{{volume}}", format(snapshot.recentVolumeMinutes))
        .replace("{{acwr}}", "%.2f".format(snapshot.acwr))
        .replace("{{acwrBadge}}", "<span class=\"badge $acwrBadgeClass\">$acwrBadgeLabel</span>")
        .replace("{{acwrExplain}}", escapeHtml(acwrExplain))
        .replace("{{monotony}}", "%.1f".format(snapshot.monotony))
        .replace("{{monotonyBadge}}", "<span class=\"badge $monoBadgeClass\">$monoBadgeLabel</span>")
        .replace("{{monotonyExplain}}", escapeHtml(monoExplain))
        .replace("{{spike10}}", "%.2f".format(snapshot.spike10))
        .replace("{{spike10Badge}}", "<span class=\"badge $spike10BadgeClass\">$spike10BadgeLabel</span>")
        .replace("{{spike10Explain}}", escapeHtml(spike10Explain))
        .replace("{{strain10}}", "%.0f".format(snapshot.strain10))
        .replace("{{strain10Badge}}", "<span class=\"badge $strain10BadgeClass\">$strain10BadgeLabel</span>")
        .replace("{{strain10Explain}}", escapeHtml(strain10Explain))
        .replace("{{legFeeling}}", state.checkIn.legFeeling.toString())
        .replace("{{mentalReadiness}}", state.checkIn.mentalReadiness.toString())
        .replace("{{timeAvailableMinutes}}", state.checkIn.timeAvailableMinutes.toString())
        .replace("{{philosophyOptions}}", options)
        .replace("{{workoutCard}}", workoutCard)
        .replace("{{errorCard}}", errorCard)
        // dashboardContent is only used by the welcome flow; in the full dashboard it's a no-op
        .replace("{{dashboardContent}}", "")
        .replace("<!-- DASHBOARD_START -->", "")
        .replace("<!-- DASHBOARD_END -->", "")
}

private fun format(value: Double): String = "%.1f".format(value)

private fun escapeHtml(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

private fun renderLoadChart(snapshot: LoadSnapshot): String {
    val series = snapshot.series
    if (series.size < 2) return "<p style=\"color:#8b909a;font-size:13px;\">Not enough data points for chart.</p>"

    val w = 1000.0
    val h = 220.0
    val padL = 44.0
    val padR = 12.0
    val padT = 16.0
    val padB = 28.0
    val chartW = w - padL - padR
    val chartH = h - padT - padB

    val allValues = series.flatMap { listOf(it.ctl, it.atl, it.tsb) }
    val minVal = (allValues.minOrNull() ?: 0.0).let { kotlin.math.min(it, -10.0) }
    val maxVal = (allValues.maxOrNull() ?: 100.0).let { kotlin.math.max(it, 10.0) }
    val range = maxVal - minVal
    val safeRange = if (range < 1.0) 1.0 else range

    fun xPos(i: Int): Double = padL + (i.toDouble() / (series.size - 1)) * chartW
    fun yPos(v: Double): Double = padT + (1.0 - (v - minVal) / safeRange) * chartH

    fun polyline(values: List<Double>, color: String, strokeWidth: Double = 2.0, dashed: Boolean = false): String {
        val points = values.mapIndexed { i, v -> "${fmt2(xPos(i))},${fmt2(yPos(v))}" }.joinToString(" ")
        val dashAttr = if (dashed) """ stroke-dasharray="6,4"""" else ""
        return """<polyline points="$points" fill="none" stroke="$color" stroke-width="$strokeWidth"$dashAttr stroke-linejoin="round" stroke-linecap="round"/>"""
    }

    // Zero line y position
    val zeroY = fmt2(yPos(0.0))

    // Y-axis labels
    val ySteps = 5
    val yLabels = (0..ySteps).map { i ->
        val v = minVal + (safeRange * i / ySteps)
        val y = yPos(v)
        """<text x="${fmt2(padL - 6)}" y="${fmt2(y + 3.5)}" text-anchor="end" fill="#8b909a" font-size="10">${"%.0f".format(v)}</text>"""
    }.joinToString("\n    ")

    // X-axis date labels (show ~6 labels)
    val labelCount = minOf(6, series.size)
    val xLabels = (0 until labelCount).map { i ->
        val idx = if (labelCount <= 1) 0 else i * (series.size - 1) / (labelCount - 1)
        val x = xPos(idx)
        val dateStr = series[idx].date.let { "${it.monthValue}/${it.dayOfMonth}" }
        """<text x="${fmt2(x)}" y="${fmt2(h - 4)}" text-anchor="middle" fill="#8b909a" font-size="10">$dateStr</text>"""
    }.joinToString("\n    ")

    val ctlLine = polyline(series.map { it.ctl }, "#1a73e8", 2.2)
    val atlLine = polyline(series.map { it.atl }, "#fc4c02", 2.2)
    val tsbLine = polyline(series.map { it.tsb }, "#0d9f5f", 1.5, dashed = true)

    return """
<svg viewBox="0 0 ${w.toInt()} ${h.toInt()}" xmlns="http://www.w3.org/2000/svg" style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;">
    <!-- Grid line at zero -->
    <line x1="${fmt2(padL)}" y1="$zeroY" x2="${fmt2(w - padR)}" y2="$zeroY" stroke="#e2e5ea" stroke-width="1" stroke-dasharray="4,3"/>
    <!-- Y labels -->
    $yLabels
    <!-- X labels -->
    $xLabels
    <!-- Data lines -->
    $ctlLine
    $atlLine
    $tsbLine
    <!-- End dots -->
    <circle cx="${fmt2(xPos(series.size - 1))}" cy="${fmt2(yPos(series.last().ctl))}" r="3.5" fill="#1a73e8"/>
    <circle cx="${fmt2(xPos(series.size - 1))}" cy="${fmt2(yPos(series.last().atl))}" r="3.5" fill="#fc4c02"/>
    <circle cx="${fmt2(xPos(series.size - 1))}" cy="${fmt2(yPos(series.last().tsb))}" r="3.5" fill="#0d9f5f"/>
</svg>
""".trimIndent()
}

private fun fmt2(v: Double): String = "%.1f".format(v)
