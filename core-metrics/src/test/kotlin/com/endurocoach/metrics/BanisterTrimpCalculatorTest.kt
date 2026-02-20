package com.endurocoach.metrics

import kotlin.math.abs
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
        // Zone 1 (< 0.68): zoneWeight = 1.0
        // TRIMP = 60 * 0.5 * 1.0 = 30.0
        val expected = 60.0 * 0.5 * 1.0
        val actual = calc.calculate(60.0, 120)
        assertTrue(abs(expected - actual) < 0.001, "Expected $expected, got $actual")
    }

    @Test
    fun tempoZoneUsesHigherWeight() {
        // HR = 165 → hrRatio = (165-50)/140 = 0.821
        // Zone 3 (0.82-0.90): zoneWeight = 3.0
        // TRIMP = 60 * 0.821 * 3.0 = 147.86
        val hrRatio = (165.0 - 50) / 140
        val expected = 60.0 * hrRatio * 3.0
        val actual = calc.calculate(60.0, 165)
        assertTrue(abs(expected - actual) < 0.01, "Expected $expected, got $actual")
    }

    @Test
    fun heartRateBelowRestingClampsToZeroRatio() {
        val result = calc.calculate(60.0, 40)
        assertEquals(0.0, result)
    }

    @Test
    fun heartRateAboveMaxClampsToOneRatio() {
        // HR = 210, above max 190 → hrRatio clamped to 1.0, zone 4 weight = 4.0
        val expected = 60.0 * 1.0 * 4.0
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
