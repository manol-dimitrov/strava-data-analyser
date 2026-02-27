package com.endurocoach.routes

import com.endurocoach.domain.ConversationMessage
import com.endurocoach.domain.DailyCheckIn
import com.endurocoach.domain.AthleteProfile
import com.endurocoach.domain.AthleteProfileStore
import com.endurocoach.domain.LlmChatClient
import com.endurocoach.domain.LlmStructuredClient
import com.endurocoach.domain.LoadSnapshot
import com.endurocoach.domain.TokenStore
import com.endurocoach.domain.WorkoutChatRequest
import com.endurocoach.domain.WorkoutPlan
import com.endurocoach.domain.WorkoutRequest
import com.endurocoach.llm.ConversationTrimmer
import com.endurocoach.session.SESSION_COOKIE
import com.endurocoach.session.SESSION_MAX_AGE
import com.endurocoach.session.SessionRegistry
import com.endurocoach.strava.CachedActivityRepository
import com.endurocoach.metrics.BanisterTrimpCalculator
import com.endurocoach.metrics.RecentPaceCalculator
import io.ktor.http.ContentType
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Instant
import java.time.LocalDate

data class DashboardState(
    val checkIn: DailyCheckIn = DailyCheckIn(
        legFeeling = 6,
        mentalReadiness = 6,
        timeAvailableMinutes = 60,
        coachingPhilosophy = "balanced",
        raceDistance = "10km"
    ),
    val maxHr: Int = 190,
    val restingHr: Int = 50,
    val onboarding: OnboardingState = OnboardingState(),
    val latestWorkout: WorkoutPlan? = null,
    val workoutHistory: List<WorkoutHistoryEntry> = emptyList(),
    val conversationThread: List<ConversationMessage> = emptyList(),
    val latestError: String? = null,
    val source: String = "demo",
    val generatedAt: Instant? = null
)

data class OnboardingState(
    val step: Int = 1,
    val sportFocus: String = "running",
    val targetEventName: String = "",
    val targetEventDate: String = "",
    val completed: Boolean = false
)

data class WorkoutHistoryEntry(
    val generatedAt: Instant,
    val source: String,
    val checkIn: DailyCheckIn,
    val workout: WorkoutPlan
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
    fun updateHeartRateProfile(maxHr: Int, restingHr: Int) {
        state = state.copy(maxHr = maxHr, restingHr = restingHr)
    }

    @Synchronized
    fun updateOnboarding(onboarding: OnboardingState) {
        state = state.copy(onboarding = onboarding)
    }

    /**
     * Hydrates session state from a persisted [AthleteProfile], marking onboarding as
     * complete so returning users skip the wizard.
     */
    @Synchronized
    fun hydrateFromProfile(profile: AthleteProfile) {
        state = state.copy(
            maxHr = profile.maxHr,
            restingHr = profile.restingHr,
            onboarding = OnboardingState(
                step = 3,
                sportFocus = profile.sportFocus,
                targetEventName = profile.targetEventName,
                targetEventDate = profile.targetEventDate,
                completed = true
            )
        )
    }

    @Synchronized
    fun updateWorkout(snapshotSource: String, workout: WorkoutPlan) {
        val entry = WorkoutHistoryEntry(
            generatedAt = Instant.now(),
            source = snapshotSource,
            checkIn = state.checkIn,
            workout = workout
        )
        // Anchor message starts/continues the chat thread for this workout
        val anchor = ConversationMessage(
            role = "model",
            content = "Session prescribed: ${workout.session.take(140).trimEnd()}. Ask me anything about it or request adjustments.",
            timestamp = Instant.now().toString()
        )
        state = state.copy(
            source = snapshotSource,
            latestWorkout = workout,
            workoutHistory = (listOf(entry) + state.workoutHistory).take(20),
            conversationThread = (state.conversationThread + anchor).takeLast(100),
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

    @Synchronized
    fun appendUserMessage(content: String) {
        val msg = ConversationMessage(
            role = "user",
            content = content,
            timestamp = Instant.now().toString()
        )
        state = state.copy(conversationThread = (state.conversationThread + msg).takeLast(100))
    }

    @Synchronized
    fun appendAssistantMessage(content: String) {
        val msg = ConversationMessage(
            role = "model",
            content = content,
            timestamp = Instant.now().toString()
        )
        state = state.copy(conversationThread = (state.conversationThread + msg).takeLast(100))
    }
}

data class DashboardDependencies(
    val templateHtml: String,
    val llmClient: LlmStructuredClient,
    val chatClient: LlmChatClient,
    val philosophyRulePacks: Map<String, String>,
    val sessionRegistry: SessionRegistry,
    val stravaConfigured: Boolean = false,
    val tokenStore: TokenStore,
    val athleteProfileStore: AthleteProfileStore,
    val loadProvider: suspend (CachedActivityRepository?, Int, Int) -> Triple<String, LoadSnapshot, List<com.endurocoach.domain.Activity>>
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
            val stravaAuthUrl = session.oauthService?.let { "/api/strava/connect" }
            val html = renderWelcome(
                template = dependencies.templateHtml,
                stravaConfigured = dependencies.stravaConfigured,
                stravaAuthUrl = stravaAuthUrl
            )
            call.respondText(html, ContentType.Text.Html)
            return@get
        }

        // ----- Authenticated: resolve athlete ID and load persisted profile -----
        if (session.stravaAthleteId == null && session.oauthService != null) {
            runCatching { session.oauthService.fetchAthleteId() }
                .onSuccess { session.stravaAthleteId = it }
        }

        // Load the persisted athlete profile once per session (skips if already hydrated)
        if (!session.profileLoaded) {
            session.profileLoaded = true
            val athleteId = session.stravaAthleteId
            if (athleteId != null && !session.dashboardState.read().onboarding.completed) {
                dependencies.athleteProfileStore.load(athleteId)?.let { profile ->
                    session.dashboardState.hydrateFromProfile(profile)
                }
            }
        }

        // ----- Authenticated: load data and render the full dashboard -----
        val state = session.dashboardState.read()
        if (!state.onboarding.completed) {
            val html = renderOnboarding(template = dependencies.templateHtml, state = state)
            call.respondText(html, ContentType.Text.Html)
            return@get
        }

        val (source, snapshot, activities) = dependencies.loadProvider(
            session.activityRepository,
            state.maxHr,
            state.restingHr
        )

        val html = renderDashboard(
            template = dependencies.templateHtml,
            state = state,
            source = source,
            snapshot = snapshot,
            activities = activities,
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
        val params = call.receiveParameters()
        val parsed = parseCheckIn(
            params = params,
            philosophyRulePacks = dependencies.philosophyRulePacks
        )
        val current = session.dashboardState.read()
        val maxHr = parseMaxHr(params, current.maxHr)
        val restingHr = parseRestingHr(params, current.restingHr)
        session.dashboardState.updateCheckIn(parsed)
        session.dashboardState.updateHeartRateProfile(maxHr, restingHr)
        call.respondRedirect("/")
    }

    post("/dashboard/onboarding") {
        val sessionId = call.request.cookies[SESSION_COOKIE]
        val session = dependencies.sessionRegistry.getOrCreate(sessionId)
        if (sessionId != session.id) {
            call.response.cookies.append(name = SESSION_COOKIE, value = session.id, path = "/", maxAge = SESSION_MAX_AGE)
        }

        val params = call.receiveParameters()
        val current = session.dashboardState.read()
        val action = params["action"] ?: "next"

        val sportFocus = parseSportFocus(params["sportFocus"], current.onboarding.sportFocus)
        val targetEventName = params["targetEventName"]?.trim()?.take(80) ?: current.onboarding.targetEventName
        val targetEventDate = parseTargetEventDate(params["targetEventDate"], current.onboarding.targetEventDate)

        val maxHr = parseMaxHr(params, current.maxHr)
        val restingHr = parseRestingHr(params, current.restingHr)
        session.dashboardState.updateHeartRateProfile(maxHr, restingHr)

        val nextStep = when (action) {
            "back" -> (current.onboarding.step - 1).coerceAtLeast(1)
            "finish", "skip" -> 3
            else -> (current.onboarding.step + 1).coerceAtMost(3)
        }

        val completed = action == "finish" || action == "skip"
        session.dashboardState.updateOnboarding(
            current.onboarding.copy(
                step = nextStep,
                sportFocus = sportFocus,
                targetEventName = targetEventName,
                targetEventDate = targetEventDate,
                completed = completed
            )
        )

        // Persist profile to disk when the athlete finishes the wizard
        if (completed) {
            val athleteId = session.stravaAthleteId
            if (athleteId != null) {
                dependencies.athleteProfileStore.save(
                    AthleteProfile(
                        stravaAthleteId = athleteId,
                        sportFocus = sportFocus,
                        maxHr = maxHr,
                        restingHr = restingHr,
                        targetEventName = targetEventName,
                        targetEventDate = targetEventDate,
                        completedAt = java.time.Instant.now().toString()
                    )
                )
            }
        }

        call.respondRedirect("/")
    }

    post("/dashboard/generate") {
        val sessionId = call.request.cookies[SESSION_COOKIE]
        val session = dependencies.sessionRegistry.getOrCreate(sessionId)
        if (sessionId != session.id) {
            call.response.cookies.append(name = SESSION_COOKIE, value = session.id, path = "/", maxAge = SESSION_MAX_AGE)
        }
        val params = call.receiveParameters()
        val parsed = parseCheckIn(
            params = params,
            philosophyRulePacks = dependencies.philosophyRulePacks
        )
        val current = session.dashboardState.read()
        val maxHr = parseMaxHr(params, current.maxHr)
        val restingHr = parseRestingHr(params, current.restingHr)
        session.dashboardState.updateCheckIn(parsed)
        session.dashboardState.updateHeartRateProfile(maxHr, restingHr)

        val (source, snapshot, activities) = dependencies.loadProvider(session.activityRepository, maxHr, restingHr)
        val state = session.dashboardState.read()
        val profileContext = buildOnboardingProfileContext(state.onboarding)
        val basePhilosophy = dependencies.philosophyRulePacks[parsed.coachingPhilosophy]
            ?: dependencies.philosophyRulePacks.getValue("balanced")

        val paceProfile = RecentPaceCalculator.calculate(
            activities = activities,
            maxHr = maxHr,
            restingHr = restingHr
        )

        val request = WorkoutRequest(
            checkIn = parsed.copy(
                coachingPhilosophy = "$basePhilosophy\n$profileContext"
            ),
            currentTsb = snapshot.tsb,
            currentCtl = snapshot.ctl,
            currentAtl = snapshot.atl,
            recentVolumeMinutes = snapshot.recentVolumeMinutes,
            acwr = snapshot.acwr,
            monotony = snapshot.monotony,
            ctlRampRate = snapshot.ctlRampRate,
            spike10 = snapshot.spike10,
            strain10 = snapshot.strain10,
            recentPaceProfile = paceProfile,
            daysSinceLastHardSession = snapshot.daysSinceLastHardSession,
            recentSessions = snapshot.recentSessions
        )

        runCatching { dependencies.llmClient.generateWorkoutPlan(request) }
            .onSuccess { session.dashboardState.updateWorkout(source, it) }
            .onFailure {
                session.dashboardState.updateError(
                    source,
                    it.message ?: "Workout generation failed"
                )
            }

        call.respondRedirect("/?tab=coach")
    }

    post("/dashboard/chat") {
        val sessionId = call.request.cookies[SESSION_COOKIE]
        val session = dependencies.sessionRegistry.getOrCreate(sessionId)
        if (sessionId != session.id) {
            call.response.cookies.append(name = SESSION_COOKIE, value = session.id, path = "/", maxAge = SESSION_MAX_AGE)
        }

        val params = call.receiveParameters()
        val message = params["message"]?.trim() ?: ""

        val state = session.dashboardState.read()
        if (message.isBlank() || state.latestWorkout == null) {
            call.respondRedirect("/?tab=coach")
            return@post
        }

        session.dashboardState.appendUserMessage(message)

        // Trim history to char budget before sending to LLM (excluding the message just appended)
        val historyForLlm = ConversationTrimmer.trimToCharBudget(
            messages = state.conversationThread, // snapshot before appending
            maxChars = 12_000
        )
        val systemInstruction = buildChatSystemInstruction(state)

        val acceptsJson = call.request.headers["Accept"]?.contains("application/json") == true

        var chatResponse = "Sorry, I couldn't process that. Please try again."
        runCatching {
            dependencies.chatClient.chat(
                WorkoutChatRequest(
                    systemInstruction = systemInstruction,
                    history = historyForLlm,
                    userMessage = message
                )
            )
        }.onSuccess { response ->
            chatResponse = response
            session.dashboardState.appendAssistantMessage(response)
        }.onFailure {
            session.dashboardState.appendAssistantMessage(chatResponse)
        }

        if (acceptsJson) {
            call.respond(mapOf("role" to "model", "content" to chatResponse))
        } else {
            call.respondRedirect("/?tab=coach")
        }
    }
}

private fun buildChatSystemInstruction(state: DashboardState): String {
    val profile = state.onboarding
    val workout = state.latestWorkout

    val profileBlock = buildString {
        appendLine("Athlete profile:")
        appendLine("- Sport focus: ${profile.sportFocus}")
        appendLine("- Max HR: ${state.maxHr} bpm | Resting HR: ${state.restingHr} bpm")
        val eventLine = profile.targetEventName.ifBlank { "Not set" } +
            if (profile.targetEventDate.isNotBlank()) " on ${profile.targetEventDate}" else ""
        appendLine("- Target event: $eventLine")
    }.trimEnd()

    val workoutBlock = if (workout != null) buildString {
        appendLine("\nLatest prescribed workout:")
        appendLine("- Session: ${workout.session}")
        appendLine("- Coach reasoning: ${workout.coachReasoning}")
    }.trimEnd() else "\nNo workout has been prescribed yet."

    val previousBlock = if (state.workoutHistory.size > 1) buildString {
        appendLine("\nPrevious sessions (brief context, most recent first):")
        state.workoutHistory.drop(1).take(2).forEach { entry ->
            appendLine("- ${entry.generatedAt.toString().take(10)}: ${entry.workout.session.take(120).trimEnd()}")
        }
    }.trimEnd() else ""

    return """
You are Maestro, an elite endurance coach. You are in a follow-up coaching conversation with your athlete about their training.

$profileBlock
$workoutBlock$previousBlock

Coaching rules:
- Be direct, precise, and coaching-forward. No filler language or sycophancy.
- If the athlete asks to adjust the session, suggest specific modifications in plain, actionable language.
- Reference physiological reasoning (CTL/ATL/TSB, ACWR, periodisation) when relevant.
- Keep responses concise unless the question genuinely warrants detail.
- Treat the athlete as a serious adult who wants honest, actionable guidance.
- If asked about nutrition, sleep, or recovery, give evidence-based guidance relevant to their training context.""".trimIndent()
}

private fun renderChatThread(thread: List<ConversationMessage>, hasWorkout: Boolean): String {
    if (!hasWorkout) return """
<div class="card anim-in">
    <div class="card-title"><span class="title-icon" aria-hidden="true">&#x1F4AC;</span> Ask Your Coach</div>
    <p class="chat-empty">Generate a session above, then ask your coach anything about it.</p>
</div>
""".trimIndent()

    val messages = if (thread.isEmpty()) {
        "<p class=\"chat-empty\">No messages yet. Ask your coach anything about the prescribed session.</p>"
    } else {
        thread.joinToString("\n") { msg ->
            val roleClass = if (msg.role == "user") "chat-user" else "chat-coach"
            val label = if (msg.role == "user") "You" else "Coach"
            """<div class="chat-msg $roleClass"><span class="chat-label">$label</span><p>${escapeHtml(msg.content)}</p></div>"""
        }
    }

    return """
<div class="card anim-in">
    <div class="card-title"><span class="title-icon" aria-hidden="true">&#x1F4AC;</span> Ask Your Coach</div>
    <div class="chat-section">
        <div class="chat-thread" id="chat-thread">$messages</div>
        <form class="chat-form" id="chat-form" action="/dashboard/chat" method="post">
            <textarea class="chat-input" name="message" placeholder="Ask about this session, request adjustments&#x2026;" autocomplete="off" maxlength="500" required rows="2"></textarea>
            <button class="btn btn-primary chat-send" type="submit">Send</button>
        </form>
    </div>
</div>
""".trimIndent()
}

private fun parseCheckIn(
    params: io.ktor.http.Parameters,
    philosophyRulePacks: Map<String, String>
): DailyCheckIn {
    val selected = params["coachingPhilosophy"]
        ?.takeIf { philosophyRulePacks.containsKey(it) }
        ?: "balanced"

    val allowedDistances = setOf("5km", "10km", "half_marathon", "marathon")
    val raceDistance = params["raceDistance"]
        ?.takeIf { it in allowedDistances } ?: "10km"

    return DailyCheckIn(
        legFeeling = params["legFeeling"]?.toIntOrNull()?.coerceIn(1, 10) ?: 6,
        mentalReadiness = params["mentalReadiness"]?.toIntOrNull()?.coerceIn(1, 10) ?: 6,
        timeAvailableMinutes = params["timeAvailableMinutes"]?.toIntOrNull()?.coerceIn(15, 240) ?: 60,
        coachingPhilosophy = selected,
        raceDistance = raceDistance
    )
}

private fun parseMaxHr(params: io.ktor.http.Parameters, current: Int): Int {
    return params["maxHr"]?.toIntOrNull()?.coerceIn(120, 230) ?: current
}

private fun parseRestingHr(params: io.ktor.http.Parameters, current: Int): Int {
    return params["restingHr"]?.toIntOrNull()?.coerceIn(30, 90) ?: current
}

private fun parseSportFocus(input: String?, current: String): String {
    val allowed = setOf("running", "cycling", "triathlon", "general")
    return input?.takeIf { it in allowed } ?: current
}

private fun parseTargetEventDate(input: String?, current: String): String {
    if (input == null) return current
    val raw = input.trim()
    if (raw.isEmpty()) return ""
    return runCatching { LocalDate.parse(raw) }.map { raw }.getOrElse { current }
}

private fun buildOnboardingProfileContext(onboarding: OnboardingState): String {
    val eventName = onboarding.targetEventName.ifBlank { "Not specified" }
    val eventDate = onboarding.targetEventDate.ifBlank { "Not specified" }

    return """
Athlete profile context:
- Sport focus: ${onboarding.sportFocus}
- Target event: $eventName
- Target event date: $eventDate
If a target event date is specified and within 12 weeks, bias the session toward appropriate periodisation (build, sharpen, taper) while respecting readiness and risk metrics.
""".trimIndent()
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
    <img src="/assets/strava/btn_strava_connect_with_orange.svg" alt="Connect with Strava" />
</a>"""
    } else {
        """<p class="welcome-note">Strava integration is not configured. Set <code>STRAVA_CLIENT_ID</code> and <code>STRAVA_CLIENT_SECRET</code> environment variables to enable.</p>"""
    }

    val welcomeHtml = """
<div class="welcome-hero anim-in">
    <div class="welcome-icon">
        <img src="/assets/logo.png" alt="Maestro logo" onerror="this.style.display='none';this.nextElementSibling.style.display='block';" />
        <svg viewBox="0 0 42 26" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
            <path d="M1 13H7.5L11 1.5L15.5 24.5L21 0.5L26.5 24.5L31 1.5L34.5 13H41" stroke="currentColor" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
    </div>
    <h1 class="welcome-title">Maestro</h1>
    <p class="welcome-sub">Your AI Endurance Coach &mdash; science-backed training load analysis and intelligent daily workout prescriptions built on the Banister impulse-response model.</p>
    $connectButton
    <div class="welcome-features">
        <div class="wf"><span class="wf-icon" aria-hidden="true">&#x1F4CA;</span><span>Fitness, fatigue &amp; readiness tracking</span></div>
        <div class="wf"><span class="wf-icon" aria-hidden="true">&#x26A1;</span><span>10-day spike &amp; strain alerts</span></div>
        <div class="wf"><span class="wf-icon" aria-hidden="true">&#x1F9E0;</span><span>AI-generated daily prescriptions</span></div>
        <div class="wf"><span class="wf-icon" aria-hidden="true">&#x1F6B4;</span><span>All cardio activities supported</span></div>
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

private fun renderOnboarding(
    template: String,
    state: DashboardState
): String {
    val step = state.onboarding.step.coerceIn(1, 3)
    val progressText = "Step $step of 3"

    val sportOptions = listOf("running", "cycling", "triathlon", "general")
        .joinToString("\n") { option ->
            val active = if (state.onboarding.sportFocus == option) "checked" else ""
            """<label class="onb-choice"><input type="radio" name="sportFocus" value="$option" $active /> ${escapeHtml(option.replaceFirstChar(Char::uppercaseChar))}</label>"""
        }

    val stepBody = when (step) {
        1 -> """
<div class="form-group">
    <label>Primary training focus</label>
    <div class="onb-choice-grid">$sportOptions</div>
    <div class="hint">Used to bias session type and progression logic.</div>
</div>
""".trimIndent()

        2 -> """
<div class="form-group">
    <label>Heart rate profile</label>
    <div class="hr-grid">
        <div class="hr-field">
            <label for="maxHr">Max HR</label>
            <input id="maxHr" type="number" name="maxHr" min="120" max="230" value="${state.maxHr}" />
        </div>
        <div class="hr-field">
            <label for="restingHr">Resting HR</label>
            <input id="restingHr" type="number" name="restingHr" min="30" max="90" value="${state.restingHr}" />
        </div>
    </div>
    <div class="hint">These values calibrate HRR/TRIMP load calculations.</div>
</div>
""".trimIndent()

        else -> """
<div class="form-group">
    <label>Target event name</label>
    <input type="text" name="targetEventName" maxlength="80" value="${escapeHtml(state.onboarding.targetEventName)}" placeholder="e.g. Sofia Marathon" />
    <div class="hint">Optional, but improves specificity of coaching suggestions.</div>
</div>
<div class="form-group">
    <label>Target event date</label>
    <input type="date" name="targetEventDate" value="${escapeHtml(state.onboarding.targetEventDate)}" />
    <div class="hint">Optional. If provided, workouts will bias toward the right training phase.</div>
</div>
""".trimIndent()
    }

    val backBtn = if (step > 1) {
        """<button class="btn btn-ghost" type="submit" name="action" value="back">Back</button>"""
    } else {
        """<span></span>"""
    }

    val nextBtn = if (step < 3) {
        """<button class="btn btn-primary" type="submit" name="action" value="next">Continue</button>"""
    } else {
        """<button class="btn btn-primary" type="submit" name="action" value="finish">Finish onboarding</button>"""
    }

    val onboardingHtml = """
<div class="layout single-column">
    <div class="card onboarding-card">
        <div class="card-title"><span class="title-icon">&#x1F680;</span> Welcome to Maestro</div>
        <div class="onb-subtitle">Complete a quick setup to personalise your coaching engine.</div>
        <div class="onb-progress">$progressText</div>
        <form action="/dashboard/onboarding" method="post">
            $stepBody
            <div class="onb-nav">
                $backBtn
                $nextBtn
            </div>
            <div class="onb-skip-wrap">
                <button class="onb-skip-link" type="submit" name="action" value="skip">Skip setup and use defaults</button>
            </div>
        </form>
    </div>
</div>
""".trimIndent()

    val stravaBar = """<span class="strava-status"><span class="dot connected"></span>Connected</span><a class="btn-disconnect" href="/api/strava/disconnect">Disconnect</a>"""

    return template
        .replace("{{stravaBar}}", stravaBar)
        .replace("{{dashboardContent}}", onboardingHtml)
        .replace(Regex("<!-- DASHBOARD_START -->.*<!-- DASHBOARD_END -->", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("\\{\\{[a-zA-Z0-9]+}}"), "")
}

private fun renderDashboard(
    template: String,
    state: DashboardState,
    source: String,
    snapshot: LoadSnapshot,
    activities: List<com.endurocoach.domain.Activity>,
    philosophyRulePacks: Map<String, String>,
    stravaConnected: Boolean = false,
    stravaAuthUrl: String? = null
): String {
    val options = philosophyRulePacks.keys.joinToString("\n") { key ->
        val selected = if (state.checkIn.coachingPhilosophy == key) "selected" else ""
        "<option value=\"${escapeHtml(key)}\" $selected>${escapeHtml(key.replaceFirstChar(Char::uppercaseChar))}</option>"
    }

    val raceDistanceOptions = listOf(
        "5km" to "5 km",
        "10km" to "10 km",
        "half_marathon" to "Half Marathon",
        "marathon" to "Marathon"
    ).joinToString("\n") { (value, label) ->
        val selected = if (state.checkIn.raceDistance == value) "selected" else ""
        "<option value=\"$value\" $selected>$label</option>"
    }

    // Hide the goal selector when the athlete already declared a target event during onboarding.
    // A hidden input preserves the value so the form still submits it correctly.
    val raceDistanceBlock = if (state.onboarding.targetEventName.isNotBlank()) {
        "<input type=\"hidden\" name=\"raceDistance\" value=\"${escapeHtml(state.checkIn.raceDistance)}\" />"
    } else {
        """<div class="form-group">
                    <label>What are you training for?</label>
                    <select name="raceDistance">$raceDistanceOptions</select>
                </div>"""
    }

    val workoutCard = state.latestWorkout?.let {
        """
<div class="workout-section"><p>${escapeHtml(it.session)}</p></div>
<div class="reasoning-block"><div class="ws-header"><span class="ws-icon">&#x1F9E0;</span><span class="ws-label">Coach Reasoning</span></div><p>${escapeHtml(it.coachReasoning)}</p></div>
<p class="generated-at">Generated at: ${state.generatedAt ?: "-"}</p>
""".trimIndent()
    } ?: """<div class="workout-empty"><div class="empty-icon">&#x1F3C3;</div>No workout generated yet.<br/>Complete check-in and press <strong>Generate workout</strong>.</div>"""

    val errorCard = state.latestError?.let {
        "<div class=\"error\"><strong>Generation error:</strong> ${escapeHtml(it)}</div>"
    } ?: ""

    // TSB contextual interpretation (Mujika & Busso research-based ranges)
    val tsb = snapshot.tsb
    val daysSinceHard = snapshot.daysSinceLastHardSession
    val hardWithin72h = daysSinceHard != null && daysSinceHard < 3
    val hardWithin48h = daysSinceHard != null && daysSinceHard < 2
    val recentHardWithHighFatigue = hardWithin72h && snapshot.atl > snapshot.ctl
    val tsbBadgeClass = when {
        recentHardWithHighFatigue -> "fatigued"
        hardWithin48h -> "neutral"
        tsb > 25 -> "fatigued"   // detraining risk
        tsb > 10 -> "fresh"      // tapered / race-ready
        tsb > -10 -> "neutral"   // productive training
        tsb > -25 -> "neutral"   // build phase — expected
        else -> "fatigued"       // overreaching risk
    }
    val tsbBadgeLabel = when {
        recentHardWithHighFatigue -> "Recovering"
        hardWithin48h -> "Hold quality"
        tsb > 25 -> "Detraining"
        tsb > 10 -> "Race-ready"
        tsb > 0 -> "Fresh"
        tsb > -10 -> "Productive"
        tsb > -25 -> "Building"
        else -> "Overreaching"
    }
    val tsbExplainBase = when {
        recentHardWithHighFatigue -> "You hit a hard session in the last 72 hours and your acute fatigue is still above fitness. This is a recovery window, not a quality window."
        hardWithin48h -> "You hit a hard session in the last 48 hours. Even if form looks okay, adaptation is still happening — hold intensity today."
        tsb > 25  -> "You've been very fresh for a while \u2014 if you're not peaking for a race, your fitness may slowly be slipping. Time to add some load back in."
        tsb > 10  -> "You're fresh and fit \u2014 perfect timing for a race or a big quality session. Make the most of it."
        tsb > 0   -> "Feeling good with solid fitness underneath. A great day for a quality effort or a strong long run."
        tsb > -10 -> "You're carrying a normal training load \u2014 exactly where you want to be when building consistently. This is where improvement happens."
        tsb > -25 -> "You're accumulating fatigue from a solid training block. That's normal and productive \u2014 just make sure you're sleeping and eating well."
        else      -> "Accumulated fatigue is getting heavy. If this has been going on for a week or more, an easier week will help more than it hurts."
    }
    // Cross-metric awareness: append a relevant secondary signal when it matters
    val tsbCrossMetric = when {
        hardWithin72h && snapshot.atl > snapshot.ctl -> " Last hard run is too recent for another quality day — keep this session easy."
        hardWithin72h -> " Last hard run is recent, so bias toward easy aerobic work today."
        snapshot.acwr > 1.5 -> " Your load ratio has spiked \u2014 dial it back today regardless."
        snapshot.acwr > 1.3 && tsb > -10 -> " Your load ratio is creeping up though \u2014 keep an eye on it."
        snapshot.monotony > 2.0 && tsb <= 0  -> " Your training variety is also low \u2014 mix in a different type of session."
        snapshot.ctl < 15 && tsb > 0     -> " Fitness is still early-stage, so be patient and build gradually."
        else -> ""
    }
    val tsbExplain = tsbExplainBase + tsbCrossMetric

    // Actionable call-to-action for the hero card
    val tsbCta = when {
        recentHardWithHighFatigue -> "⛔ Hard effort is too soon. Keep it easy today and reassess tomorrow."
        hardWithin48h -> "🛌 Protect adaptation — easy aerobic running or rest today, quality tomorrow at the earliest."
        tsb > 25  -> "\u27A1 Get moving \u2014 a solid tempo or threshold session today will rebuild momentum without overdoing it."
        tsb > 10  -> "\u26A1 This is your window. Ask the coach for a race-effort session or hit that hard workout you've been saving."
        tsb > 0   -> "\u2705 Use the freshness \u2014 a quality long run, tempo, or interval session will land well today."
        tsb > -10 -> "\uD83D\uDCC8 Stay the course. Trust the process and let the coach dial the intensity \u2014 you're on track."
        tsb > -25 -> "\uD83D\uDCA4 Prioritise sleep and nutrition today. A lighter session or recovery spin will pay dividends."
        else      -> "\u26D4 Take an easy day or full rest. If you've been pushing for 7+ days straight, schedule a recovery week."
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
        ctl > 60 -> "Strong fitness base. Your body is well-conditioned to handle sustained training. Keep the consistency going.<span class=\"nudge\">Maintain rhythm \u2014 you can handle quality sessions without overreaching.</span>"
        ctl > 30 -> "Good fitness with plenty of room to grow. Keep stacking consistent weeks and you'll see this number climb.<span class=\"nudge\">Stay patient and build week on week \u2014 consistency is king.</span>"
        ctl > 15 -> "Your fitness is on the rise. Stay patient and stick to the plan \u2014 this is the base-building phase.<span class=\"nudge\">Focus on regular easy volume to lay the aerobic foundation.</span>"
        else     -> "Fitness is still developing \u2014 either early in a new block or coming back from time off. Build gently and let the load accumulate.<span class=\"nudge\">Start with 3\u20134 easy sessions a week and increase gradually.</span>"
    }

    // ATL contextual interpretation (relative to CTL — ACWR-informed)
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
        acwr > 1.5 -> "Your recent training has jumped well above what your body is used to. Back off a little to keep injury risk in check.<span class=\"nudge\">Skip the hard session today \u2014 an easy spin or rest day is the smart call.</span>"
        acwr > 1.3 -> "You're training harder than your recent average \u2014 fine for a build block, but don't let it run for more than a week or two without a lighter day.<span class=\"nudge\">Watch how you feel tomorrow \u2014 back off if legs are heavy.</span>"
        acwr > 0.8 -> "Recent training is nicely balanced with your fitness base. You're working hard enough to improve without overdoing it.<span class=\"nudge\">Good groove \u2014 keep the mix of hard and easy days going.</span>"
        else       -> "You're doing less than your body is used to \u2014 fine if you're recovering, but don't stay here too long or fitness will drift.<span class=\"nudge\">If you're feeling good, there's room to add a session this week.</span>"
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
        vol > 600 -> "A big week \u2014 over 10 hours on your feet. Make sure sleep and food are keeping up with the effort.<span class=\"nudge\">Consider an easier couple of days to let your body absorb this block.</span>"
        vol > 420 -> "Solid 7+ hour week. Good training load \u2014 keep an eye on how your legs feel day to day.<span class=\"nudge\">You're doing the work \u2014 stay consistent and listen to your body.</span>"
        vol > 240 -> "A decent 4\u20137 hours this week. There's room to build if your body is responding well.<span class=\"nudge\">If legs are feeling fresh, you could add an extra easy session.</span>"
        vol > 120 -> "A lighter 2\u20134 hour week. Good for recovery or easing back into things.<span class=\"nudge\">Perfect if it's a planned rest week. If not, try to get out the door tomorrow.</span>"
        else      -> "Under 2 hours \u2014 very light. Whether planned or not, easy weeks are part of the process.<span class=\"nudge\">When you're ready, start back gently with short easy sessions.</span>"
    }

    val sourceBadge = if (source == "strava") {
        "<span class=\"badge strava-badge\">Strava</span>"
    } else {
        "<span class=\"badge demo-badge\">Demo data</span>"
    }

    // Topbar-inline strava status (no wrapping div — rendered inside .topbar-right)
    val stravaBar = if (stravaConnected) {
        val statusText = if (source == "strava") "Cardio activities synced" else "Connected — no recent activities"
        val tsbColor = when {
            tsb > 10  -> "#2ED3A2"
            tsb > 0   -> "#FFBD5B"
            tsb > -20 -> "#9B91FF"
            else      -> "#FF6C84"
        }
        val tsbLabel = if (tsb >= 0) "+${"%.0f".format(tsb)}" else "${"%.0f".format(tsb)}"
        """<span class="tsb-topbar-pill" style="color:$tsbColor">TSB $tsbLabel</span><span class="strava-status"><span class="dot connected"></span>$statusText</span><a class="btn-disconnect" href="/api/strava/disconnect">Disconnect</a>"""
    } else if (stravaAuthUrl != null) {
        """<span class="strava-status"><span class="dot disconnected"></span>Not connected</span><a class="btn-connect" href="${escapeHtml(stravaAuthUrl)}"><img src="/assets/strava/btn_strava_connect_with_orange.svg" alt="Connect with Strava" /></a>"""
    } else {
        """<span class="strava-status"><span class="dot disconnected"></span>Demo mode</span>"""
    }

    // SVG load chart
    val loadChart = renderLoadChart(snapshot)

    // Recent workouts strip
    val workoutsStrip = renderWorkoutsStrip(activities)
    val workoutHistory = renderWorkoutHistory(state.workoutHistory)
    val chatThread = renderChatThread(state.conversationThread, state.latestWorkout != null)

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
        acwr > 1.5 -> "Your recent training load has spiked well above your normal level. Dial back the intensity or volume \u2014 the injury risk is real at this level.<span class=\"nudge\">Drop to easy sessions only for the next 2\u20133 days.</span>"
        acwr > 1.3 -> "You're pushing a bit harder than your baseline, which is fine during a planned build. Don't sustain it for more than 10 days without backing off.<span class=\"nudge\">One more hard day is fine, then ease up.</span>"
        acwr > 0.8 -> "Training load and fitness are well-matched. You're in a productive groove \u2014 pushing enough to improve without overdoing it.<span class=\"nudge\">Keep the balance \u2014 this is the sweet spot for adaptation.</span>"
        else       -> "Your recent training is well below your usual level. That's fine for a recovery week, but don't stay here too long.<span class=\"nudge\">When you're ready, ramp back up gradually over 3\u20135 days.</span>"
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
        mono > 2.0 -> "Your training days are all looking very similar. Mix it up more \u2014 alternate hard and easy days to lower the risk of overuse injury and burnout.<span class=\"nudge\">Tomorrow, try the opposite \u2014 if today was hard, go very easy.</span>"
        mono > 1.5 -> "There's some variety in your week, but you could get more from it. Try making your easy days a bit easier and your hard days a bit harder.<span class=\"nudge\">Add more contrast \u2014 a polarised approach helps adaptation.</span>"
        else       -> "Good mix of hard and easy days. This kind of variety helps your body recover and adapt week on week.<span class=\"nudge\">Keep this up \u2014 variety is protective against overuse injuries.</span>"
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
        spike10 > 1.5 -> "The last 10 days have been significantly heavier than your usual training level. Even if it doesn't feel that way, the load is building — plan some lighter days soon.<span class=\"nudge\">Schedule a lighter 2–3 days to let the body catch up.</span>"
        spike10 > 1.3 -> "You've been training above your usual level for the past 10 days. Fine for a build phase, but plan a lighter stretch before long.<span class=\"nudge\">A brief dip in volume will consolidate recent gains.</span>"
        spike10 > 0.8 -> "The last 10 days of training are right in line with your normal level. Consistent and sustainable.<span class=\"nudge\">Steady as she goes — no red flags here.</span>"
        else          -> "The last 10 days have been lighter than usual. Rest and recovery are part of the process.<span class=\"nudge\">When energy returns, build back up incrementally.</span>"
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
        strain10 > 700 -> "High strain over the last 10 days — heavy load combined with repetitive days. Your body needs more contrast between hard and easy sessions.<span class=\"nudge\">Prioritise variety and an easier day or two before the next hard block.</span>"
        strain10 > 400 -> "Moderate strain — you're working hard and the training is productive. Keep an ear out for any signs of fatigue building up.<span class=\"nudge\">Stay aware — moderate strain is fine if variety is holding up.</span>"
        else           -> "Low strain — a good mix of effort levels and manageable load. Your body has room to absorb more training.<span class=\"nudge\">Room in the tank — you can push a bit harder if you're feeling good.</span>"
    }

    // Hero card supplementary vars
    val tsbHeroClass = when {
        recentHardWithHighFatigue -> "negative-risk"
        hardWithin48h -> "negative-ok"
        tsb > 10 -> "positive"
        tsb > 0 -> "neutral"
        tsb > -20 -> "negative-ok"
        else -> "negative-risk"
    }
    val tsbHeroMessage = when {
        recentHardWithHighFatigue -> "Recent hard run + high fatigue: recover today"
        hardWithin48h -> "Recent hard run: no quality today"
        tsb > 25 -> "Keep it steady — your fitness base may be fading"
        tsb > 10 -> "Primed to push hard or race"
        tsb > 0 -> "Fresh with solid fitness — a good day to perform"
        tsb > -10 -> "In the build zone — this is where improvement happens"
        tsb > -25 -> "Deep build phase — recovery quality matters now"
        else -> "Heavy load — factor in a recovery week soon"
    }
    val tsbHeroStatusBadge = "<div class=\"hero-status-badge $tsbBadgeClass\">$tsbBadgeLabel</div>"

    // ── CTL Ramp Rate (fitness momentum) ──
    val rampRate = snapshot.ctlRampRate
    val rampBadgeClass = when {
        rampRate > 7  -> "fatigued"
        rampRate > 3  -> "neutral"
        rampRate > 0  -> "fresh"
        else          -> "neutral"
    }
    val rampBadgeLabel = when {
        rampRate > 7  -> "Aggressive"
        rampRate > 3  -> "Building"
        rampRate > 0  -> "Rising"
        else          -> "Declining"
    }
    val rampExplain = when {
        rampRate > 7  -> "Fitness is climbing fast \u2014 above 7 points/week risks burnout. Consider easing back next week to let adaptations settle.<span class=\"nudge\">Slow the ramp \u2014 a consolidation week will protect long-term progress.</span>"
        rampRate > 3  -> "Solid fitness momentum. You're building at a sustainable rate \u2014 this is the ideal growth corridor.<span class=\"nudge\">Keep going \u2014 this rate of progression is textbook.</span>"
        rampRate > 0  -> "Fitness is trending up gently. Steady progress without forcing it.<span class=\"nudge\">Room to push a little more if energy and recovery are good.</span>"
        rampRate > -3 -> "Fitness is broadly stable or edging down. A normal phase during recovery weeks.<span class=\"nudge\">If this wasn't planned, add a bit more volume this week.</span>"
        else          -> "Fitness is declining \u2014 either a deliberate taper or an extended break. Time off can be productive if it was needed.<span class=\"nudge\">Start rebuilding with easy, consistent sessions.</span>"
    }

    // ── Trend arrows for CTL and ATL ──
    val series = snapshot.series
    val ctlDelta = if (series.size >= 8) series.last().ctl - series[series.size - 8].ctl else 0.0
    val atlDelta = if (series.size >= 8) series.last().atl - series[series.size - 8].atl else 0.0
    fun trendArrow(delta: Double): String = when {
        delta > 1.0  -> "<span class=\"trend-arrow up\">\u2191</span>"
        delta < -1.0 -> "<span class=\"trend-arrow down\">\u2193</span>"
        else         -> "<span class=\"trend-arrow flat\">\u2192</span>"
    }
    val ctlTrend = trendArrow(ctlDelta)
    val atlTrend = trendArrow(atlDelta)

    // ── Range bar builder ──
    // Each zone: Triple(proportionalWidth 0-1, cssColor, label)
    fun buildRangeBar(value: Double, scaleMin: Double, scaleMax: Double, zones: List<Triple<Double, Double, String>>): String {
        val range = scaleMax - scaleMin
        if (range <= 0) return ""
        val pct = ((value - scaleMin) / range).coerceIn(0.0, 1.0) * 100.0
        val segments = zones.map { (start, end, color) ->
            val w = ((end - start) / range * 100.0).coerceIn(0.0, 100.0)
            "<span class=\"range-seg\" style=\"flex:0 0 ${"%.1f".format(w)}%;background:$color;\"></span>"
        }.joinToString("")
        return "<div class=\"range-bar\">$segments<span class=\"range-marker\" style=\"left:${"%.1f".format(pct)}%;\"></span></div>"
    }

    val ctlRangeBar = buildRangeBar(ctl, 0.0, 100.0, listOf(
        Triple(0.0, 15.0, "#FF6C84"), Triple(15.0, 30.0, "#FFBD5B"),
        Triple(30.0, 60.0, "#FFBD5B"), Triple(60.0, 80.0, "#2ED3A2"), Triple(80.0, 100.0, "#2ED3A2")
    ))
    val atlRangeBar = buildRangeBar(acwr, 0.0, 2.0, listOf(
        Triple(0.0, 0.8, "#B2A6D8"), Triple(0.8, 1.3, "#2ED3A2"),
        Triple(1.3, 1.5, "#FFBD5B"), Triple(1.5, 2.0, "#FF6C84")
    ))
    val acwrRangeBar = buildRangeBar(acwr, 0.0, 2.0, listOf(
        Triple(0.0, 0.8, "#B2A6D8"), Triple(0.8, 1.3, "#2ED3A2"),
        Triple(1.3, 1.5, "#FFBD5B"), Triple(1.5, 2.0, "#FF6C84")
    ))
    val rampRangeBar = buildRangeBar(rampRate, -5.0, 10.0, listOf(
        Triple(-5.0, 0.0, "#B2A6D8"), Triple(0.0, 3.0, "#2ED3A2"),
        Triple(3.0, 7.0, "#FFBD5B"), Triple(7.0, 10.0, "#FF6C84")
    ))
    val monoRangeBar = buildRangeBar(mono, 0.0, 3.0, listOf(
        Triple(0.0, 1.5, "#2ED3A2"), Triple(1.5, 2.0, "#FFBD5B"),
        Triple(2.0, 2.5, "#FF6C84"), Triple(2.5, 3.0, "#FF6C84")
    ))
    val volRangeBar = buildRangeBar(vol, 0.0, 720.0, listOf(
        Triple(0.0, 120.0, "#2ED3A2"), Triple(120.0, 240.0, "#2ED3A2"),
        Triple(240.0, 420.0, "#FFBD5B"), Triple(420.0, 600.0, "#FF6C84"), Triple(600.0, 720.0, "#FF6C84")
    ))
    val spike10RangeBar = buildRangeBar(spike10, 0.0, 2.0, listOf(
        Triple(0.0, 0.8, "#B2A6D8"), Triple(0.8, 1.3, "#2ED3A2"),
        Triple(1.3, 1.5, "#FFBD5B"), Triple(1.5, 2.0, "#FF6C84")
    ))
    val strain10RangeBar = buildRangeBar(strain10, 0.0, 1000.0, listOf(
        Triple(0.0, 400.0, "#2ED3A2"), Triple(400.0, 700.0, "#FFBD5B"), Triple(700.0, 1000.0, "#FF6C84")
    ))

    return template
        .replace("{{source}}", escapeHtml(source))
        .replace("{{ctl}}", format(snapshot.ctl))
        .replace("{{atl}}", format(snapshot.atl))
        .replace("{{tsb}}", format(snapshot.tsb))
        .replace("{{tsbBadge}}", "<span class=\"badge $tsbBadgeClass\">$tsbBadgeLabel</span>")
        .replace("{{tsbExplain}}", escapeHtml(tsbExplain))
        .replace("{{tsbCta}}", escapeHtml(tsbCta))
        .replace("{{tsbHeroClass}}", tsbHeroClass)
        .replace("{{tsbHeroMessage}}", escapeHtml(tsbHeroMessage))
        .replace("{{tsbHeroStatusBadge}}", tsbHeroStatusBadge)
        .replace("{{workoutsStrip}}", workoutsStrip)
        .replace("{{ctlBadge}}", "<span class=\"badge $ctlBadgeClass\">$ctlBadgeLabel</span>")
        .replace("{{ctlExplain}}", ctlExplain)
        .replace("{{ctlTrend}}", ctlTrend)
        .replace("{{ctlRangeBar}}", ctlRangeBar)
        .replace("{{atlBadge}}", "<span class=\"badge $atlBadgeClass\">$atlBadgeLabel</span>")
        .replace("{{atlExplain}}", atlExplain)
        .replace("{{atlTrend}}", atlTrend)
        .replace("{{atlRangeBar}}", atlRangeBar)
        .replace("{{volumeBadge}}", "<span class=\"badge $volBadgeClass\">$volBadgeLabel</span>")
        .replace("{{volumeExplain}}", volExplain)
        .replace("{{volRangeBar}}", volRangeBar)
        .replace("{{sourceBadge}}", sourceBadge)
        .replace("{{stravaBar}}", stravaBar)
        .replace("{{loadChart}}", loadChart)
        .replace("{{volume}}", format(snapshot.recentVolumeMinutes))
        .replace("{{acwr}}", "%.2f".format(snapshot.acwr))
        .replace("{{acwrBadge}}", "<span class=\"badge $acwrBadgeClass\">$acwrBadgeLabel</span>")
        .replace("{{acwrExplain}}", acwrExplain)
        .replace("{{acwrRangeBar}}", acwrRangeBar)
        .replace("{{monotony}}", "%.1f".format(snapshot.monotony))
        .replace("{{monotonyBadge}}", "<span class=\"badge $monoBadgeClass\">$monoBadgeLabel</span>")
        .replace("{{monotonyExplain}}", monoExplain)
        .replace("{{monoRangeBar}}", monoRangeBar)
        .replace("{{spike10}}", "%.2f".format(snapshot.spike10))
        .replace("{{spike10Badge}}", "<span class=\"badge $spike10BadgeClass\">$spike10BadgeLabel</span>")
        .replace("{{spike10Explain}}", spike10Explain)
        .replace("{{spike10RangeBar}}", spike10RangeBar)
        .replace("{{strain10}}", "%.0f".format(snapshot.strain10))
        .replace("{{strain10Badge}}", "<span class=\"badge $strain10BadgeClass\">$strain10BadgeLabel</span>")
        .replace("{{strain10Explain}}", strain10Explain)
        .replace("{{strain10RangeBar}}", strain10RangeBar)
        .replace("{{rampRate}}", "%+.1f".format(rampRate))
        .replace("{{rampBadge}}", "<span class=\"badge $rampBadgeClass\">$rampBadgeLabel</span>")
        .replace("{{rampExplain}}", rampExplain)
        .replace("{{rampRangeBar}}", rampRangeBar)
        .replace("{{legFeeling}}", state.checkIn.legFeeling.toString())
        .replace("{{mentalReadiness}}", state.checkIn.mentalReadiness.toString())
        .replace("{{timeAvailableMinutes}}", state.checkIn.timeAvailableMinutes.toString())
        .replace("{{maxHr}}", state.maxHr.toString())
        .replace("{{restingHr}}", state.restingHr.toString())
        .replace("{{philosophyOptions}}", options)
        .replace("{{raceDistanceBlock}}", raceDistanceBlock)
        .replace("{{workoutCard}}", workoutCard)
        .replace("{{workoutHistory}}", workoutHistory)
        .replace("{{chatThread}}", chatThread)
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
        """<text x="${fmt2(padL - 6)}" y="${fmt2(y + 3.5)}" text-anchor="end" fill="#9078C0" font-size="10">${"%.0f".format(v)}</text>"""
    }.joinToString("\n    ")

    // X-axis date labels (show ~6 labels)
    val labelCount = minOf(6, series.size)
    val xLabels = (0 until labelCount).map { i ->
        val idx = if (labelCount <= 1) 0 else i * (series.size - 1) / (labelCount - 1)
        val x = xPos(idx)
        val dateStr = series[idx].date.let { "${it.monthValue}/${it.dayOfMonth}" }
        """<text x="${fmt2(x)}" y="${fmt2(h - 4)}" text-anchor="middle" fill="#9078C0" font-size="10">$dateStr</text>"""
    }.joinToString("\n    ")

    val ctlLine = polyline(series.map { it.ctl }, "#9B91FF", 2.2)
    val atlLine = polyline(series.map { it.atl }, "#F07055", 2.2)
    val tsbLine = polyline(series.map { it.tsb }, "#2ED3A2", 1.5, dashed = true)

    return """
<svg viewBox="0 0 ${w.toInt()} ${h.toInt()}" xmlns="http://www.w3.org/2000/svg" style="font-family:'IBM Plex Sans',sans-serif;">
    <!-- Grid line at zero -->
    <line x1="${fmt2(padL)}" y1="$zeroY" x2="${fmt2(w - padR)}" y2="$zeroY" stroke="rgba(179,157,255,.15)" stroke-width="1" stroke-dasharray="4,3"/>
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
    <circle cx="${fmt2(xPos(series.size - 1))}" cy="${fmt2(yPos(series.last().tsb))}" r="3.5" fill="#2ED3A2"/>
</svg>
""".trimIndent()
}

private fun fmt2(v: Double): String = "%.1f".format(v)

private fun renderWorkoutsStrip(activities: List<com.endurocoach.domain.Activity>): String {
    if (activities.isEmpty()) {
        return """<div class="workouts-card anim-in anim-d3">
    <div class="section-header"><span class="section-title">Recent Workouts</span></div>
    <div class="workouts-empty">No activities in the analysis window.</div>
</div>"""
    }

    val trimpCalc = BanisterTrimpCalculator()
    // Sort most recent first, limit to 30
    val sorted = activities.sortedByDescending { it.date }.take(30)

    // Find max TRIMP for bar scaling
    val allTrimps = sorted.map { trimpCalc.calculate(it.durationMinutes, it.avgHeartRate) }
    val maxTrimp = allTrimps.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0

    val cards = sorted.mapIndexed { i, a ->
        val trimp = allTrimps[i]
        val typeIcon = when (a.type?.lowercase()) {
            "run", "trailrun", "virtualrun" -> "\uD83C\uDFC3"
            "ride", "virtualride", "ebikeride" -> "\uD83D\uDEB4"
            "swim" -> "\uD83C\uDFCA"
            "hike" -> "\u26F0\uFE0F"
            "walk" -> "\uD83D\uDEB6"
            "nordicski" -> "\u26F7\uFE0F"
            "rowing" -> "\uD83D\uDEA3"
            else -> "\uD83C\uDFCB\uFE0F"
        }
        val name = escapeHtml(a.name ?: a.type ?: "Activity")
        val dateStr = "${a.date.dayOfMonth} ${a.date.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)}"

        // Distance
        val dist = a.distanceMeters
        val distStr = if (dist != null && dist > 0) {
            "${"%.1f".format(dist / 1000.0)} km"
        } else "—"

        // Duration h:mm
        val durationTotal = a.durationMinutes.toInt()
        val durStr = if (durationTotal >= 60) "${durationTotal / 60}h ${durationTotal % 60}m" else "${durationTotal}m"

        // Pace: Strava-style display
        // Runs/hikes/walks → min/km | Swims → min/100m | Rides/ski → km/h
        val actType = a.type?.lowercase() ?: ""
        val isRunType = actType in setOf("run", "trailrun", "virtualrun", "hike", "walk")
        val isSwim = actType in setOf("swim", "openwatersports")
        val paceStr = if (dist != null && dist > 0 && a.movingMinutes > 0) {
            when {
                isRunType -> {
                    val minPerKm = a.movingMinutes / (dist / 1000.0)
                    val paceMin = minPerKm.toInt()
                    val paceSec = ((minPerKm - paceMin) * 60).toInt()
                    "$paceMin:%02d /km".format(paceSec)
                }
                isSwim -> {
                    val minPer100m = a.movingMinutes / (dist / 100.0)
                    val paceMin = minPer100m.toInt()
                    val paceSec = ((minPer100m - paceMin) * 60).toInt()
                    "$paceMin:%02d /100m".format(paceSec)
                }
                else -> {
                    val kmh = (dist / 1000.0) / (a.movingMinutes / 60.0)
                    "${"%.1f".format(kmh)} km/h"
                }
            }
        } else "—"

        // HR
        val hrStr = if (a.avgHeartRate != null) "${a.avgHeartRate} bpm" else "—"

        // TRIMP bar color
        val trimpPct = (trimp / maxTrimp * 100).coerceIn(0.0, 100.0)
        val trimpColor = when {
            trimp > 200 -> "var(--red)"
            trimp > 100 -> "var(--amber)"
            else -> "var(--green)"
        }

        """<div class="workout-item">
    <div class="wi-header">
        <span class="wi-type-icon" aria-hidden="true">$typeIcon</span>
        <div>
            <div class="wi-name">$name</div>
            <div class="wi-date">$dateStr</div>
        </div>
    </div>
    <div class="wi-stats">
        <div class="wi-stat"><div class="wi-stat-label">Distance</div><div class="wi-stat-value">$distStr</div></div>
        <div class="wi-stat"><div class="wi-stat-label">Duration</div><div class="wi-stat-value">$durStr</div></div>
        <div class="wi-stat"><div class="wi-stat-label">Pace</div><div class="wi-stat-value">$paceStr</div></div>
        <div class="wi-stat"><div class="wi-stat-label">Avg HR</div><div class="wi-stat-value">$hrStr</div></div>
    </div>
    <div class="wi-trimp-row">
        <div class="wi-trimp-bar"><div class="wi-trimp-fill" style="width:${"%.0f".format(trimpPct)}%;background:$trimpColor;"></div></div>
        <span class="wi-trimp-val" style="color:$trimpColor;">${"%.0f".format(trimp)}</span>
    </div>
</div>"""
    }.joinToString("\n")

    return """<div class="workouts-card anim-in anim-d3">
    <div class="section-header">
        <span class="section-title">Recent Workouts</span>
        <span class="section-sub">${sorted.size} activities</span>
    </div>
    <div class="workouts-scroll">
$cards
    </div>
</div>"""
}

private fun renderWorkoutHistory(history: List<WorkoutHistoryEntry>): String {
    if (history.isEmpty()) {
        return """<div class="history-empty">No saved workouts yet. Generate your first session to build history.</div>"""
    }

    val items = history.joinToString("\n") { item ->
        val sourceBadge = if (item.source == "strava") {
            "<span class=\"badge strava-badge\">Strava</span>"
        } else {
            "<span class=\"badge demo-badge\">Demo</span>"
        }

        val philosophy = item.checkIn.coachingPhilosophy.replaceFirstChar(Char::uppercaseChar)
        val generatedAt = item.generatedAt.toString().replace("T", " ").take(16)

        """<div class="history-item">
    <div class="history-head">
        <span class="history-time">$generatedAt</span>
        <span class="history-meta">Legs ${item.checkIn.legFeeling}/10 · Mind ${item.checkIn.mentalReadiness}/10 · ${item.checkIn.timeAvailableMinutes}m · $philosophy $sourceBadge</span>
    </div>
    <div class="history-body">
        <p>${escapeHtml(item.workout.session)}</p>
    </div>
</div>"""
    }

    return """<div class="history-list">$items</div><p class="history-ephemeral-note">History is kept for this session only and clears on server restart.</p>"""
}
