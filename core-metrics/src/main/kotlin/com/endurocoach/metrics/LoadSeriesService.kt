package com.endurocoach.metrics

import com.endurocoach.domain.Activity
import com.endurocoach.domain.LoadPoint
import com.endurocoach.domain.LoadSnapshot
import java.time.LocalDate
import kotlin.math.exp

data class LoadSettings(
    val ctlTimeConstantDays: Double = 42.0,
    val atlTimeConstantDays: Double = 7.0
)

class LoadSeriesService(
    private val trimpCalculator: BanisterTrimpCalculator,
    private val settings: LoadSettings = LoadSettings()
) {
    fun buildSnapshot(
        activities: List<Activity>,
        days: Int = 45,
        endDate: LocalDate = LocalDate.now()
    ): LoadSnapshot {
        val safeDays = days.coerceAtLeast(1)
        val startDate = endDate.minusDays((safeDays - 1).toLong())

        val trimpByDate = activities
            .filter { !it.date.isBefore(startDate) && !it.date.isAfter(endDate) }
            .groupBy { it.date }
            .mapValues { (_, dayActivities) ->
                dayActivities.sumOf { trimpCalculator.calculate(it.durationMinutes, it.avgHeartRate) }
            }

        val alphaCtl = 1.0 - exp(-1.0 / settings.ctlTimeConstantDays)
        val alphaAtl = 1.0 - exp(-1.0 / settings.atlTimeConstantDays)

        var ctl = 0.0
        var atl = 0.0
        val series = mutableListOf<LoadPoint>()

        for (offset in 0 until safeDays) {
            val date = startDate.plusDays(offset.toLong())
            val trimp = trimpByDate[date] ?: 0.0

            ctl += alphaCtl * (trimp - ctl)
            atl += alphaAtl * (trimp - atl)

            series += LoadPoint(
                date = date,
                trimp = trimp,
                ctl = ctl,
                atl = atl,
                tsb = ctl - atl
            )
        }

        val latest = series.last()
        val recentVolumeMinutes = activities
            .filter { !it.date.isBefore(endDate.minusDays(6)) && !it.date.isAfter(endDate) }
            .sumOf { it.durationMinutes }

        return LoadSnapshot(
            date = latest.date,
            ctl = latest.ctl,
            atl = latest.atl,
            tsb = latest.tsb,
            recentVolumeMinutes = recentVolumeMinutes,
            series = series
        )
    }
}
