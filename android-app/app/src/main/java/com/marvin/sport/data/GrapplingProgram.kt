package com.marvin.sport.data

/**
 * Programme "Grappling" — Lutte / BJJ / MMA sol.
 * 3 phases × 4 semaines × 3 séances.
 *   Phase 1 : Grip & force de base
 *   Phase 2 : Force-endurance + posture isométrique
 *   Phase 3 : Puissance combinée + pic compétition
 */
object GrapplingProgramBuilder {

    private const val ID = "grappling"

    private const val REST_HEAVY = "2-3 min repos"
    private const val REST_EXPLO = "90 s repos (qualité du mouvement)"
    private const val REST_ISO = "60 s repos · respiration nasale"
    private const val REST_CIRCUIT = "30 s entre exos · 60 s entre tours"

    private const val WARMUP_GRIP = "Mobilité poignets/épaules / dead-hang 2x20 s / 20 fermières"
    private const val WARMUP_POST = "Hip airplane 2x6 / cat-cow 2x10 / bird-dog 2x10 / pont fessier 2x12"
    private const val WARMUP_WREST = "Tour de tapis / shrimps / sprawls 3x10 / shadow grappling 3 min"

    private data class WeekSpec(
        val sets: Int, val mainReps: Int, val isoSec: Int, val gripSec: Int, val rounds: Int,
    )

    private fun phaseScheme(phase: Int): List<WeekSpec> = when (phase) {
        // Phase 1 — Grip & force de base
        0 -> listOf(
            WeekSpec(3, 6, 30, 30, 4),
            WeekSpec(4, 6, 35, 35, 4),
            WeekSpec(4, 5, 40, 40, 5),
            WeekSpec(3, 5, 25, 25, 3),
        )
        // Phase 2 — Force-endurance / iso
        1 -> listOf(
            WeekSpec(4, 6, 45, 45, 5),
            WeekSpec(5, 5, 50, 50, 6),
            WeekSpec(5, 5, 60, 55, 6),
            WeekSpec(3, 4, 30, 30, 4),
        )
        // Phase 3 — Puissance combinée + pic
        else -> listOf(
            WeekSpec(5, 4, 40, 40, 6),
            WeekSpec(5, 3, 45, 45, 7),
            WeekSpec(4, 3, 35, 35, 6),
            WeekSpec(3, 3, 25, 25, 4),
        )
    }

    private data class Loads(
        val deadPct: Double,
        val rowPct: Double,
        val pullHighPct: Double,
        val pullLowPct: Double,
        val squatPct: Double,
        val frontSquatPct: Double,
        val pushPressPct: Double,
        val powerCleanPct: Double,
        val kbPct: Double,
    )

    private fun phaseLoads(phase: Int): Loads = when (phase) {
        0 -> Loads(0.70, 0.70, 0.75, 0.75, 0.70, 0.65, 0.65, 0.55, 0.80)
        1 -> Loads(0.75, 0.75, 0.80, 0.80, 0.75, 0.70, 0.70, 0.65, 1.00)
        else -> Loads(0.80, 0.70, 0.75, 0.70, 0.70, 0.75, 0.75, 0.70, 1.20)
    }

    private fun s1(p: Int, w: Int, spec: WeekSpec, loads: Loads) = Session(
        id = "${ID}_P${p + 1}W${w + 1}S1", programId = ID,
        phaseIndex = p, weekIndex = w, sessionIndex = 0,
        title = "Séance 1 — Tirage explosif & grip",
        warmup = WARMUP_GRIP,
        exercises = listOf(
            Exercise("Power clean", "${spec.sets}", "${spec.mainReps} reps", "Power clean", loads.powerCleanPct, REST_HEAVY, annotation = "Triple extension"),
            Exercise("Traction lestée explosive", "${spec.sets}", "${spec.mainReps} reps", "Traction lestée", 1.0, REST_EXPLO, isSuperset = true, supersetGroup = 1, annotation = "Concentrique rapide"),
            Exercise("Barbell row", "${spec.sets}", "${spec.mainReps + 2} reps", "Barbell Row", loads.rowPct, REST_EXPLO, isSuperset = true, supersetGroup = 1),
            Exercise("Dead-hang lesté", "3", "${spec.gripSec} s", "Traction lestée", 1.0, REST_ISO, annotation = "Grip isométrique"),
            Exercise("Fermières (gi-grip simulé)", "3", "${spec.gripSec} s par main", rest = REST_ISO, isSuperset = true, supersetGroup = 2),
            Exercise("Curl marteau", "3", "10 reps", "Curl marteau", 1.0, REST_CIRCUIT, isSuperset = true, supersetGroup = 2),
        ),
    )

    private fun s2(p: Int, w: Int, spec: WeekSpec, loads: Loads) = Session(
        id = "${ID}_P${p + 1}W${w + 1}S2", programId = ID,
        phaseIndex = p, weekIndex = w, sessionIndex = 1,
        title = "Séance 2 — Chaîne postérieure & tronc iso",
        warmup = WARMUP_POST,
        exercises = listOf(
            Exercise("Deadlift vitesse", "${spec.sets}", "${spec.mainReps} reps", "Deadlift", loads.deadPct, REST_HEAVY, annotation = "Tirage explosif"),
            Exercise("KB swing russe", "${spec.sets}", "12 reps", "Kettlebell", loads.kbPct, REST_EXPLO, isSuperset = true, supersetGroup = 1),
            Exercise("Glute-ham raise / Nordic curl", "${spec.sets}", "${spec.mainReps + 2} reps", rest = REST_EXPLO, isSuperset = true, supersetGroup = 1),
            Exercise("Planche bras tendus", "3", "${spec.isoSec} s", rest = REST_ISO),
            Exercise("Hollow body hold", "3", "${spec.isoSec} s", rest = REST_ISO),
            Exercise("Wall sit lesté", "3", "${spec.isoSec} s", "Wall sit lesté", 1.0, REST_ISO, annotation = "Sac/plaque sur cuisses"),
        ),
    )

    private fun s3(p: Int, w: Int, spec: WeekSpec, loads: Loads) = Session(
        id = "${ID}_P${p + 1}W${w + 1}S3", programId = ID,
        phaseIndex = p, weekIndex = w, sessionIndex = 2,
        title = "Séance 3 — Wrestling-spécifique",
        warmup = WARMUP_WREST,
        exercises = listOf(
            Exercise("Front squat puissance", "${spec.sets}", "${spec.mainReps} reps", "Front Squat", loads.frontSquatPct, REST_HEAVY, annotation = "Posture verticale"),
            Exercise("Push press", "${spec.sets}", "${spec.mainReps} reps", "Push press", loads.pushPressPct, REST_EXPLO),
            Exercise("Tirage poulie haute explosif", "${spec.sets}", "${spec.mainReps + 2} reps", "Tirage poulie haute", loads.pullHighPct, REST_EXPLO, isSuperset = true, supersetGroup = 1),
            Exercise("Tirage poulie basse explosif", "${spec.sets}", "${spec.mainReps + 2} reps", "Tirage poulie basse", loads.pullLowPct, REST_EXPLO, isSuperset = true, supersetGroup = 1),
            Exercise("Bear crawl + sprawl", "${spec.rounds}", "30 s ON / 30 s OFF", rest = "—", annotation = "Conditionnement spécifique sol"),
            Exercise("Shadow grappling intense", "${spec.rounds}", "1 min", rest = "30 s repos"),
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
        id = ID,
        name = "Grappling — Lutte / BJJ / MMA sol",
        shortName = "Grappling",
        description = "12 semaines en 3 phases (grip → endurance posture → puissance pic) pour dominer au sol.",
        phases = listOf(
            phase(0, "PHASE 1 — Grip & force de base", "Renforcement du grip, base de force, technique pull explosif."),
            phase(1, "PHASE 2 — Force-endurance", "Isométrie longue, charges modérées : tenir une posture sous fatigue."),
            phase(2, "PHASE 3 — Puissance & pic", "Couplage poussée/tirage explosif, conditionnement sol intense. Pic compétition."),
        ),
    )
}
