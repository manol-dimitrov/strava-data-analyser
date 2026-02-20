package com.endurocoach.metrics

import com.endurocoach.domain.Activity
import java.time.DayOfWeek
import java.time.LocalDate

object DemoActivityGenerator {
    fun generate(days: Int = 45, endDate: LocalDate = LocalDate.now()): List<Activity> {
        val safeDays = days.coerceAtLeast(1)
        val startDate = endDate.minusDays((safeDays - 1).toLong())
        val result = mutableListOf<Activity>()

        // Days 22–28 (counting from startDate) simulate a “peak build week” with elevated
        // volume to ensure the spike10 and strain10 signals are exercised in demo mode.
        val peakSpikeStart = 22
        val peakSpikeEnd = 28

        for (offset in 0 until safeDays) {
            val date = startDate.plusDays(offset.toLong())
            val dayTypeIndex = offset % 7
            val inPeakWeek = offset in peakSpikeStart..peakSpikeEnd

            val baseDuration = when (date.dayOfWeek) {
                DayOfWeek.MONDAY -> 45.0
                DayOfWeek.TUESDAY -> 65.0
                DayOfWeek.WEDNESDAY -> 50.0
                DayOfWeek.THURSDAY -> 70.0
                DayOfWeek.FRIDAY -> 40.0
                DayOfWeek.SATURDAY -> 90.0
                DayOfWeek.SUNDAY -> 0.0
            }

            // Peak week: 40% volume boost and no rest days to drive a measurable spike
            val duration = if (inPeakWeek && baseDuration <= 0.0) 45.0
            else if (inPeakWeek) baseDuration * 1.4
            else baseDuration

            if (duration <= 0.0) continue

            // Peak week: slightly elevated HR to compound the strain signal
            val heartRate = 138 + (dayTypeIndex * 3) + ((offset / 7) % 4) +
                if (inPeakWeek) 6 else 0

            result += Activity(
                date = date,
                durationMinutes = duration,
                avgHeartRate = heartRate
            )
        }

        return result
    }
}
