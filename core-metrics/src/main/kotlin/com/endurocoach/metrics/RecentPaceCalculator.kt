package com.endurocoach.metrics

import com.endurocoach.domain.Activity
import com.endurocoach.domain.RecentPaceProfile
import java.time.LocalDate

/**
 * Derives running pace zones from recent Strava activities using the Seiler/Esteve-Lanao thresholds:
 *   LT1 (aerobic threshold)  = restingHr + 0.75 × HRR
 *   LT2 (lactate threshold)  = restingHr + 0.87 × HRR
 *
 * Runs are filtered to the last [windowDays] days and bucketed by average HR into:
 *   easy   < LT1
 *   tempo  [LT1, LT2)
 *   VO2max >= LT2
 *
 * If fewer than 50% of qualifying runs have HR data, falls back to a pace-median split
 * (easy = slower half, tempo = faster half, VO2max = null) and sets [RecentPaceProfile.fallbackUsed].
 *
 * Returns null when fewer than 3 qualifying runs exist in the window.
 */
object RecentPaceCalculator {

    fun calculate(
        activities: List<Activity>,
        maxHr: Int,
        restingHr: Int,
        windowDays: Int = 14
    ): RecentPaceProfile? {
        val cutoff = LocalDate.now().minusDays(windowDays.toLong())

        val runs = activities.filter { a ->
            a.type?.contains("Run", ignoreCase = true) == true &&
                (a.distanceMeters ?: 0.0) > 0.0 &&
                a.movingMinutes > 0.0 &&
                a.date >= cutoff
        }

        if (runs.size < 3) return null

        // sec/km based on moving time (more accurate than elapsed for hilly/interrupted runs)
        fun Activity.paceSecPerKm(): Double =
            (movingMinutes * 60.0) / (distanceMeters!! / 1000.0)

        val hrRange = (maxHr - restingHr).toDouble()
        val lt1Hr = restingHr + 0.75 * hrRange  // aerobic threshold
        val lt2Hr = restingHr + 0.87 * hrRange  // lactate threshold

        val withHrCount = runs.count { it.avgHeartRate != null }
        val useHrBucketing = withHrCount * 2 >= runs.size  // at least 50% must have HR data

        return if (useHrBucketing) {
            val easyRuns   = runs.filter { a -> val hr = a.avgHeartRate; hr != null && hr < lt1Hr }
            val tempoRuns  = runs.filter { a -> val hr = a.avgHeartRate; hr != null && hr >= lt1Hr && hr < lt2Hr }
            val vo2maxRuns = runs.filter { a -> val hr = a.avgHeartRate; hr != null && hr >= lt2Hr }

            RecentPaceProfile(
                easyPaceSecPerKm   = easyRuns.averagePace { it.paceSecPerKm() },
                tempoPaceSecPerKm  = tempoRuns.averagePace { it.paceSecPerKm() },
                vo2maxPaceSecPerKm = vo2maxRuns.averagePace { it.paceSecPerKm() },
                runCount           = runs.size,
                fallbackUsed       = false
            )
        } else {
            // No reliable HR data — use pace-median split into easy (slower) and tempo (faster)
            val sortedByPace = runs.sortedByDescending { it.paceSecPerKm() }  // slowest first
            val mid = sortedByPace.size / 2
            val slowHalf = sortedByPace.take(mid + sortedByPace.size % 2)     // easy
            val fastHalf = sortedByPace.drop(mid + sortedByPace.size % 2)     // tempo

            RecentPaceProfile(
                easyPaceSecPerKm   = slowHalf.averagePace { it.paceSecPerKm() },
                tempoPaceSecPerKm  = fastHalf.averagePace { it.paceSecPerKm() },
                vo2maxPaceSecPerKm = null,
                runCount           = runs.size,
                fallbackUsed       = true
            )
        }
    }

    // Returns null when the list is empty (zone had no qualifying runs)
    private fun List<Activity>.averagePace(pace: (Activity) -> Double): Int? =
        takeIf { isNotEmpty() }?.map { pace(it) }?.average()?.toInt()
}
