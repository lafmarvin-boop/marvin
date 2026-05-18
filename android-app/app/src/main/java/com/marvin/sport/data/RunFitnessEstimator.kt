package com.marvin.sport.data

/**
 * Estimation du "niveau" de l'utilisateur à partir de ses courses récentes
 * (les 5 dernières). Sert à scaler automatiquement les distances et durées
 * du programme running.
 *
 *   factor = clamp(avgRecentKm / baselineKm, 0.7, 1.5)
 *
 * Avec baseline 5 km. Si l'utilisateur tourne à 7 km de moyenne → factor 1.4
 * → une séance "5 km Z2" du programme devient "7 km". On plafonne à 10 km par
 * séance (max du programme) et à 0.7 × en bas pour ne pas démotiver.
 */
data class FitnessProfile(
    val recentAvgKm: Double?,
    val recentAvgPaceMinPerKm: Double?,
    val factor: Double,
) {
    fun adaptedKm(baseKm: Double): Double = (baseKm * factor).coerceAtMost(10.0)
    fun adaptedDurationMin(baseMin: Int): Int =
        if (recentAvgPaceMinPerKm == null) baseMin
        else (baseMin * factor).toInt().coerceAtLeast(1)

    val isAdapted: Boolean get() = recentAvgKm != null && kotlin.math.abs(factor - 1.0) >= 0.05
}

object RunFitnessEstimator {

    private const val BASELINE_KM = 5.0
    private const val MIN_DISTANCE_FOR_SAMPLE_M = 800.0

    fun estimate(runs: List<Run>): FitnessProfile {
        val recent = runs
            .filter { it.distanceM >= MIN_DISTANCE_FOR_SAMPLE_M }
            .sortedByDescending { it.startedAt }
            .take(5)
        if (recent.isEmpty()) return FitnessProfile(null, null, 1.0)

        val avgKm = recent.sumOf { it.distanceM } / recent.size / 1000.0
        val avgPace = recent
            .map { it.durationMs / 1000.0 / 60.0 / (it.distanceM / 1000.0) }
            .average()
            .takeIf { !it.isNaN() }

        val factor = (avgKm / BASELINE_KM).coerceIn(0.7, 1.5)
        return FitnessProfile(avgKm, avgPace, factor)
    }
}
