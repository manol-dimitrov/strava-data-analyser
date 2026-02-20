package com.endurocoach.metrics

/**
 * Zone-weighted heart-rate TRIMP inspired by Lucia et al. (2003) and Seiler (2010).
 *
 * Rather than the original Banister exponential formula (which heavily inflates
 * moderate-intensity work and drives unrealistically negative TSB), this uses
 * continuous zone-weighting based on %HRR thresholds aligned with the
 * three-zone model:
 *
 * - Zone 1 (< 68% HRR): base aerobic — weight 1.0
 * - Zone 2 (68-82% HRR): tempo / steady-state — weight 2.0
 * - Zone 3 (82-90% HRR): threshold / VO2max — weight 3.0
 * - Zone 4 (> 90% HRR): anaerobic / sprint — weight 4.0
 *
 * TRIMP = duration × HR-reserve-ratio × zone-weight
 *
 * This produces training-load numbers that align better with modern polarised
 * training research and don't catastrophise about regular endurance training.
 */
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

        val zoneWeight = when {
            hrRatio < 0.68 -> 1.0
            hrRatio < 0.82 -> 2.0
            hrRatio < 0.90 -> 3.0
            else -> 4.0
        }

        return durationMinutes * hrRatio * zoneWeight
    }
}
