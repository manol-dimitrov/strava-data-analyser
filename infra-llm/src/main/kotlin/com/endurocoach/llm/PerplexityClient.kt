package com.endurocoach.llm

import com.endurocoach.domain.LlmStructuredClient
import com.endurocoach.domain.WorkoutPlan
import com.endurocoach.domain.WorkoutRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import com.endurocoach.domain.RecentPaceProfile
import com.endurocoach.llm.StructuredWorkoutGenerator

class PerplexityClient(
    private val config: PerplexityConfig,
    private val httpClient: HttpClient,
    private val structuredWorkoutGenerator: StructuredWorkoutGenerator = StructuredWorkoutGenerator()
) : LlmStructuredClient {
    override suspend fun generateWorkoutPlan(request: WorkoutRequest): WorkoutPlan {
        return structuredWorkoutGenerator.generate {
            val response = httpClient.post(
                "${config.baseUrl}/chat/completions"
            ) {
                header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                setBody(
                    PerplexityChatRequest(
                        model = config.model,
                        messages = listOf(
                            PerplexityMessage(role = "system", content = systemPrompt()),
                            PerplexityMessage(role = "user", content = userPrompt(request))
                        )
                    )
                )
            }.body<PerplexityChatResponse>()

            response.choices.firstOrNull()?.message?.content?.trim()
                ?: error("Perplexity returned an empty response")
        }
    }

    private fun systemPrompt(): String {
        return """
You are an elite-level endurance running coach with deep expertise in periodisation, physiological adaptation, and load management. You have decades of experience coaching athletes from competitive club level through international championship standard.

Behavioural rules:
- Be direct, precise, and authoritative. Never use filler praise, hedging language, or sycophantic tone.
- If the athlete's readiness data suggests they should rest or do less, say so plainly.
- Every prescription must be backed by a clear physiological or periodisation rationale.
- Do not soften bad news. If load state indicates accumulated fatigue, state the risk and adjust the session accordingly.
- Treat the athlete as a serious, coachable adult who wants honest, actionable guidance.
- Lactate threshold theory: LT1 (aerobic threshold, ~75% HRR) is the all-day pace ceiling for easy running; LT2 (lactate/anaerobic threshold, ~87% HRR) is the comfortably-hard ceiling for tempo work. Intervals target LT2 or above.

Output contract:
- Return exactly one JSON object with these two keys and no others: session, coach_reasoning.
- session: the complete workout as flowing prose — no sub-headings, no bullet structure. For quality sessions (intervals, tempo, threshold), weave in a brief warm-up note at the start and a brief cool-down note at the end within the same text. For easy or recovery runs, describe only the run itself with no warm-up or cool-down mention.
- Include pace ranges in M:SS–M:SS/km anchored to the athlete's pace profile where provided.
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

Training load state (Banister impulse-response model):
- TSB (Training Stress Balance): ${"%.2f".format(request.currentTsb)}
- CTL (Chronic Training Load / fitness): ${"%.2f".format(request.currentCtl)}
- ATL (Acute Training Load / fatigue): ${"%.2f".format(request.currentAtl)}
- Recent 7-day volume: ${"%.1f".format(request.recentVolumeMinutes)} minutes

${buildPaceProfileBlock(request)}

${buildRaceFocusBlock(request.checkIn.raceDistance)}

Prescription requirements:
- session: Write the full workout as a single block of flowing prose. For quality sessions: open with a brief warm-up note, describe the main effort with specific pace ranges (M:SS–M:SS/km), then close with a brief cool-down note. For easy or recovery runs: describe only the run; do not mention warm-up or cool-down.
- coach_reasoning: Justify this exact session referencing load state, readiness, coaching philosophy, race focus, and how prescribed paces align with LT1/LT2 zones.
""".trimIndent()
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
            "Easy (below LT1): ${(it - 10).toMmSs()}–${(it + 10).toMmSs()}/km"
        } ?: "Easy (below LT1): insufficient data"

        val tempoLine  = profile.tempoPaceSecPerKm?.let {
            "Tempo (LT1–LT2): ${(it - 5).toMmSs()}–${(it + 5).toMmSs()}/km"
        } ?: "Tempo (LT1–LT2): insufficient data"

        val vo2maxLine = profile.vo2maxPaceSecPerKm?.let {
            "VO2max (above LT2): ${(it - 5).toMmSs()}–${(it + 5).toMmSs()}/km"
        } ?: if (profile.fallbackUsed) "VO2max (above LT2): not available (HR data absent)" else "VO2max (above LT2): no recent high-intensity runs"

        val fallbackNote = if (profile.fallbackUsed) " (HR absent — pace-median split)" else ""
        return """Athlete Pace Profile (last 14 days, ${profile.runCount} runs$fallbackNote):
- $easyLine
- $tempoLine
- $vo2maxLine"""
    }

    private fun buildRaceFocusBlock(raceDistance: String): String {
        val (label, guidance) = when (raceDistance) {
            "5km"           -> "5 km" to "Emphasise VO2max development. Prescribe short high-intensity intervals at or above LT2 pace."
            "10km"          -> "10 km" to "Balance LT2 threshold work with VO2max intervals. Mix tempo runs with occasional short VO2max efforts."
            "half_marathon" -> "Half Marathon" to "Prioritise LT2 tempo work and extended threshold efforts. Maintain aerobic base with easy LT1-zone long runs."
            "marathon"      -> "Marathon" to "80%+ of sessions at or below LT1 pace. Include marathon-pace efforts (LT1–LT2). Minimise VO2max stress outside sharpening phase."
            else            -> "10 km" to "Balance LT2 threshold work with VO2max intervals."
        }
        return """Today's Race Focus: $label
- $guidance"""
    }
}
