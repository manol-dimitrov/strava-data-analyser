package com.endurocoach.config

import io.ktor.server.application.Application

data class AppRuntimeConfig(
    val llmProvider: String,
    val llmModel: String,
    val llmApiKeyEnv: String,
    val llmBaseUrl: String,
    val philosophyRulePacks: Map<String, String>
)

fun Application.readRuntimeConfig(): AppRuntimeConfig {
    val provider = readOrDefault("enduroCoach.llm.provider", "gemini")
    val model = readOrDefault("enduroCoach.llm.model", "gemini-2.5-flash")
    val apiKeyEnv = readOrDefault("enduroCoach.llm.apiKeyEnv", "GEMINI_API_KEY")
    val baseUrl = readOrDefault("enduroCoach.llm.baseUrl", "https://generativelanguage.googleapis.com")

    val rulePacks = mapOf(
        "balanced" to (
            readOrNull("enduroCoach.coachingPhilosophy.balanced")
                ?: "Balance performance and recovery while maintaining consistency."
            ),
        "conservative" to (
            readOrNull("enduroCoach.coachingPhilosophy.conservative")
                ?: "Prioritize low-risk durability and avoid unnecessary intensity."
            ),
        "aggressive" to (
            readOrNull("enduroCoach.coachingPhilosophy.aggressive")
                ?: "Push adaptation with quality work when readiness permits."
            )
    )

    return AppRuntimeConfig(
        llmProvider = provider,
        llmModel = model,
        llmApiKeyEnv = apiKeyEnv,
        llmBaseUrl = baseUrl,
        philosophyRulePacks = rulePacks
    )
}

private fun Application.readOrNull(path: String): String? {
    return runCatching { environment.config.property(path).getString() }.getOrNull()
}

private fun Application.readOrDefault(path: String, fallback: String): String {
    return readOrNull(path) ?: fallback
}
