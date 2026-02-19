package com.endurocoach.llm

import com.endurocoach.domain.WorkoutPlan

class StructuredWorkoutGenerator(
    private val maxSchemaAttempts: Int = 2
) {
    suspend fun generate(call: suspend () -> String): WorkoutPlan {
        var lastSchemaError: Throwable? = null

        repeat(maxSchemaAttempts) { attempt ->
            val payload = call()
            runCatching { StructuredWorkoutValidation.parseAndValidate(payload) }
                .onSuccess { return it }
                .onFailure { error ->
                    if (error is WorkoutSchemaException && attempt < maxSchemaAttempts - 1) {
                        lastSchemaError = error
                    } else {
                        throw error
                    }
                }
        }

        throw InvalidWorkoutSchemaException(
            lastSchemaError?.message ?: "LLM response failed schema validation"
        )
    }
}
