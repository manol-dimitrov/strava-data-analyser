package com.endurocoach.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class StructuredWorkoutValidationTest {

    @Test
    fun validPayloadParses() {
        val json = """
        {
            "warmup": "10 min easy jog",
            "main_set": "5 x 1km at threshold",
            "cooldown": "10 min walk",
            "coach_reasoning": "TSB is positive, time to push."
        }
        """.trimIndent()

        val plan = StructuredWorkoutValidation.parseAndValidate(json)
        assertEquals("10 min easy jog", plan.warmup)
        assertEquals("5 x 1km at threshold", plan.mainSet)
        assertEquals("10 min walk", plan.cooldown)
        assertEquals("TSB is positive, time to push.", plan.coachReasoning)
    }

    @Test
    fun invalidJsonThrows() {
        assertFailsWith<InvalidWorkoutSchemaException> {
            StructuredWorkoutValidation.parseAndValidate("not json at all")
        }
    }

    @Test
    fun jsonArrayThrows() {
        assertFailsWith<InvalidWorkoutSchemaException> {
            StructuredWorkoutValidation.parseAndValidate("""["warmup", "main_set"]""")
        }
    }

    @Test
    fun missingKeyThrows() {
        val json = """
        {
            "warmup": "10 min easy jog",
            "main_set": "5 x 1km at threshold",
            "cooldown": "10 min walk"
        }
        """.trimIndent()

        val ex = assertFailsWith<InvalidWorkoutSchemaException> {
            StructuredWorkoutValidation.parseAndValidate(json)
        }
        assertNotNull(ex.message)
    }

    @Test
    fun extraKeyThrows() {
        val json = """
        {
            "warmup": "10 min easy jog",
            "main_set": "5 x 1km at threshold",
            "cooldown": "10 min walk",
            "coach_reasoning": "Looks good.",
            "bonus": "extra field"
        }
        """.trimIndent()

        assertFailsWith<InvalidWorkoutSchemaException> {
            StructuredWorkoutValidation.parseAndValidate(json)
        }
    }

    @Test
    fun blankValueThrows() {
        val json = """
        {
            "warmup": "   ",
            "main_set": "5 x 1km at threshold",
            "cooldown": "10 min walk",
            "coach_reasoning": "Data driven."
        }
        """.trimIndent()

        assertFailsWith<InvalidWorkoutSchemaException> {
            StructuredWorkoutValidation.parseAndValidate(json)
        }
    }

    @Test
    fun numericValueThrows() {
        val json = """
        {
            "warmup": 42,
            "main_set": "5 x 1km at threshold",
            "cooldown": "10 min walk",
            "coach_reasoning": "Because numbers."
        }
        """.trimIndent()

        assertFailsWith<InvalidWorkoutSchemaException> {
            StructuredWorkoutValidation.parseAndValidate(json)
        }
    }

    @Test
    fun emptyStringValueThrows() {
        val json = """
        {
            "warmup": "",
            "main_set": "5 x 1km at threshold",
            "cooldown": "10 min walk",
            "coach_reasoning": "Because."
        }
        """.trimIndent()

        assertFailsWith<InvalidWorkoutSchemaException> {
            StructuredWorkoutValidation.parseAndValidate(json)
        }
    }
}
