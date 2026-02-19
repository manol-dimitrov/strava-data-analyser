package com.endurocoach.llm

data class GeminiConfig(
    val apiKey: String,
    val model: String = "gemini-2.5-flash",
    val baseUrl: String = "https://generativelanguage.googleapis.com"
) {
    companion object {
        fun fromEnv(
            apiKeyEnv: String = "GEMINI_API_KEY",
            model: String = System.getenv("GEMINI_MODEL") ?: "gemini-2.5-flash",
            baseUrl: String = System.getenv("GEMINI_BASE_URL")
                ?: "https://generativelanguage.googleapis.com"
        ): GeminiConfig? {
            val apiKey = System.getenv(apiKeyEnv)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return GeminiConfig(
                apiKey = apiKey,
                model = model,
                baseUrl = baseUrl.trimEnd('/')
            )
        }
    }
}
