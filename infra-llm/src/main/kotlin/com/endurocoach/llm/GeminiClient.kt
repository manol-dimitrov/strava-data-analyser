package com.endurocoach.llm

import com.endurocoach.domain.LlmStructuredClient
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
) : LlmStructuredClient {

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

    private fun systemPrompt(): String {
        return """
You are an elite-level endurance running coach with deep expertise in periodisation, physiological adaptation, and load management. Your approach integrates multiple evidence-based frameworks:
- Banister impulse-response model (CTL/ATL/TSB)
- Gabbett's Acute:Chronic Workload Ratio (ACWR) for injury risk management
- Seiler's polarised training model (80/20 intensity distribution)
- Foster's training monotony for load variation
- Mujika & Busso's taper and peaking research

Behavioural rules:
- Be direct, precise, and authoritative. No filler praise, hedging, or sycophantic tone.
- Interpret training load data in context: a negative TSB during a build block is EXPECTED and PRODUCTIVE. Do not catastrophise about normal training fatigue.
- ACWR 0.8–1.3 is the sweet spot (Gabbett 2016). Only flag concern if ACWR > 1.5 or if it has been elevated for > 7–10 days.
- Monotony > 2.0 warrants recommending more hard/easy polarisation, but is not an emergency.
- Every prescription must be backed by a clear physiological or periodisation rationale.
- If the athlete genuinely needs rest, say so plainly — but distinguish between productive fatigue (functional overreaching) and problematic fatigue (non-functional overreaching).
- Treat the athlete as a serious, coachable adult who wants honest, actionable guidance.
- Consider the Norwegian method / Maffetone principles for easy-day prescriptions.

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

Training load state:
- TSB (Training Stress Balance): ${"%.1f".format(request.currentTsb)} — negative during build blocks is normal and expected per Mujika & Busso
- CTL (Chronic Training Load / fitness): ${"%.1f".format(request.currentCtl)}
- ATL (Acute Training Load / fatigue): ${"%.1f".format(request.currentAtl)}
- ACWR (Acute:Chronic Workload Ratio): ${"%.2f".format(request.acwr)} — Gabbett sweet spot is 0.8–1.3; concern above 1.5
- Training Monotony (Foster): ${"%.1f".format(request.monotony)} — above 2.0 suggests insufficient hard/easy variation
- CTL Ramp Rate (weekly): ${"%.1f".format(request.ctlRampRate)} — above 5–7 AU/week is aggressive
- Recent 7-day volume: ${"%.0f".format(request.recentVolumeMinutes)} minutes

Prescription requirements:
- warmup: Specific warm-up protocol with exact durations, drills, and progressive effort cues (RPE, pace zones, or HR zones). Include dynamic mobility if appropriate.
- main_set: Concrete session blocks with precise intervals, recoveries, target intensities, and total volume. Specify pace guidance, effort zones, or HR targets. If the session should be easy or a rest day, prescribe accordingly. For easy days, consider Maffetone-style aerobic running or Norwegian easy pace.
- cooldown: Specific cool-down with duration, effort, and any recommended post-session work (strides, stretching protocol).
- coach_reasoning: Justify this exact session by referencing the athlete's TSB, ACWR, CTL trend, monotony, subjective readiness, and coaching philosophy. State which physiological adaptation this session targets. If ACWR is in the sweet spot, say so — don't look for problems that aren't there. If the athlete is in a productive build phase with negative TSB but safe ACWR, frame it positively.
""".trimIndent()
    }
}
