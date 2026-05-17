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
    val targetM: Double? = null,
)

/**
 * État live d'une course. Soit une course "objectif distance" (targetM), soit
 * une séance du programme running (programBlocks). Les deux mécaniques de bips
 * sont distinctes : pour les courses libres, milestones sur le total ; pour
 * les séances programme, milestones par bloc + bip long à la fin de chaque bloc.
 */
data class LiveRun(
    val startedAt: Long,
    val points: List<RunPoint> = emptyList(),
    val distanceM: Double = 0.0,
    val lastUpdateMs: Long = startedAt,
    val targetM: Double? = null,
    val milestonesReached: Set<Int> = emptySet(),
    // --- Mode programme ---
    val programBlocks: List<RunBlock>? = null,
    val currentBlockIndex: Int = 0,
    val currentBlockStartMs: Long = startedAt,
    val currentBlockStartDistanceM: Double = 0.0,
    val currentBlockMilestonesReached: Set<Int> = emptySet(),
    // --- Qualité GPS (rafraîchi à chaque fix, accepté ou non) ---
    val currentAccuracyM: Float? = null,
    val lastFixAtMs: Long? = null,
)
