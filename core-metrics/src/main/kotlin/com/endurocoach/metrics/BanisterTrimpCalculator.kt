package com.endurocoach.metrics

import kotlin.math.exp

class BanisterTrimpCalculator(
    private val maxHeartRate: Int = 190,
    private val restingHeartRate: Int = 50
) {
    fun calculate(durationMinutes: Double, avgHeartRate: Int?): Double {
        if (durationMinutes <= 0.0 || avgHeartRate == null) {
            return 0.0
        }

        val reserve = (maxHeartRate - restingHeartRate).coerceAtLeast(1)
        val hrRatio = ((avgHeartRate - restingHeartRate).toDouble() / reserve)
            .coerceIn(0.0, 1.0)

        return durationMinutes * hrRatio * 0.64 * exp(1.92 * hrRatio)
    }
}
