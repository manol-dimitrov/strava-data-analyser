package com.endurocoach.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Request ---

@Serializable
data class GeminiGenerateRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig? = null,
    val systemInstruction: GeminiContent? = null
)

@Serializable
data class GeminiContent(
    val parts: List<GeminiPart>,
    val role: String? = null
)

@Serializable
data class GeminiPart(
    val text: String? = null,
    val thought: Boolean? = null
)

@Serializable
data class GeminiGenerationConfig(
    val temperature: Double? = null,
    @SerialName("responseMimeType")
    val responseMimeType: String? = null
)

// --- Response ---

@Serializable
data class GeminiGenerateResponse(
    val candidates: List<GeminiCandidate> = emptyList()
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null
)
