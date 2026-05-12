package com.marvin.sport.data

/**
 * Programme "Striking" — Boxe anglaise / MMA debout.
 * 4 phases × 4 semaines × 3 séances par semaine. Travail explosif basé sur :
 *   - PPG, force-vitesse, puissance, pic compétition
 *   - charges relatives aux 1RM du programme musculation (cohérence Marvin)
 *
 * Sessions :
 *   S1 = Bas du corps explosif & sprint
 *   S2 = Push explosif & frappe
 *   S3 = Core, rotation & conditionnement HIIT
 */
object StrikingProgramBuilder {

    private const val PROGRAM_ID = "striking"

    // Bases à partir des 1RM du programme original.
    private const val SQUAT_1RM = 100.0
    private const val DEAD_1RM = 140.0
    private const val BENCH_1RM = 70.0
    private const val INCLINE_1RM = 70.0
    private const val ROW_1RM = 60.0

    private const val REST_HEAVY = "2-3 min repos (récupération complète)"
    private const val REST_EXPLO = "90 s repos (qualité du mouvement)"
    private const val REST_PLYO = "60-90 s repos entre séries"
    private const val REST_CIRCUIT = "30 s entre exercices · 60 s entre tours"

    private const val WARMUP_DYN = "Mobilité dynamique 5' / 3x10 skipping / 10 squats sautés / shadow 2 min"
    private const val WARMUP_UP = "Mobilité épaules/T-spine / pompes progressives / shadow boxing 3 min"
    private const val WARMUP_CORE = "Mobilité hanches/colonne / cat-cow 2x10 / planche 30 s / 100 cordes"

    private data class WeekSpec(
        val sets: Int,
        val plyoReps: Int,   // sauts / lancers
        val mainReps: Int,   // travail force
        val condRounds: Int, // rounds HIIT
    )

    /** Schémas hebdo par phase (semaines 1→4 dans chaque phase). */
    private fun phaseScheme(phase: Int): List<WeekSpec> = when (phase) {
        // Phase 1 — PPG : volume modéré, charges 70-75%, focus technique
        0 -> listOf(
            WeekSpec(sets = 3, plyoReps = 5, mainReps = 6, condRounds = 4),
            WeekSpec(sets = 4, plyoReps = 5, mainReps = 6, condRounds = 4),
            WeekSpec(sets = 4, plyoReps = 6, mainReps = 6, condRounds = 5),
            WeekSpec(sets = 3, plyoReps = 4, mainReps = 5, condRounds = 3),
        )
        // Phase 2 — Force-vitesse : charges 60-75% à vitesse maximale
        1 -> listOf(
            WeekSpec(sets = 4, plyoReps = 5, mainReps = 5, condRounds = 5),
            WeekSpec(sets = 5, plyoReps = 6, mainReps = 4, condRounds = 5),
            WeekSpec(sets = 5, plyoReps = 6, mainReps = 4, condRounds = 6),
            WeekSpec(sets = 3, plyoReps = 4, mainReps = 4, condRounds = 4),
        )
        // Phase 3 — Puissance / pliométrie : charges 40-60% explosivité max
        2 -> listOf(
            WeekSpec(sets = 5, plyoReps = 5, mainReps = 4, condRounds = 6),
            WeekSpec(sets = 5, plyoReps = 6, mainReps = 3, condRounds = 6),
            WeekSpec(sets = 6, plyoReps = 6, mainReps = 3, condRounds = 7),
            WeekSpec(sets = 3, plyoReps = 4, mainReps = 3, condRounds = 4),
        )
        // Phase 4 — Pic compétition : volume bas, intensité élevée
        else -> listOf(
            WeekSpec(sets = 4, plyoReps = 4, mainReps = 3, condRounds = 5),
            WeekSpec(sets = 4, plyoReps = 5, mainReps = 3, condRounds = 6),
            WeekSpec(sets = 3, plyoReps = 4, mainReps = 2, condRounds = 6),
            WeekSpec(sets = 2, plyoReps = 3, mainReps = 2, condRounds = 3),
        )
    }

    /** Charges principales en fonction de la phase. */
    private data class Loads(
        val squatPct: Double,
        val benchPct: Double,
        val deadPct: Double,
        val rowPct: Double,
        val inclinePct: Double,
        val medBallKg: Double,
    )

    private fun phaseLoads(phase: Int): Loads = when (phase) {
        0 -> Loads(0.70, 0.65, 0.70, 0.65, 0.60, 5.0)
        1 -> Loads(0.75, 0.70, 0.75, 0.70, 0.65, 6.0)
        2 -> Loads(0.55, 0.50, 0.60, 0.55, 0.50, 7.0)
        else -> Loads(0.80, 0.75, 0.80, 0.70, 0.70, 8.0)
    }

    private fun s1(p: Int, w: Int, spec: WeekSpec, loads: Loads) = Session(
        id = "${PROGRAM_ID}_P${p + 1}W${w + 1}S1",
        programId = PROGRAM_ID,
        phaseIndex = p, weekIndex = w, sessionIndex = 0,
        title = "Séance 1 — Bas du corps explosif & sprint",
        warmup = WARMUP_DYN,
        exercises = listOf(
            Exercise("Box jump", "${spec.sets}", "${spec.plyoReps} reps", rest = REST_PLYO, annotation = "Réception silencieuse"),
            Exercise("Back squat dynamique", "${spec.sets}", "${spec.mainReps} reps", SQUAT_1RM * loads.squatPct, REST_EXPLO, annotation = "Vitesse de concentrique max"),
            Exercise("Fente sautée alternée", "3", "${spec.plyoReps} par jambe", rest = REST_PLYO, isSuperset = true, supersetGroup = 1),
            Exercise("Sprint navette 20 m", "2", "${spec.condRounds} rounds", rest = REST_CIRCUIT, isSuperset = true, supersetGroup = 1),
            Exercise("Mollets / proprioception", "3", "12 reps", rest = REST_CIRCUIT),
            Exercise("Étirements actifs", "1", "5 min", rest = "—"),
        ),
    )

    private fun s2(p: Int, w: Int, spec: WeekSpec, loads: Loads) = Session(
        id = "${PROGRAM_ID}_P${p + 1}W${w + 1}S2",
        programId = PROGRAM_ID,
        phaseIndex = p, weekIndex = w, sessionIndex = 1,
        title = "Séance 2 — Push explosif & frappe",
        warmup = WARMUP_UP,
        exercises = listOf(
            Exercise("DVP couché balistique", "${spec.sets}", "${spec.mainReps} reps", BENCH_1RM * loads.benchPct, REST_EXPLO, annotation = "Barre projetée"),
            Exercise("Lancer médecine-ball poitrine", "${spec.sets}", "${spec.plyoReps} reps", loads.medBallKg, REST_PLYO, isSuperset = true, supersetGroup = 1, annotation = "Contre mur"),
            Exercise("Pompes claquées", "${spec.sets}", "${spec.plyoReps} reps", rest = REST_PLYO, isSuperset = true, supersetGroup = 1),
            Exercise("DVP incliné contrôlé", "3", "${spec.mainReps + 2} reps", INCLINE_1RM * loads.inclinePct, REST_EXPLO),
            Exercise("Frappe sac (combos 1-2-3-2)", "3", "${spec.condRounds} rounds 1 min", rest = "1 min repos par round", annotation = "Vitesse > puissance"),
            Exercise("Triceps corde / Élévation latérale", "3", "12 reps", rest = REST_CIRCUIT, isSuperset = true, supersetGroup = 2),
        ),
    )

    private fun s3(p: Int, w: Int, spec: WeekSpec, loads: Loads) = Session(
        id = "${PROGRAM_ID}_P${p + 1}W${w + 1}S3",
        programId = PROGRAM_ID,
        phaseIndex = p, weekIndex = w, sessionIndex = 2,
        title = "Séance 3 — Core rotation & HIIT",
        warmup = WARMUP_CORE,
        exercises = listOf(
            Exercise("Soulevé de terre vitesse", "${spec.sets}", "${spec.mainReps} reps", DEAD_1RM * loads.deadPct, REST_HEAVY, annotation = "Concentrique explosif"),
            Exercise("Lancer rotatif médecine-ball", "${spec.sets}", "${spec.plyoReps} par côté", loads.medBallKg, REST_PLYO, isSuperset = true, supersetGroup = 1),
            Exercise("Russian twist lesté", "${spec.sets}", "20 reps", 5.0, REST_PLYO, isSuperset = true, supersetGroup = 1),
            Exercise("Dragon flag", "3", "${spec.plyoReps + 2} reps", rest = REST_CIRCUIT),
            Exercise("Burpees + sprawl", "${spec.condRounds}", "30 s ON / 30 s OFF", rest = "—", annotation = "HIIT spécifique"),
            Exercise("Corde à sauter (rapide)", "${spec.condRounds}", "1 min", rest = "30 s repos"),
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
        name = "Striking — Boxe / MMA debout",
        shortName = "Striking",
        description = "16 semaines en 4 phases (PPG → force-vitesse → puissance → pic) pour développer l'explosivité du puncheur.",
        phases = listOf(
            phase(0, "PHASE 1 — PPG explosive", "Préparation physique générale, technique du saut et du sprint, base force-vitesse."),
            phase(1, "PHASE 2 — Force-vitesse", "Charges modérées exécutées à vitesse maximale. Couplage barre / pliométrie."),
            phase(2, "PHASE 3 — Puissance pliométrique", "Pliométrie intense, lancers, charges légères : vitesse maximale du mouvement."),
            phase(3, "PHASE 4 — Pic compétition", "Volume réduit, intensité élevée, transferts spécifiques au combat."),
        ),
    )
}
