package com.endurocoach.domain

import java.time.LocalDate

data class Activity(
    val date: LocalDate,
    val durationMinutes: Double,
    val avgHeartRate: Int?
)

data class DailyCheckIn(
    val legFeeling: Int,
    val mentalReadiness: Int,
    val timeAvailableMinutes: Int,
    val coachingPhilosophy: String
)

data class WorkoutRequest(
    val checkIn: DailyCheckIn,
    val currentTsb: Double,
    val currentCtl: Double,
    val currentAtl: Double,
    val recentVolumeMinutes: Double,
    val acwr: Double,
    val monotony: Double,
    val ctlRampRate: Double,
    val spike10: Double = 1.0,
    val strain10: Double = 0.0
)

data class WorkoutPlan(
    val warmup: String,
    val mainSet: String,
    val cooldown: String,
    val coachReasoning: String
)

data class LoadPoint(
    val date: LocalDate,
    val trimp: Double,
    val ctl: Double,
    val atl: Double,
    val tsb: Double
)

data class LoadSnapshot(
    val date: LocalDate,
    val ctl: Double,
    val atl: Double,
    val tsb: Double,
    val recentVolumeMinutes: Double,
    val acwr: Double,
    val monotony: Double,
    val ctlRampRate: Double,
    val series: List<LoadPoint>,
    /** Ratio of 10-day rolling total TRIMP to 10 × full-window average daily TRIMP.
     *  Values > 1.3 indicate a recent load spike worth monitoring. */
    val spike10: Double = 1.0,
    /** Foster strain over the last 10 days: mean(last10 TRIMP) × monotony(last10).
     *  Captures accumulated fatigue from both volume and uniformity. */
    val strain10: Double = 0.0
)
