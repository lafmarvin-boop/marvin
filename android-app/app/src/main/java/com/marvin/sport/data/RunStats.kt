package com.marvin.sport.data

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object RunStats {

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Distance en mètres entre deux points GPS (formule de Haversine). */
    fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2).let { it * it }
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    /** Durée en mm:ss ou h:mm:ss. */
    fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else "%02d:%02d".format(m, s)
    }

    /** Distance en km avec 2 décimales. */
    fun formatDistance(m: Double): String =
        if (m >= 1000.0) "%.2f km".format(m / 1000.0)
        else "%d m".format(m.toInt())

    /** Allure moyenne en min/km : durée totale / distance. */
    fun formatPace(durationMs: Long, distanceM: Double): String {
        if (distanceM < 5.0) return "—"
        val secPerKm = (durationMs / 1000.0) / (distanceM / 1000.0)
        val m = (secPerKm / 60).toInt()
        val s = (secPerKm % 60).toInt()
        return "%d:%02d /km".format(m, s)
    }

    /** Vitesse moyenne en km/h. */
    fun formatSpeed(durationMs: Long, distanceM: Double): String {
        if (durationMs < 1000) return "—"
        val kmh = (distanceM / 1000.0) / (durationMs / 3_600_000.0)
        return "%.1f km/h".format(kmh)
    }

}
