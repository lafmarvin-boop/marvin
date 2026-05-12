package com.marvin.sport.data

import kotlinx.serialization.Serializable

@Serializable
data class RunPoint(
    val lat: Double,
    val lng: Double,
    val altitude: Double = 0.0,
    val timestampMs: Long,
)

@Serializable
data class Run(
    val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
    val distanceM: Double,
    val points: List<RunPoint>,
    val note: String = "",
)

/** État live d'une course en cours. */
data class LiveRun(
    val startedAt: Long,
    val points: List<RunPoint> = emptyList(),
    val distanceM: Double = 0.0,
    val lastUpdateMs: Long = startedAt,
)
