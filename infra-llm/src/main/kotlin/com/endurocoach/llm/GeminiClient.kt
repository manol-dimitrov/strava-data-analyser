package com.endurocoach.llm

import com.endurocoach.domain.ConversationMessage
import com.endurocoach.domain.LlmChatClient
import com.endurocoach.domain.LlmStructuredClient
import com.endurocoach.domain.WorkoutChatRequest
import com.endurocoach.domain.WorkoutPlan
import com.endurocoach.domain.WorkoutRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class GeminiClient(
    private val config: GeminiConfig,
    private val httpClient: HttpClient,
    private val structuredWorkoutGenerator: StructuredWorkoutGenerator = StructuredWorkoutGenerator()
) : LlmStructuredClient, LlmChatClient {

    override suspend fun generateWorkoutPlan(request: WorkoutRequest): WorkoutPlan {
        return structuredWorkoutGenerator.generate {
            val url = "${config.baseUrl}/v1beta/models/${config.model}:generateContent?key=${config.apiKey}"

            val response = httpClient.post(url) {
                contentType(ContentType.Application.Json)
                setBody(
                    GeminiGenerateRequest(
                        systemInstruction = GeminiContent(
                            parts = listOf(GeminiPart(text = systemPrompt()))
                        ),
                        contents = listOf(
                            GeminiContent(
                                role = "user",
                                parts = listOf(GeminiPart(text = userPrompt(request)))
                            )
                        ),
                        generationConfig = GeminiGenerationConfig(
                            temperature = 0.2,
                            responseMimeType = "application/json"
                        )
                    )
                )
            }.body<GeminiGenerateResponse>()

            val parts = response.candidates.firstOrNull()?.content?.parts
                ?: error("Gemini returned an empty response")

            // Thinking models (e.g. 2.5-flash) return thought parts first;
            // pick the last non-thought part which contains the actual output.
            val outputPart = parts.lastOrNull { it.thought != true }
                ?: parts.lastOrNull()

            outputPart?.text?.trim()
                ?: error("Gemini returned an empty response")
        }
    }

    override suspend fun chat(request: WorkoutChatRequest): String {
        val url = "${config.baseUrl}/v1beta/models/${config.model}:generateContent?key=${config.apiKey}"

        val historyContents = request.history.map { msg ->
            GeminiContent(
                role = msg.role,
                parts = listOf(GeminiPart(text = msg.content))
            )
        }
        val userContent = GeminiContent(
            role = "user",
            parts = listOf(GeminiPart(text = request.userMessage))
        )

        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(
                GeminiGenerateRequest(
                    systemInstruction = GeminiContent(
                        parts = listOf(GeminiPart(text = request.systemInstruction))
                    ),
                    contents = historyContents + userContent,
                    generationConfig = GeminiGenerationConfig(temperature = 0.6)
                )
            )
        }.body<GeminiGenerateResponse>()

        val parts = response.candidates.firstOrNull()?.content?.parts
            ?: error("Gemini returned an empty chat response")

        val outputPart = parts.lastOrNull { it.thought != true } ?: parts.lastOrNull()
        return outputPart?.text?.trim() ?: error("Gemini returned an empty chat response")
    }

    private fun systemPrompt(): String {
        return """
You are an elite-level endurance running coach with deep expertise in periodisation, physiological adaptation, and load management. Your approach integrates multiple evidence-based frameworks:
- Banister impulse-response model (CTL/ATL/TSB)
- Gabbett's Acute:Chronic Workload Ratio (ACWR) for injury risk management
- Seiler's polarised training model (80/20 intensity distribution)
- Canova marathon-specific aerobic progression and hard/easy session sequencing
- Daniels' Running Formula intensity distribution (E, M, T, I, R)
- Norwegian threshold principles (high-quality threshold work, easy days truly easy)
- Foster's training monotony for load variation
- Mujika & Busso's taper and peaking research
- Skiba & Billat lactate threshold models: LT1 (first lactate threshold / aerobic threshold, ~75% HRR) is the all-day aerobic ceiling — easy runs should stay below it; LT2 (lactate/anaerobic threshold, ~87% HRR) is the comfortably-hard ceiling for tempo and threshold work — interval efforts target this zone or above

Behavioural rules:
- Be direct, precise, and authoritative. No filler praise, hedging, or sycophantic tone.
- Interpret TSB with context and clear bands:
    - TSB < -10: heavy fatigue; prescribe recovery/easy only.
    - TSB -10 to -5: moderate fatigue; no high-intensity quality.
    - TSB -5 to +5: normal productive training zone.
    - TSB > +5: freshness supports quality when other signals agree.
- ACWR 0.8–1.3 is the sweet spot (Gabbett 2016). Only flag concern if ACWR > 1.5 or if it has been elevated for > 7–10 days.
- Monotony > 2.0 warrants recommending more hard/easy polarisation, but is not an emergency.
- Every prescription must be backed by a clear physiological or periodisation rationale.
- If the athlete genuinely needs rest, say so plainly — but distinguish between productive fatigue (functional overreaching) and problematic fatigue (non-functional overreaching).
- Treat the athlete as a serious, coachable adult who wants honest, actionable guidance.
- Hard-session recency rule (strict):
    - Never prescribe a quality/hard workout if the last HARD session (Zone 3+, >=82% HRR) was < 48 hours ago.
    - If last HARD session was < 72 hours ago and ATL > CTL, prescribe recovery/easy only.
- Always blend these method anchors into your decision, regardless of coaching philosophy:
    - Canova: event-specific aerobic strength and controlled quality; protect easy days after hard sessions.
    - Daniels: choose stress by adaptation target (E/M/T/I/R); avoid stacking I/R efforts without recovery.
    - Norwegian: threshold work should stay near threshold (not above); keep easy runs genuinely easy.

Pace range requirements (MANDATORY):
- Every segment (warmup, main_set, cooldown) MUST include a pace range in the format M:SS–M:SS/km.
- Anchor paces to the athlete's provided pace profile when available:
    - Easy / recovery segments → below LT1 pace (easy pace zone ± 10 s/km)
    - Tempo / threshold segments → LT1–LT2 pace zone (tempo pace ± 5 s/km)
    - Interval / VO2max segments → at or faster than LT2 pace (vo2max pace ± 5 s/km)
- If no pace profile is provided, derive evidence-based zone estimates from the athlete's sport focus and race distance target.
- Adjust paces relative to the race focus: marathon runners need more LT1-zone volume; 5 km runners need more LT2/VO2max work.

Output contract:
- Return exactly one JSON object with these two keys and no others: session, coach_reasoning.
- session: the complete workout as flowing prose — no sub-headings, no bullet structure. For quality sessions (intervals, tempo, threshold), weave in a brief warm-up note at the start and a brief cool-down note at the end within the same text. For easy or recovery runs, describe only the run itself with no warm-up or cool-down mention.
- Include pace ranges in M:SS–M:SS/km anchored to the athlete's pace profile where provided. Adjust zones per race focus: marathon = LT1-zone dominance; 5 km = LT2/VO2max emphasis.
- coach_reasoning: concise justification referencing load state, readiness, and the physiological target.
- All values must be non-empty strings.
- No markdown formatting, no wrapper text, no additional keys.
""".trimIndent()
    }

    private fun userPrompt(request: WorkoutRequest): String {
        return """
Prescribe today's running session based on the athlete's current state.

Athlete readiness:
- Leg feeling: ${request.checkIn.legFeeling}/10
- Mental readiness: ${request.checkIn.mentalReadiness}/10
- Time available: ${request.checkIn.timeAvailableMinutes} minutes
- Coaching philosophy: ${request.checkIn.coachingPhilosophy}

Training load state:
- Last hard session (Zone 3+): ${formatDaysSinceHard(request.daysSinceLastHardSession)}
- TSB (Training Stress Balance): ${"%.1f".format(request.currentTsb)}
- CTL (Chronic Training Load / fitness): ${"%.1f".format(request.currentCtl)}
- ATL (Acute Training Load / fatigue): ${"%.1f".format(request.currentAtl)}
- ACWR (Acute:Chronic Workload Ratio): ${"%.2f".format(request.acwr)} — Gabbett sweet spot is 0.8–1.3; concern above 1.5
- Training Monotony (Foster): ${"%.1f".format(request.monotony)} — above 2.0 suggests insufficient hard/easy variation
- CTL Ramp Rate (weekly): ${"%.1f".format(request.ctlRampRate)} — above 5–7 AU/week is aggressive
- Recent 7-day volume: ${"%.0f".format(request.recentVolumeMinutes)} minutes
- 10-day Spike Ratio: ${"%.2f".format(request.spike10)} — ratio of 10-day rolling load to the full-window daily average baseline; above 1.3 warrants monitoring, above 1.5 is a concern even if standard ACWR looks safe
- 10-day Strain (Foster): ${"%.0f".format(request.strain10)} — accumulated load × monotony over 10 days; above 700 is high and suggests the athlete is carrying both volume and insufficient variation

Recent training (last 7 days):
${buildRecentSessionsBlock(request)}

${buildPaceProfileBlock(request)}

${buildRaceFocusBlock(request.checkIn.raceDistance)}

Prescription requirements:
- session: Write the full workout as a single block of flowing prose. For quality sessions (intervals, tempo, threshold work): open with a brief warm-up in the same text (e.g. "After a 10-min easy jog at 5:50/km..."), describe the main effort with specific intervals, recoveries, and pace ranges (M:SS–M:SS/km), then close with a brief cool-down note (e.g. "...followed by 5 min easy to finish"). For easy or recovery runs: describe only the run with pace range; do not mention warm-up or cool-down.
- coach_reasoning: Justify this exact session by referencing the athlete's TSB, ACWR, CTL trend, monotony, subjective readiness, coaching philosophy, and race focus. State which physiological adaptation this session targets and how the prescribed paces align with LT1/LT2 zones. If ACWR is in the sweet spot, say so — don't look for problems that aren't there.
""".trimIndent()
    }

    private fun formatDaysSinceHard(daysSinceLastHardSession: Int?): String {
        return when (daysSinceLastHardSession) {
            null -> "No hard session recorded in the last 14 days"
            0 -> "Today"
            1 -> "1 day ago"
            else -> "$daysSinceLastHardSession days ago"
        }
    }

    private fun buildRecentSessionsBlock(request: WorkoutRequest): String {
        if (request.recentSessions.isEmpty()) {
            return "- No activities recorded in the last 7 days"
        }

        return request.recentSessions.joinToString("\n") { session ->
            val dayLabel = when (val daysAgo = java.time.temporal.ChronoUnit.DAYS.between(session.date, java.time.LocalDate.now()).toInt()) {
                0 -> "today"
                1 -> "1 day ago"
                else -> "$daysAgo days ago"
            }
            "- ${session.date} ($dayLabel): ${session.name}, ${"%.0f".format(session.durationMinutes)} min, TRIMP ${"%.1f".format(session.trimp)}, ${session.intensity}"
        }
    }

    private fun buildPaceProfileBlock(request: WorkoutRequest): String {
        val profile = request.recentPaceProfile
            ?: return """Athlete Pace Profile (last 14 days):
- No reliable pace data available. Derive evidence-based zone estimates for the stated race focus."""

        fun Int.toMmSs(): String {
            val m = this / 60
            val s = this % 60
            return "$m:%02d".format(s)
        }

        val easyLine   = profile.easyPaceSecPerKm?.let {
            val lo = (it - 10).toMmSs(); val hi = (it + 10).toMmSs()
            "Easy (below LT1): $lo–$hi/km"
        } ?: "Easy (below LT1): insufficient data"

        val tempoLine  = profile.tempoPaceSecPerKm?.let {
            val lo = (it - 5).toMmSs(); val hi = (it + 5).toMmSs()
            "Tempo (LT1–LT2): $lo–$hi/km"
        } ?: "Tempo (LT1–LT2): insufficient data"

        val vo2maxLine = profile.vo2maxPaceSecPerKm?.let {
            val lo = (it - 5).toMmSs(); val hi = (it + 5).toMmSs()
            "VO2max (above LT2): $lo–$hi/km"
        } ?: if (profile.fallbackUsed) "VO2max (above LT2): not available (HR data absent)" else "VO2max (above LT2): no recent high-intensity runs to reference"

        val fallbackNote = if (profile.fallbackUsed)
            " (Note: HR data was absent — zones estimated via pace-median split; treat with moderate confidence.)"
        else ""

        return """Athlete Pace Profile (last 14 days, ${profile.runCount} runs$fallbackNote):
- $easyLine
- $tempoLine
- $vo2maxLine"""
    }

    private fun buildRaceFocusBlock(raceDistance: String): String {
        val (label, guidance) = when (raceDistance) {
            "5km"           -> "5 km" to "Emphasise VO2max development and speed. Prescribe short, high-intensity intervals (e.g. 200–400 m reps) at or above LT2 pace. Easy volume is secondary to quality."
            "10km"          -> "10 km" to "Balance LT2 threshold work with VO2max intervals. Sessions should mix tempo runs in the LT1–LT2 zone with occasional short VO2max efforts. Aerobic base supports both."
            "half_marathon" -> "Half Marathon" to "Prioritise LT2 tempo work and extended threshold efforts (e.g. 20–40 min at LT2 pace). Maintain solid aerobic base with easy LT1-zone long runs."
            "marathon"      -> "Marathon" to "Prioritise aerobic base and LT1-pace running. The majority of sessions (80%+) should be at or below LT1 pace. Include occasional marathon-pace efforts (between LT1 and LT2). Minimise vo2max stress outside sharpening phase."
            else            -> "10 km" to "Balance LT2 threshold work with VO2max intervals."
        }
        return """Today's Race Focus: $label
- $guidance"""
    }
}
