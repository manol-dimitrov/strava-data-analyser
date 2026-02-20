package com.endurocoach.metrics

import com.endurocoach.domain.Activity
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoadSeriesServiceTest {

    private val calc = BanisterTrimpCalculator(maxHeartRate = 190, restingHeartRate = 50)
    private val service = LoadSeriesService(calc)

    @Test
    fun emptyActivitiesProduceZeroLoad() {
        val snapshot = service.buildSnapshot(activities = emptyList(), days = 7)
        assertEquals(7, snapshot.series.size)
        assertEquals(0.0, snapshot.ctl)
        assertEquals(0.0, snapshot.atl)
        assertEquals(0.0, snapshot.tsb)
    }

    @Test
    fun seriesLengthMatchesDays() {
        val snapshot = service.buildSnapshot(activities = emptyList(), days = 30)
        assertEquals(30, snapshot.series.size)
    }

    @Test
    fun tsbEqualsCtlMinusAtl() {
        val activities = DemoActivityGenerator.generate(days = 45)
        val snapshot = service.buildSnapshot(activities = activities, days = 45)
        assertTrue(
            abs(snapshot.tsb - (snapshot.ctl - snapshot.atl)) < 0.0001,
            "TSB (${snapshot.tsb}) must equal CTL (${snapshot.ctl}) - ATL (${snapshot.atl})"
        )
    }

    @Test
    fun everySeriesPointHasTsbEqualsCtlMinusAtl() {
        val activities = DemoActivityGenerator.generate(days = 20)
        val snapshot = service.buildSnapshot(activities = activities, days = 20)
        snapshot.series.forEach { point ->
            assertTrue(
                abs(point.tsb - (point.ctl - point.atl)) < 0.0001,
                "TSB must equal CTL-ATL on ${point.date}"
            )
        }
    }

    @Test
    fun singleActivityProducesPositiveLoad() {
        val date = LocalDate.of(2025, 6, 15)
        val activities = listOf(Activity(date = date, durationMinutes = 60.0, avgHeartRate = 155))
        val snapshot = service.buildSnapshot(activities = activities, days = 1, endDate = date)
        assertTrue(snapshot.ctl > 0, "CTL should be positive after one activity")
        assertTrue(snapshot.atl > 0, "ATL should be positive after one activity")
    }

    @Test
    fun atlRespondsMoreQuicklyThanCtl() {
        // With a burst of high load after a quiet seed period, ATL should jump above CTL
        val endDate = LocalDate.of(2025, 6, 20)
        val activities = listOf(
            Activity(date = endDate, durationMinutes = 120.0, avgHeartRate = 175)
        )
        val snapshot = service.buildSnapshot(activities = activities, days = 7, endDate = endDate)
        // On seed-only days (no activity), CTL/ATL both decay toward 0.
        // The single big day spikes ATL more than CTL.
        assertTrue(snapshot.atl > snapshot.ctl, "ATL should respond faster than CTL to a single large load")
    }

    @Test
    fun demoDataProducesDeterministicOutput() {
        val date = LocalDate.of(2026, 1, 15)
        val activities1 = DemoActivityGenerator.generate(days = 10, endDate = date)
        val activities2 = DemoActivityGenerator.generate(days = 10, endDate = date)
        val s1 = service.buildSnapshot(activities1, days = 10, endDate = date)
        val s2 = service.buildSnapshot(activities2, days = 10, endDate = date)
        assertEquals(s1.ctl, s2.ctl, "Demo data should produce deterministic CTL")
        assertEquals(s1.atl, s2.atl, "Demo data should produce deterministic ATL")
        assertEquals(s1.tsb, s2.tsb, "Demo data should produce deterministic TSB")
    }

    @Test
    fun recentVolumeOnlyCoversSeven() {
        val endDate = LocalDate.of(2025, 6, 20)
        val activities = (0..13).map { offset ->
            Activity(
                date = endDate.minusDays(offset.toLong()),
                durationMinutes = 30.0,
                avgHeartRate = 140
            )
        }
        val snapshot = service.buildSnapshot(activities = activities, days = 14, endDate = endDate)
        assertTrue(
            abs(snapshot.recentVolumeMinutes - 210.0) < 0.001,
            "Recent volume should be 7 * 30 = 210, got ${snapshot.recentVolumeMinutes}"
        )
    }

    @Test
    fun acwrIsComputedCorrectly() {
        val activities = DemoActivityGenerator.generate(days = 45)
        val snapshot = service.buildSnapshot(activities = activities, days = 45)
        val expectedAcwr = if (snapshot.ctl > 1.0) snapshot.atl / snapshot.ctl else 1.0
        assertTrue(
            abs(snapshot.acwr - expectedAcwr) < 0.001,
            "ACWR should be ATL/CTL, expected $expectedAcwr, got ${snapshot.acwr}"
        )
    }

    @Test
    fun monotonyIsNonNegative() {
        val activities = DemoActivityGenerator.generate(days = 14)
        val snapshot = service.buildSnapshot(activities = activities, days = 14)
        assertTrue(snapshot.monotony >= 0.0, "Monotony should be non-negative")
    }

    @Test
    fun seedingPreventsUnrealisticTsb() {
        // With consistent training over 45 days, TSB should not be deeply negative
        // The old algorithm (zero-seeding) produced TSB around -80 to -100 for consistent training.
        // The new algorithm should keep TSB in a reasonable range.
        val activities = DemoActivityGenerator.generate(days = 45)
        val snapshot = service.buildSnapshot(activities = activities, days = 45)
        assertTrue(
            snapshot.tsb > -30,
            "TSB should not be deeply negative for consistent training, got ${snapshot.tsb}"
        )
    }
}
