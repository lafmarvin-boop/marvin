package com.marvin.sport.data

/**
 * Programme "Grappling" — Lutte / BJJ / MMA sol.
 * 4 phases × 4 semaines × 3 séances par semaine. Travail explosif basé sur :
 *   - Force-grip, force-endurance, puissance posture, pic compétition
 *   - charges relatives aux 1RM du programme musculation
 *
 * Sessions :
 *   S1 = Tirage explosif & grip
 *   S2 = Chaîne postérieure & tronc isométrique
 *   S3 = Lutte-spécifique (clean, sprawl, bear crawl, takedown)
 */
object GrapplingProgramBuilder {

    private const val PROGRAM_ID = "grappling"

    private const val DEAD_1RM = 140.0
    private const val ROW_1RM = 60.0
    private const val PULL_HIGH_1RM = 70.0
    private const val PULL_LOW_1RM = 60.0
    private const val SQUAT_1RM = 100.0
    private const val BENCH_1RM = 70.0

    private const val REST_HEAVY = "2-3 min repos"
    private const val REST_EXPLO = "90 s repos (qualité du mouvement)"
    private const val REST_ISO = "60 s repos · respiration nasale"
    private const val REST_CIRCUIT = "30 s entre exos · 60 s entre tours"

    private const val WARMUP_GRIP = "Mobilité poignets/épaules / pendulaire / dead-hang 2x20 s / 20 fermières"
    private const val WARMUP_POST = "Hip airplane 2x6 / cat-cow 2x10 / bird-dog 2x10 / pont fessier 2x12"
    private const val WARMUP_WREST = "Tour de tapis / shrimps / sprawls 3x10 / shadow grappling 3 min"

    private data class WeekSpec(
        val sets: Int,
        val mainReps: Int,
        val isoSeconds: Int,
        val gripSeconds: Int,
        val condRounds: Int,
    )

    private fun phaseScheme(phase: Int): List<WeekSpec> = when (phase) {
        // Phase 1 — Force / grip de base
        0 -> listOf(
            WeekSpec(3, 6, 30, 30, 4),
            WeekSpec(4, 6, 35, 35, 4),
            WeekSpec(4, 5, 40, 40, 5),
            WeekSpec(3, 5, 25, 25, 3),
        )
        // Phase 2 — Force-endurance : isométrie longue, charges modérées
        1 -> listOf(
            WeekSpec(4, 6, 45, 45, 5),
            WeekSpec(5, 5, 50, 50, 6),
            WeekSpec(5, 5, 60, 55, 6),
            WeekSpec(3, 4, 30, 30, 4),
        )
        // Phase 3 — Puissance traction/poussée combinée
        2 -> listOf(
            WeekSpec(5, 4, 40, 40, 6),
            WeekSpec(5, 3, 45, 45, 7),
            WeekSpec(6, 3, 50, 50, 8),
            WeekSpec(3, 3, 25, 25, 4),
        )
        // Phase 4 — Pic compétition
        else -> listOf(
            WeekSpec(4, 3, 35, 30, 6),
            WeekSpec(4, 3, 40, 30, 7),
            WeekSpec(3, 2, 30, 25, 6),
            WeekSpec(2, 2, 20, 20, 4),
        )
    }

    private data class Loads(
        val deadPct: Double,
        val rowPct: Double,
        val pullHighPct: Double,
        val pullLowPct: Double,
        val squatPct: Double,
        val benchPct: Double,
        val kbKg: Double,
    )

    private fun phaseLoads(phase: Int): Loads = when (phase) {
        0 -> Loads(0.70, 0.70, 0.75, 0.75, 0.70, 0.65, 16.0)
        1 -> Loads(0.75, 0.75, 0.80, 0.80, 0.75, 0.70, 20.0)
        2 -> Loads(0.65, 0.60, 0.65, 0.65, 0.60, 0.55, 24.0)
        else -> Loads(0.85, 0.80, 0.85, 0.80, 0.80, 0.75, 28.0)
    }

    private fun s1(p: Int, w: Int, spec: WeekSpec, loads: Loads) = Session(
        id = "${PROGRAM_ID}_P${p + 1}W${w + 1}S1",
        programId = PROGRAM_ID,
        phaseIndex = p, weekIndex = w, sessionIndex = 0,
        title = "Séance 1 — Tirage explosif & grip",
        warmup = WARMUP_GRIP,
        exercises = listOf(
            Exercise("Power clean", "${spec.sets}", "${spec.mainReps} reps", DEAD_1RM * 0.55, REST_HEAVY, annotation = "Triple extension, vitesse barre"),
            Exercise("Traction lestée explosive", "${spec.sets}", "${spec.mainReps} reps", 10.0, REST_EXPLO, isSuperset = true, supersetGroup = 1, annotation = "Concentrique rapide"),
            Exercise("Barbell row", "${spec.sets}", "${spec.mainReps + 2} reps", ROW_1RM * loads.rowPct, REST_EXPLO, isSuperset = true, supersetGroup = 1),
            Exercise("Dead-hang lesté", "3", "${spec.gripSeconds} s", 10.0, REST_ISO, annotation = "Grip isométrique"),
            Exercise("Fermières (gi-grip simulé)", "3", "${spec.gripSeconds} s par main", rest = REST_ISO, isSuperset = true, supersetGroup = 2),
            Exercise("Curl marteau", "3", "10 reps", 12.0, REST_CIRCUIT, isSuperset = true, supersetGroup = 2),
        ),
    )

    private fun s2(p: Int, w: Int, spec: WeekSpec, loads: Loads) = Session(
        id = "${PROGRAM_ID}_P${p + 1}W${w + 1}S2",
        programId = PROGRAM_ID,
        phaseIndex = p, weekIndex = w, sessionIndex = 1,
        title = "Séance 2 — Chaîne postérieure & tronc isométrique",
        warmup = WARMUP_POST,
        exercises = listOf(
            Exercise("Deadlift vitesse", "${spec.sets}", "${spec.mainReps} reps", DEAD_1RM * loads.deadPct, REST_HEAVY, annotation = "Tirage explosif"),
            Exercise("KB swing russe", "${spec.sets}", "12 reps", loads.kbKg, REST_EXPLO, isSuperset = true, supersetGroup = 1),
            Exercise("Glute-ham raise / Nordic curl", "${spec.sets}", "${spec.mainReps + 2} reps", rest = REST_EXPLO, isSuperset = true, supersetGroup = 1),
            Exercise("Planche bras tendus", "3", "${spec.isoSeconds} s", rest = REST_ISO),
            Exercise("Hollow body hold", "3", "${spec.isoSeconds} s", rest = REST_ISO),
            Exercise("Wall sit lesté", "3", "${spec.isoSeconds} s", 15.0, REST_ISO, annotation = "Sac/plaque sur cuisses"),
        ),
    )

    private fun s3(p: Int, w: Int, spec: WeekSpec, loads: Loads) = Session(
        id = "${PROGRAM_ID}_P${p + 1}W${w + 1}S3",
        programId = PROGRAM_ID,
        phaseIndex = p, weekIndex = w, sessionIndex = 2,
        title = "Séance 3 — Wrestling-spécifique",
        warmup = WARMUP_WREST,
        exercises = listOf(
            Exercise("Front squat puissance", "${spec.sets}", "${spec.mainReps} reps", SQUAT_1RM * loads.squatPct, REST_HEAVY, annotation = "Posture verticale"),
            Exercise("Push press", "${spec.sets}", "${spec.mainReps} reps", BENCH_1RM * loads.benchPct, REST_EXPLO),
            Exercise("Tirage poulie haute explosif", "${spec.sets}", "${spec.mainReps + 2} reps", PULL_HIGH_1RM * loads.pullHighPct, REST_EXPLO, isSuperset = true, supersetGroup = 1),
            Exercise("Tirage poulie basse explosif", "${spec.sets}", "${spec.mainReps + 2} reps", PULL_LOW_1RM * loads.pullLowPct, REST_EXPLO, isSuperset = true, supersetGroup = 1),
            Exercise("Bear crawl + sprawl", "${spec.condRounds}", "30 s ON / 30 s OFF", rest = "—", annotation = "Conditionnement spécifique sol"),
            Exercise("Shadow grappling intense", "${spec.condRounds}", "1 min", rest = "30 s repos"),
        ),
    )

    private fun phase(index: Int, title: String, description: String): Phase {
        val scheme = phaseScheme(index)
        val loads = phaseLoads(index)
        val weeks = (0..3).map { w ->
            Week(
                index = w,
                label = "Semaine ${w + 1}",
                sessions = listOf(
                    s1(index, w, scheme[w], loads),
                    s2(index, w, scheme[w], loads),
                    s3(index, w, scheme[w], loads),
                ),
            )
        }
        return Phase(index = index, title = title, description = description, weeks = weeks)
    }

    fun build() = TrainingProgram(
        id = PROGRAM_ID,
        name = "Grappling — Lutte / BJJ / MMA sol",
        shortName = "Grappling",
        description = "16 semaines en 4 phases (grip → force-endurance → puissance → pic) pour dominer au sol.",
        phases = listOf(
            phase(0, "PHASE 1 — Grip & force de base", "Renforcement du grip, base de force, technique pull explosif."),
            phase(1, "PHASE 2 — Force-endurance", "Isométrie longue, charges modérées : tenir une posture sous fatigue."),
            phase(2, "PHASE 3 — Puissance combinée", "Couplage poussée/tirage explosif, conditionnement sol intense."),
            phase(3, "PHASE 4 — Pic compétition", "Pic d'intensité, volume bas, simulation combat."),
        ),
    )
}
