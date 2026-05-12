package com.marvin.sport.data

data class Exercise(
    val name: String,
    val sets: String,
    val reps: String,
    val baseLoadKg: Double? = null,
    val rest: String = "",
    val isSuperset: Boolean = false,
    val supersetGroup: Int = 0,
    val annotation: String = "",
)

data class Session(
    val id: String,
    val phaseIndex: Int,
    val weekIndex: Int,
    val sessionIndex: Int,
    val title: String,
    val warmup: String,
    val exercises: List<Exercise>,
)

data class Week(
    val index: Int,
    val label: String,
    val sessions: List<Session>,
)

data class Phase(
    val index: Int,
    val title: String,
    val description: String,
    val weeks: List<Week>,
)

data class TrainingProgram(
    val phases: List<Phase>,
)
