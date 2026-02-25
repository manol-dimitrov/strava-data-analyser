package com.endurocoach.llm

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StructuredWorkoutGeneratorTest {

    private val validJson = """
        {
            "session": "After a 10-min easy jog, run 6 x 800m with 90s rest, then 5 min easy to finish.",
            "coach_reasoning": "Load is manageable."
        }
    """.trimIndent()

    private val invalidJson = """{ "bad": "schema" }"""

    @Test
    fun returnsOnFirstValidResponse() = runTest {
        var callCount = 0
        val generator = StructuredWorkoutGenerator(maxSchemaAttempts = 2)
        val plan = generator.generate {
            callCount++
            validJson
        }
        assertEquals(1, callCount, "Should not retry when first response is valid")
        assertEquals("After a 10-min easy jog, run 6 x 800m with 90s rest, then 5 min easy to finish.", plan.session)
    }

    @Test
    fun retriesOnceAndSucceeds() = runTest {
        var callCount = 0
        val generator = StructuredWorkoutGenerator(maxSchemaAttempts = 2)
        val plan = generator.generate {
            callCount++
            if (callCount == 1) invalidJson else validJson
        }
        assertEquals(2, callCount, "Should retry once after first invalid response")
        assertEquals("After a 10-min easy jog, run 6 x 800m with 90s rest, then 5 min easy to finish.", plan.session)
    }

    @Test
    fun failsAfterMaxAttempts() = runTest {
        var callCount = 0
        val generator = StructuredWorkoutGenerator(maxSchemaAttempts = 2)
        assertFailsWith<WorkoutSchemaException> {
            generator.generate {
                callCount++
                invalidJson
            }
        }
        assertEquals(2, callCount, "Should exhaust all attempts before throwing")
    }

    @Test
    fun singleAttemptModeFailsImmediately() = runTest {
        var callCount = 0
        val generator = StructuredWorkoutGenerator(maxSchemaAttempts = 1)
        assertFailsWith<WorkoutSchemaException> {
            generator.generate {
                callCount++
                invalidJson
            }
        }
        assertEquals(1, callCount)
    }

    @Test
    fun nonSchemaExceptionPropagatesImmediately() = runTest {
        var callCount = 0
        val generator = StructuredWorkoutGenerator(maxSchemaAttempts = 2)
        assertFailsWith<RuntimeException> {
            generator.generate {
                callCount++
                throw RuntimeException("network error")
            }
        }
        assertEquals(1, callCount, "Non-schema errors should not trigger retry")
    }
}
