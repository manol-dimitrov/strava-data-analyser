package com.endurocoach.domain

import java.time.LocalDate
import kotlinx.serialization.Serializable

data class Activity(
    val date: LocalDate,
    val durationMinutes: Double,
    val avgHeartRate: Int?,
    val name: String? = null,
    val type: String? = null,
    val distanceMeters: Double? = null,
    val maxHeartRate: Int? = null,
    val elevationGain: Double? = null,
    val movingMinutes: Double = durationMinutes  // Moving time; defaults to elapsed time
)

/**
 * Pace zones derived from recent Strava runs.
 * LT1 = 75% HRR (aerobic threshold), LT2 = 87% HRR (lactate threshold).
 * [fallbackUsed] is true when HR data was insufficient and a pace-median split was used instead.
 */
data class RecentPaceProfile(
    val easyPaceSecPerKm: Int?,        // avg pace of runs with avgHR < LT1
    val tempoPaceSecPerKm: Int?,       // avg pace of runs with avgHR in [LT1, LT2)
    val vo2maxPaceSecPerKm: Int?,      // avg pace of runs with avgHR >= LT2 (null when HR fallback used)
    val runCount: Int,
    val fallbackUsed: Boolean          // true = no HR data; pace-median split into easy/tempo only
)

data class DailyCheckIn(
    val legFeeling: Int,
    val mentalReadiness: Int,
    val timeAvailableMinutes: Int,
    val coachingPhilosophy: String,
    val raceDistance: String = "10km"  // "5km", "10km", "half_marathon", "marathon"
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
    val strain10: Double = 0.0,
    val recentPaceProfile: RecentPaceProfile? = null,
    val daysSinceLastHardSession: Int? = null,
    val recentSessions: List<RecentSessionSummary> = emptyList()
)

data class WorkoutPlan(
    val session: String,
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
    val strain10: Double = 0.0,
    val daysSinceLastHardSession: Int? = null,
    val recentSessions: List<RecentSessionSummary> = emptyList()
)

data class RecentSessionSummary(
    val date: LocalDate,
    val name: String,
    val durationMinutes: Double,
    val trimp: Double,
    val intensity: String
)

/**
 * A single message in a coaching conversation thread.
 * role is "user" (athlete) or "model" (coach/LLM), following Gemini's convention.
 */
data class ConversationMessage(
    val role: String,
    val content: String,
    val timestamp: String
)

/**
 * Request payload for a follow-up coaching chat message.
 * The system instruction contains the full coaching context; history is the
 * (already trimmed) prior messages; userMessage is the new athlete input.
 */
data class WorkoutChatRequest(
    val systemInstruction: String,
    val history: List<ConversationMessage>,
    val userMessage: String
)

/**
 * Persistent athlete profile captured during the onboarding wizard.
 * Keyed by Strava athlete ID so it survives cookie clears and server restarts.
 */
@Serializable
data class AthleteProfile(
    val stravaAthleteId: Long,
    val sportFocus: String,
    val maxHr: Int,
    val restingHr: Int,
    val targetEventName: String,
    val targetEventDate: String,
    val completedAt: String
)
