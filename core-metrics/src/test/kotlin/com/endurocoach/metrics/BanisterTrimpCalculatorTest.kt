package com.endurocoach.metrics

import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BanisterTrimpCalculatorTest {

    private val calc = BanisterTrimpCalculator(maxHeartRate = 190, restingHeartRate = 50)

    @Test
    fun zeroOrNegativeDurationReturnsZero() {
        assertEquals(0.0, calc.calculate(0.0, 150))
        assertEquals(0.0, calc.calculate(-10.0, 150))
    }

    @Test
    fun nullHeartRateReturnsZero() {
        assertEquals(0.0, calc.calculate(60.0, null))
    }

    @Test
    fun knownValueAtMidReserve() {
        // HR = 120 → hrRatio = (120-50)/(190-50) = 70/140 = 0.5
        // TRIMP = 60 * 0.5 * 0.64 * exp(1.92 * 0.5) = 60 * 0.5 * 0.64 * exp(0.96)
        val hrRatio = 0.5
        val expected = 60.0 * hrRatio * 0.64 * exp(1.92 * hrRatio)
        val actual = calc.calculate(60.0, 120)
        assertTrue(abs(expected - actual) < 0.001, "Expected $expected, got $actual")
    }

    @Test
    fun heartRateBelowRestingClampsToZeroRatio() {
        // HR = 40, below resting 50 → hrRatio clamped to 0 → TRIMP = 0 * ... = 0
        // Actually: 60 * 0 * 0.64 * exp(0) = 0.0
        val result = calc.calculate(60.0, 40)
        assertEquals(0.0, result)
    }

    @Test
    fun heartRateAboveMaxClampsToOneRatio() {
        // HR = 210, above max 190 → hrRatio clamped to 1.0
        val expected = 60.0 * 1.0 * 0.64 * exp(1.92)
        val actual = calc.calculate(60.0, 210)
        assertTrue(abs(expected - actual) < 0.001, "Expected $expected, got $actual")
    }

    @Test
    fun trimpScalesLinearlyWithDuration() {
        val trimp30 = calc.calculate(30.0, 150)
        val trimp60 = calc.calculate(60.0, 150)
        assertTrue(abs(trimp60 - 2 * trimp30) < 0.001, "TRIMP should scale linearly with duration")
    }

    @Test
    fun higherHeartRateProducesHigherTrimp() {
        val trimpLow = calc.calculate(60.0, 130)
        val trimpHigh = calc.calculate(60.0, 170)
        assertTrue(trimpHigh > trimpLow, "Higher HR should produce higher TRIMP")
    }
}
