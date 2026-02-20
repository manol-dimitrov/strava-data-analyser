package com.endurocoach.metrics

import com.endurocoach.domain.Activity
import com.endurocoach.domain.LoadPoint
import com.endurocoach.domain.LoadSnapshot
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.sqrt

data class LoadSettings(
    val ctlTimeConstantDays: Double = 42.0,
    val atlTimeConstantDays: Double = 7.0,
    /** Number of days used to seed CTL/ATL from the initial training data. */
    val seedDays: Int = 7
)

/**
 * Computes the full training-load model:
 * - CTL / ATL / TSB (Banister impulse-response, EMA variant)
 * - ACWR (Gabbett 2016 — acute:chronic workload ratio)
 * - Training Monotony (Foster 1998 — mean/SD of 7-day loads)
 * - CTL Ramp Rate (week-over-week fitness change)
 *
 * CTL and ATL are seeded from the average daily TRIMP of the first [LoadSettings.seedDays]
 * to avoid the classic cold-start problem where CTL lags behind ATL and TSB is
 * unrealistically negative.
 */
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

        // Seed CTL/ATL with the average daily TRIMP from the first seedDays.
        val seedWindow = minOf(settings.seedDays, safeDays)
        val seedAvg = if (seedWindow > 0) {
            (0 until seedWindow).sumOf { offset ->
                trimpByDate[startDate.plusDays(offset.toLong())] ?: 0.0
            } / seedWindow
        } else 0.0

        var ctl = seedAvg
        var atl = seedAvg
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

        // Recent 7-day volume
        val recentVolumeMinutes = activities
            .filter { !it.date.isBefore(endDate.minusDays(6)) && !it.date.isAfter(endDate) }
            .sumOf { it.durationMinutes }

        // ACWR — Gabbett (2016): ATL / CTL (uncoupled)
        val acwr = if (latest.ctl > 1.0) latest.atl / latest.ctl else 1.0

        // Training Monotony — Foster (1998): mean / SD of last 7 daily loads
        val last7Trimps = series.takeLast(minOf(7, series.size)).map { it.trimp }
        val monotony = computeMonotony(last7Trimps)

        // CTL ramp rate — change per week (last CTL minus CTL 7 days ago)
        val ctlRampRate = if (series.size >= 8) {
            latest.ctl - series[series.size - 8].ctl
        } else if (series.size >= 2) {
            latest.ctl - series.first().ctl
        } else 0.0

        // 10-day spike ratio — 10-day rolling sum vs 10 × full-window average daily TRIMP.
        // Flags short-term load surges even when the 42/7-day ACWR looks safe.
        val last10Trimps = series.takeLast(minOf(10, series.size)).map { it.trimp }
        val windowAvgTrimp = if (series.isNotEmpty()) series.sumOf { it.trimp } / series.size else 0.0
        val spike10 = if (windowAvgTrimp > 0.1) {
            last10Trimps.sum() / (last10Trimps.size.toDouble() * windowAvgTrimp)
        } else 1.0

        // 10-day Foster strain — mean(last10 TRIMP) × monotony(last10).
        val strain10 = if (last10Trimps.size >= 2) {
            val mean10 = last10Trimps.average()
            if (mean10 < 0.001) 0.0
            else {
                val variance10 = last10Trimps.sumOf { (it - mean10) * (it - mean10) } / last10Trimps.size
                val sd10 = sqrt(variance10)
                val mono10 = if (sd10 > 0.001) mean10 / sd10 else 0.0
                mean10 * mono10
            }
        } else 0.0

        return LoadSnapshot(
            date = latest.date,
            ctl = latest.ctl,
            atl = latest.atl,
            tsb = latest.tsb,
            recentVolumeMinutes = recentVolumeMinutes,
            acwr = acwr,
            monotony = monotony,
            ctlRampRate = ctlRampRate,
            series = series,
            spike10 = spike10,
            strain10 = strain10
        )
    }

    private fun computeMonotony(dailyLoads: List<Double>): Double {
        if (dailyLoads.size < 2) return 0.0
        
        val mean = dailyLoads.average()
        
        if (mean < 0.001) return 0.0
        
        val variance = dailyLoads.sumOf { (it - mean) * (it - mean) } / dailyLoads.size
        val sd = sqrt(variance)
        
        return if (sd > 0.001) mean / sd else 0.0
    }
}
