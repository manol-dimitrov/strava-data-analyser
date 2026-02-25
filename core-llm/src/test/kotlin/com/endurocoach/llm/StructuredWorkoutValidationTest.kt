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
            "session": "After a 10-min easy jog, run 5 x 1km at threshold.",
            "coach_reasoning": "TSB is positive, time to push."
        }
        """.trimIndent()

        val plan = StructuredWorkoutValidation.parseAndValidate(json)
        assertEquals("After a 10-min easy jog, run 5 x 1km at threshold.", plan.session)
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
            "session": "Easy 30-min run at conversational pace."
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
            "session": "Easy 30-min run.",
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
            "session": "   ",
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
            "session": 42,
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
            "session": "",
            "coach_reasoning": "Because."
        }
        """.trimIndent()

        assertFailsWith<InvalidWorkoutSchemaException> {
            StructuredWorkoutValidation.parseAndValidate(json)
        }
    }
}
