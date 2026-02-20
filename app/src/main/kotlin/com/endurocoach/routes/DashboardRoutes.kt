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

        val state = session.dashboardState.read()
        val hasToken = dependencies.tokenStore.loadToken(session.tokenStoreKey) != null
        val stravaConnected = dependencies.stravaConfigured && hasToken
        val stravaAuthUrl = if (!stravaConnected) {
            session.oauthService?.buildAuthorizationUrl(state = session.id)
        } else null

        val (source, snapshot) = dependencies.loadProvider(session.activityRepository)

        val html = renderDashboard(
            template = dependencies.templateHtml,
            state = state,
            source = source,
            snapshot = snapshot,
            philosophyRulePacks = dependencies.philosophyRulePacks,
            stravaConnected = stravaConnected,
            stravaAuthUrl = stravaAuthUrl
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
            recentVolumeMinutes = snapshot.recentVolumeMinutes
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

    // TSB contextual interpretation
    val tsb = snapshot.tsb
    val tsbBadgeClass = when {
        tsb > 5 -> "fresh"
        tsb > -10 -> "neutral"
        else -> "fatigued"
    }
    val tsbBadgeLabel = when {
        tsb > 15 -> "Very fresh"
        tsb > 5 -> "Fresh"
        tsb > -10 -> "Functional"
        tsb > -20 -> "Fatigued"
        else -> "High fatigue"
    }
    val tsbExplain = when {
        tsb > 15 -> "Strongly positive \u2014 well-rested relative to fitness. Good window for a quality session, time trial, or race."
        tsb > 5 -> "Mildly positive \u2014 freshness available. Suitable for moderate-to-hard work or a progressive long run."
        tsb > -10 -> "Near zero \u2014 load and recovery are roughly balanced. Normal productive training range for most athletes."
        tsb > -20 -> "Negative \u2014 accumulated fatigue exceeds freshness. Consider easier sessions or active recovery."
        else -> "Deeply negative \u2014 significant fatigue accumulation. Prioritise recovery to avoid non-functional overreaching."
    }

    // CTL contextual interpretation
    val ctl = snapshot.ctl
    val ctlBadgeClass = when {
        ctl > 80 -> "fresh"     // high fitness
        ctl > 40 -> "neutral"   // moderate
        else -> "fatigued"      // low
    }
    val ctlBadgeLabel = when {
        ctl > 100 -> "Very high"
        ctl > 80 -> "High"
        ctl > 40 -> "Moderate"
        ctl > 20 -> "Building"
        else -> "Low"
    }
    val ctlExplain = when {
        ctl > 80 -> "Strong chronic fitness base. Your body has adapted to sustained training loads. Maintain with consistent stimulus."
        ctl > 40 -> "Moderate fitness level. Room to build \u2014 progressive overload over the coming weeks will push this higher."
        ctl > 20 -> "Fitness is building. Early adaptation phase \u2014 consistency matters more than intensity right now."
        else -> "Low chronic load. Either early in a training block or returning from a break. Build gradually."
    }

    // ATL contextual interpretation
    val atl = snapshot.atl
    val atlBadgeClass = when {
        atl > ctl * 1.3 -> "fatigued"   // high relative fatigue
        atl > ctl * 0.8 -> "neutral"    // balanced
        else -> "fresh"                  // low fatigue
    }
    val atlBadgeLabel = when {
        atl > ctl * 1.5 -> "Very high"
        atl > ctl * 1.3 -> "High"
        atl > ctl * 0.8 -> "Balanced"
        else -> "Low"
    }
    val atlExplain = when {
        atl > ctl * 1.5 -> "Acute load is far above chronic fitness \u2014 significant short-term fatigue. High risk of overreaching if sustained."
        atl > ctl * 1.3 -> "Acute load exceeds fitness \u2014 normal during a build phase, but monitor recovery and subjective readiness."
        atl > ctl * 0.8 -> "Acute and chronic loads are balanced \u2014 sustainable training rhythm. Good for steady adaptation."
        else -> "Low recent load relative to fitness \u2014 recovery phase or taper. Freshness is building."
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

    val stravaBar = if (stravaConnected) {
        if (source == "strava") {
            """<div class="strava-bar"><span class="status"><span class="dot connected"></span>Strava connected \u2014 using your activity data</span></div>"""
        } else {
            """<div class="strava-bar"><span class="status"><span class="dot connected"></span>Strava connected \u2014 no recent activities, showing demo data</span></div>"""
        }
    } else if (stravaAuthUrl != null) {
        """<div class="strava-bar"><span class="status"><span class="dot disconnected"></span>Strava not connected</span><a href="${escapeHtml(stravaAuthUrl)}">Connect Strava</a></div>"""
    } else {
        """<div class="strava-bar"><span class="status"><span class="dot disconnected"></span>Strava not configured \u2014 using demo data</span></div>"""
    }

    // SVG load chart
    val loadChart = renderLoadChart(snapshot)

    return template
        .replace("{{source}}", escapeHtml(source))
        .replace("{{ctl}}", format(snapshot.ctl))
        .replace("{{atl}}", format(snapshot.atl))
        .replace("{{tsb}}", format(snapshot.tsb))
        .replace("{{tsbBadge}}", "<span class=\"badge $tsbBadgeClass\">$tsbBadgeLabel</span>")
        .replace("{{tsbExplain}}", escapeHtml(tsbExplain))
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
        .replace("{{legFeeling}}", state.checkIn.legFeeling.toString())
        .replace("{{mentalReadiness}}", state.checkIn.mentalReadiness.toString())
        .replace("{{timeAvailableMinutes}}", state.checkIn.timeAvailableMinutes.toString())
        .replace("{{philosophyOptions}}", options)
        .replace("{{workoutCard}}", workoutCard)
        .replace("{{errorCard}}", errorCard)
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
