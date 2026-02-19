package com.endurocoach.metrics

import com.endurocoach.domain.Activity
import java.time.DayOfWeek
import java.time.LocalDate

object DemoActivityGenerator {
    fun generate(days: Int = 45, endDate: LocalDate = LocalDate.now()): List<Activity> {
        val safeDays = days.coerceAtLeast(1)
        val startDate = endDate.minusDays((safeDays - 1).toLong())
        val result = mutableListOf<Activity>()

        for (offset in 0 until safeDays) {
            val date = startDate.plusDays(offset.toLong())
            val dayTypeIndex = offset % 7

            val duration = when (date.dayOfWeek) {
                DayOfWeek.MONDAY -> 45.0
                DayOfWeek.TUESDAY -> 65.0
                DayOfWeek.WEDNESDAY -> 50.0
                DayOfWeek.THURSDAY -> 70.0
                DayOfWeek.FRIDAY -> 40.0
                DayOfWeek.SATURDAY -> 90.0
                DayOfWeek.SUNDAY -> 0.0
            }

            if (duration <= 0.0) {
                continue
            }

            val heartRate = 138 + (dayTypeIndex * 3) + ((offset / 7) % 4)

            result += Activity(
                date = date,
                durationMinutes = duration,
                avgHeartRate = heartRate
            )
        }

        return result
    }
}
