package com.endurocoach.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PerplexityChatRequest(
    val model: String,
    val messages: List<PerplexityMessage>,
    val temperature: Double = 0.2
)

@Serializable
data class PerplexityMessage(
    val role: String,
    val content: String
)

@Serializable
data class PerplexityChatResponse(
    val choices: List<PerplexityChoice> = emptyList()
)

@Serializable
data class PerplexityChoice(
    val message: PerplexityMessage
)
