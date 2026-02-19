package com.endurocoach.llm

data class PerplexityConfig(
    val apiKey: String,
    val model: String = "sonar",
    val baseUrl: String = "https://api.perplexity.ai"
) {
    companion object {
        fun fromEnv(
            apiKeyEnv: String = "PERPLEXITY_API_KEY",
            model: String = System.getenv("PERPLEXITY_MODEL") ?: "sonar",
            baseUrl: String = System.getenv("PERPLEXITY_BASE_URL") ?: "https://api.perplexity.ai"
        ): PerplexityConfig? {
            val apiKey = System.getenv(apiKeyEnv)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return PerplexityConfig(
                apiKey = apiKey,
                model = model,
                baseUrl = baseUrl.trimEnd('/')
            )
        }
    }
}
