package com.marvin.sport.data

/**
 * Programme "Striking" — Boxe anglaise / MMA debout.
 * 3 phases × 4 semaines × 3 séances axées explosivité du puncheur.
 *   Phase 1 : PPG explosive (technique sauts + base force-vitesse)
 *   Phase 2 : Force-vitesse + volume (couplage barre / pliométrie)
 *   Phase 3 : Puissance pliométrique + pic compétition
 */
object StrikingProgramBuilder {

    private const val ID = "striking"

    private const val REST_HEAVY = "2-3 min repos"
    private const val REST_EXPLO = "90 s repos (qualité du mouvement)"
    private const val REST_PLYO = "60-90 s repos entre séries"
    private const val REST_CIRCUIT = "30 s entre exos · 60 s entre tours"

    private const val WARMUP_DYN = "Mobilité dynamique 5' / skipping 3x10 / 10 squats sautés / shadow 2 min"
    private const val WARMUP_UP = "Mobilité épaules/T-spine / pompes progressives / shadow boxing 3 min"
    private const val WARMUP_CORE = "Mobilité hanches/colonne / cat-cow / planche 30 s / 100 cordes"

    /** Finisher cardio explosif standard — 5 min max (10 rounds 30 s / 30 s). */
    private fun cardioFinisher(annotation: String) = Exercise(
        name = "Cardio explosif final",
        sets = "10",
        reps = "30 s ON / 30 s OFF",
        rest = "—",
        annotation = annotation,
    )

    private data class WeekSpec(val sets: Int, val plyoReps: Int, val mainReps: Int, val rounds: Int)

    private fun phaseScheme(phase: Int): List<WeekSpec> = when (phase) {
        // Phase 1 — PPG
        0 -> listOf(
            WeekSpec(3, 5, 6, 4),
            WeekSpec(4, 5, 6, 4),
            WeekSpec(4, 6, 6, 5),
            WeekSpec(3, 4, 5, 3),
        )
        // Phase 2 — Force-vitesse / volume
        1 -> listOf(
            WeekSpec(4, 5, 5, 5),
            WeekSpec(5, 6, 5, 5),
            WeekSpec(5, 6, 4, 6),
            WeekSpec(3, 4, 4, 4),
        )
        // Phase 3 — Puissance + pic
        else -> listOf(
            WeekSpec(5, 5, 4, 6),
            WeekSpec(5, 6, 3, 7),
            WeekSpec(4, 5, 3, 6),
            WeekSpec(3, 4, 2, 4),
        )
    }

    private data class Loads(
        val squatPct: Double,
        val benchPct: Double,
        val deadPct: Double,
        val rowPct: Double,
        val inclinePct: Double,
        val medBallPct: Double,
    )

    private fun phaseLoads(phase: Int): Loads = when (phase) {
        0 -> Loads(0.70, 0.65, 0.70, 0.65, 0.60, 1.0)
        1 -> Loads(0.75, 0.70, 0.75, 0.70, 0.65, 1.0)
        else -> Loads(0.60, 0.55, 0.65, 0.60, 0.55, 1.2)
    }

    private fun s1(p: Int, w: Int, spec: WeekSpec, loads: Loads) = Session(
        id = "${ID}_P${p + 1}W${w + 1}S1", programId = ID,
        phaseIndex = p, weekIndex = w, sessionIndex = 0,
        title = "Séance 1 — Bas du corps explosif & sprint",
        warmup = WARMUP_DYN,
        exercises = listOf(
            Exercise("Box jump", "${spec.sets}", "${spec.plyoReps} reps", rest = REST_PLYO, annotation = "Réception silencieuse"),
            Exercise("Back squat dynamique", "${spec.sets}", "${spec.mainReps} reps", "Back Squat", loads.squatPct, REST_EXPLO, annotation = "Vitesse concentrique max"),
            Exercise("Fente sautée alternée", "3", "${spec.plyoReps} par jambe", rest = REST_PLYO, isSuperset = true, supersetGroup = 1),
            Exercise("Sprint navette 20 m", "2", "${spec.rounds} rounds", rest = REST_CIRCUIT, isSuperset = true, supersetGroup = 1),
            cardioFinisher("Sprint navettes 30 s / 30 s — alternance sprint linéaire / sauts groupés"),
        ),
    )

    private fun s2(p: Int, w: Int, spec: WeekSpec, loads: Loads) = Session(
        id = "${ID}_P${p + 1}W${w + 1}S2", programId = ID,
        phaseIndex = p, weekIndex = w, sessionIndex = 1,
        title = "Séance 2 — Push explosif & frappe",
        warmup = WARMUP_UP,
        exercises = listOf(
            Exercise("DVP couché balistique", "${spec.sets}", "${spec.mainReps} reps", "DVP couché", loads.benchPct, REST_EXPLO, annotation = "Barre projetée"),
            Exercise("Lancer médecine-ball poitrine", "${spec.sets}", "${spec.plyoReps} reps", "Médecine-ball", loads.medBallPct, REST_PLYO, isSuperset = true, supersetGroup = 1, annotation = "Contre mur"),
            Exercise("Pompes claquées", "${spec.sets}", "${spec.plyoReps} reps", rest = REST_PLYO, isSuperset = true, supersetGroup = 1),
            Exercise("DVP incliné contrôlé", "3", "${spec.mainReps + 2} reps", "DVP Incliné", loads.inclinePct, REST_EXPLO),
            Exercise("Frappe sac (combos 1-2-3-2)", "3", "${spec.rounds} rounds 1 min", rest = "1 min repos par round", annotation = "Vitesse > puissance"),
            Exercise("Triceps corde / Élévation latérale", "3", "12 reps", "Triceps corde", 1.0, REST_CIRCUIT, isSuperset = true, supersetGroup = 2),
            cardioFinisher("Sac de frappe combos 1-2-3-2 / corde rapide en alternance"),
        ),
    )

    private fun s3(p: Int, w: Int, spec: WeekSpec, loads: Loads) = Session(
        id = "${ID}_P${p + 1}W${w + 1}S3", programId = ID,
        phaseIndex = p, weekIndex = w, sessionIndex = 2,
        title = "Séance 3 — Core rotation & HIIT",
        warmup = WARMUP_CORE,
        exercises = listOf(
            Exercise("Soulevé de terre vitesse", "${spec.sets}", "${spec.mainReps} reps", "Deadlift", loads.deadPct, REST_HEAVY, annotation = "Concentrique explosif"),
            Exercise("Lancer rotatif médecine-ball", "${spec.sets}", "${spec.plyoReps} par côté", "Médecine-ball", loads.medBallPct, REST_PLYO, isSuperset = true, supersetGroup = 1),
            Exercise("Russian twist lesté", "${spec.sets}", "20 reps", "Russian twist lesté", 1.0, REST_PLYO, isSuperset = true, supersetGroup = 1),
            Exercise("Dragon flag", "3", "${spec.plyoReps + 2} reps", rest = REST_CIRCUIT),
            Exercise("Burpees + sprawl", "${spec.rounds}", "30 s ON / 30 s OFF", rest = "—", annotation = "HIIT spécifique"),
            Exercise("Corde à sauter (rapide)", "${spec.rounds}", "1 min", rest = "30 s repos"),
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
        name = "Striking — Boxe / MMA debout",
        shortName = "Striking",
        description = "12 semaines en 3 phases (PPG → force-vitesse → puissance pic) pour développer l'explosivité du puncheur.",
        phases = listOf(
            phase(0, "PHASE 1 — PPG explosive", "Préparation physique générale, technique du saut et du sprint, base force-vitesse."),
            phase(1, "PHASE 2 — Force-vitesse", "Charges modérées exécutées à vitesse maximale. Couplage barre / pliométrie."),
            phase(2, "PHASE 3 — Puissance pic", "Pliométrie intense, lancers, charges légères : vitesse maximale du mouvement. Pic compétition."),
        ),
    )
}
