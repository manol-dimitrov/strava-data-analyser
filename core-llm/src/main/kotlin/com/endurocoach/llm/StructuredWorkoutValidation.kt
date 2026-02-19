package com.endurocoach.llm

import com.endurocoach.domain.WorkoutPlan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed class WorkoutSchemaException(message: String) : RuntimeException(message)

class InvalidWorkoutSchemaException(message: String) : WorkoutSchemaException(message)

class MissingWorkoutKeyException(key: String) : WorkoutSchemaException("Missing required key: $key")

object StructuredWorkoutValidation {
    private val requiredKeys = setOf("warmup", "main_set", "cooldown", "coach_reasoning")
    private val json = Json { ignoreUnknownKeys = false }

    fun parseAndValidate(payload: String): WorkoutPlan {
        val root = runCatching { json.parseToJsonElement(payload) }
            .getOrElse { throw InvalidWorkoutSchemaException("LLM response is not valid JSON") }

        val obj = root as? JsonObject
            ?: throw InvalidWorkoutSchemaException("LLM response must be a JSON object")

        if (obj.keys != requiredKeys) {
            throw InvalidWorkoutSchemaException(
                "LLM response keys must match exactly: ${requiredKeys.joinToString(", ")}. Got: ${obj.keys.joinToString(", ")}"
            )
        }

        return WorkoutPlan(
            warmup = obj.requiredString("warmup"),
            mainSet = obj.requiredString("main_set"),
            cooldown = obj.requiredString("cooldown"),
            coachReasoning = obj.requiredString("coach_reasoning")
        )
    }

    private fun JsonObject.requiredString(key: String): String {
        val value = this[key] ?: throw MissingWorkoutKeyException(key)
        val primitive = value as? JsonPrimitive
            ?: throw InvalidWorkoutSchemaException("$key must be a JSON string")
        if (!primitive.isString) {
            throw InvalidWorkoutSchemaException("$key must be a JSON string")
        }

        return primitive.content.trim().takeIf { it.isNotEmpty() }
            ?: throw InvalidWorkoutSchemaException("$key cannot be blank")
    }
}
