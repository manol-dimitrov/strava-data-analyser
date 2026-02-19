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

Output contract:
- Return exactly one JSON object with these four keys and no others: warmup, main_set, cooldown, coach_reasoning.
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

Prescription requirements:
- warmup: Specific warm-up protocol with exact durations, drills, and progressive effort cues (e.g. RPE, pace zones, or HR zones). Include dynamic mobility if appropriate.
- main_set: Concrete session blocks with precise intervals, recoveries, target intensities, and total volume. Specify pace guidance, effort zones, or HR targets. If the session should be easy or a rest day, prescribe accordingly with clear rationale.
- cooldown: Specific cool-down with duration, effort, and any recommended post-session work (e.g. strides, stretching protocol).
- coach_reasoning: Justify this exact session by referencing the athlete's TSB, CTL/ATL balance, subjective readiness scores, recent volume trends, and the coaching philosophy. Explain what physiological adaptation this session targets (or why recovery is prioritised). If you are deviating from what the athlete might expect, explain why.
""".trimIndent()
    }
}
