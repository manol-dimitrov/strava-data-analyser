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

        // Demo activity names by day-of-week pattern
        val dayNames = arrayOf(
            "Recovery Jog", "Tempo Intervals", "Easy Run",
            "Threshold Session", "Shake-out Run", "Long Run", "Rest Day"
        )

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

            // Derive realistic pace ~5:00-6:30 min/km → distance from duration
            val paceMinPerKm = 5.0 + (dayTypeIndex * 0.2) - (if (inPeakWeek) 0.2 else 0.0)
            val distanceKm = duration / paceMinPerKm
            val distanceMeters = distanceKm * 1000.0
            // Moving time = elapsed time minus ~8% for rest periods
            val movingMinutes = duration * 0.92

            result += Activity(
                date = date,
                durationMinutes = duration,
                avgHeartRate = heartRate,
                name = if (inPeakWeek) "Peak Week: ${dayNames[dayTypeIndex]}" else dayNames[dayTypeIndex],
                type = "Run",
                distanceMeters = distanceMeters,
                maxHeartRate = heartRate + 18 + (dayTypeIndex % 3) * 4,
                elevationGain = 30.0 + (distanceKm * 5.0) + (if (date.dayOfWeek == DayOfWeek.SATURDAY) 60.0 else 0.0),
                movingMinutes = movingMinutes
            )
        }

        return result
    }
}
