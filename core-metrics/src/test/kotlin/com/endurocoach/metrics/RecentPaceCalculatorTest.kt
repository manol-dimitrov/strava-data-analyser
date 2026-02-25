package com.endurocoach.metrics

import com.endurocoach.domain.Activity
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecentPaceCalculatorTest {

    // maxHr=190, restingHr=50, HRR=140
    // LT1 = 50 + 0.75*140 = 155
    // LT2 = 50 + 0.87*140 = 171.8
    private val maxHr = 190
    private val restingHr = 50

    private fun run(
        daysAgo: Long = 3,
        distanceMeters: Double = 10_000.0,
        movingMinutes: Double = 60.0,
        avgHr: Int? = null,
        type: String = "Run"
    ) = Activity(
        date = LocalDate.now().minusDays(daysAgo),
        durationMinutes = movingMinutes,
        movingMinutes = movingMinutes,
        avgHeartRate = avgHr,
        type = type,
        distanceMeters = distanceMeters
    )

    // ─── happy path: three zones with full HR data ────────────────────────────

    @Test
    fun threeZoneSplitWithHrData() {
        // Easy:   HR 140 (<155), pace 360 s/km (10 km in 60 min)
        // Tempo:  HR 160 (>=155, <171.8), pace 300 s/km (10 km in 50 min)
        // VO2max: HR 180 (>=171.8), pace 240 s/km (10 km in 40 min)
        val activities = listOf(
            run(daysAgo = 1, distanceMeters = 10_000.0, movingMinutes = 60.0, avgHr = 140),  // easy
            run(daysAgo = 3, distanceMeters = 10_000.0, movingMinutes = 50.0, avgHr = 160),  // tempo
            run(daysAgo = 5, distanceMeters = 10_000.0, movingMinutes = 40.0, avgHr = 180),  // vo2max
        )

        val profile = RecentPaceCalculator.calculate(activities, maxHr, restingHr)

        assertNotNull(profile)
        assertEquals(360, profile.easyPaceSecPerKm)    // 60*60/10 = 360
        assertEquals(300, profile.tempoPaceSecPerKm)   // 50*60/10 = 300
        assertEquals(240, profile.vo2maxPaceSecPerKm)  // 40*60/10 = 240
        assertEquals(3, profile.runCount)
        assertEquals(false, profile.fallbackUsed)
    }

    @Test
    fun easyZoneIsNullWhenNoEasyRuns() {
        // All runs are tempo/vo2max
        val activities = listOf(
            run(daysAgo = 1, distanceMeters = 10_000.0, movingMinutes = 50.0, avgHr = 160),
            run(daysAgo = 3, distanceMeters = 10_000.0, movingMinutes = 48.0, avgHr = 162),
            run(daysAgo = 5, distanceMeters = 10_000.0, movingMinutes = 40.0, avgHr = 180),
        )

        val profile = RecentPaceCalculator.calculate(activities, maxHr, restingHr)

        assertNotNull(profile)
        assertNull(profile.easyPaceSecPerKm)
        assertNotNull(profile.tempoPaceSecPerKm)
        assertEquals(false, profile.fallbackUsed)
    }

    // ─── LT2 boundary edge ───────────────────────────────────────────────────

    @Test
    fun runAtExactlyLt2BoundaryGoesToVo2max() {
        // LT2 = 171.8, a run with avgHR=172 must go to vo2max (>= 171.8)
        val activities = listOf(
            run(daysAgo = 1, distanceMeters = 10_000.0, movingMinutes = 60.0, avgHr = 140),  // easy
            run(daysAgo = 3, distanceMeters = 10_000.0, movingMinutes = 60.0, avgHr = 140),  // easy
            run(daysAgo = 5, distanceMeters = 10_000.0, movingMinutes = 40.0, avgHr = 172),  // vo2max (>=171.8)
        )

        val profile = RecentPaceCalculator.calculate(activities, maxHr, restingHr)

        assertNotNull(profile)
        assertNotNull(profile.vo2maxPaceSecPerKm)
        assertNull(profile.tempoPaceSecPerKm)  // no tempo runs
        assertEquals(false, profile.fallbackUsed)
    }

    @Test
    fun runJustBelowLt2GoesToTempo() {
        // avgHR=171 < 171.8 → tempo
        val activities = listOf(
            run(daysAgo = 1, distanceMeters = 10_000.0, movingMinutes = 60.0, avgHr = 140),
            run(daysAgo = 3, distanceMeters = 10_000.0, movingMinutes = 50.0, avgHr = 171),  // tempo
            run(daysAgo = 5, distanceMeters = 10_000.0, movingMinutes = 50.0, avgHr = 156),  // tempo
        )

        val profile = RecentPaceCalculator.calculate(activities, maxHr, restingHr)

        assertNotNull(profile)
        assertNull(profile.vo2maxPaceSecPerKm)
        assertNotNull(profile.tempoPaceSecPerKm)
        assertEquals(false, profile.fallbackUsed)
    }

    // ─── fallback: no HR data ────────────────────────────────────────────────

    @Test
    fun medianPaceFallbackWhenNoHrData() {
        // 4 runs without HR; sorted slowest→fastest by pace:
        //   400 s/km (easy), 360, 320, 280 (tempo)
        // mid=2, slowHalf=take(2+0)=first 2, fastHalf=drop(2)=last 2
        val activities = listOf(
            run(daysAgo = 1, distanceMeters = 5_000.0, movingMinutes = 20.0, avgHr = null),   // 240 s/km fast
            run(daysAgo = 3, distanceMeters = 5_000.0, movingMinutes = 22.0, avgHr = null),   // 264 s/km
            run(daysAgo = 5, distanceMeters = 5_000.0, movingMinutes = 26.0, avgHr = null),   // 312 s/km
            run(daysAgo = 7, distanceMeters = 5_000.0, movingMinutes = 30.0, avgHr = null),   // 360 s/km slow
        )

        val profile = RecentPaceCalculator.calculate(activities, maxHr, restingHr)

        assertNotNull(profile)
        assertTrue(profile.fallbackUsed)
        assertNull(profile.vo2maxPaceSecPerKm)
        assertNotNull(profile.easyPaceSecPerKm)
        assertNotNull(profile.tempoPaceSecPerKm)
        // easy (slower half) should be slower than tempo (faster half)
        assertTrue(profile.easyPaceSecPerKm!! > profile.tempoPaceSecPerKm!!)
        assertEquals(4, profile.runCount)
    }

    // ─── too few runs ────────────────────────────────────────────────────────

    @Test
    fun returnsNullWhenFewerThanThreeRuns() {
        val activities = listOf(
            run(daysAgo = 1, avgHr = 140),
            run(daysAgo = 3, avgHr = 160)
        )
        assertNull(RecentPaceCalculator.calculate(activities, maxHr, restingHr))
    }

    @Test
    fun returnsNullWhenNoRuns() {
        assertNull(RecentPaceCalculator.calculate(emptyList(), maxHr, restingHr))
    }

    // ─── activity filtering ──────────────────────────────────────────────────

    @Test
    fun nonRunActivitiesAreExcluded() {
        val activities = listOf(
            run(daysAgo = 1, avgHr = 140),
            run(daysAgo = 2, type = "Ride", avgHr = 140),   // should be filtered out
            run(daysAgo = 3, type = "Swim", avgHr = 140),   // should be filtered out
        )
        // Only 1 qualifying run → should be null
        assertNull(RecentPaceCalculator.calculate(activities, maxHr, restingHr))
    }

    @Test
    fun activitiesOutsideWindowAreExcluded() {
        val activities = listOf(
            run(daysAgo = 1, avgHr = 140),
            run(daysAgo = 15, avgHr = 160),   // outside 14-day window
            run(daysAgo = 20, avgHr = 180),   // outside 14-day window
        )
        // Only 1 qualifying run → should be null
        assertNull(RecentPaceCalculator.calculate(activities, maxHr, restingHr))
    }

    @Test
    fun customWindowRespected() {
        val activities = listOf(
            run(daysAgo = 3,  avgHr = 140),
            run(daysAgo = 10, avgHr = 140),
            run(daysAgo = 18, avgHr = 160),  // outside 14d but inside 30d
            run(daysAgo = 25, avgHr = 180),  // outside 14d but inside 30d
        )
        // With windowDays=14: 2 runs → null
        assertNull(RecentPaceCalculator.calculate(activities, maxHr, restingHr, windowDays = 14))
        // With windowDays=30: 4 runs → non-null
        assertNotNull(RecentPaceCalculator.calculate(activities, maxHr, restingHr, windowDays = 30))
    }

    @Test
    fun runsWithZeroDistanceAreExcluded() {
        val activities = listOf(
            run(daysAgo = 1, distanceMeters = 0.0, avgHr = 140),
            run(daysAgo = 2, distanceMeters = 10_000.0, avgHr = 160),
            run(daysAgo = 3, distanceMeters = 10_000.0, avgHr = 140),
        )
        // Only 2 qualifying runs → null
        assertNull(RecentPaceCalculator.calculate(activities, maxHr, restingHr))
    }
}
