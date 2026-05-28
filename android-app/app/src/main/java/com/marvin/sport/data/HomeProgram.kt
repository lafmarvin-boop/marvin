package com.marvin.sport.data

/**
 * Programme "Maison" — full body sans matériel, orienté grappling / boxe / MMA.
 * 3 phases × 4 semaines × 3 séances : focus explosivité, endurance musculaire,
 * transferts spécifiques au combat (takedowns, frappes, gainage rotationnel,
 * mobilité au sol).
 */
object HomeProgramBuilder {

    private const val ID = "home"

    private const val REST_MAIN = "45-60 s repos"
    private const val REST_EXPLO = "60 s repos · qualité du mouvement"
    private const val REST_SUPER = "15-20 s entre exos · 45 s entre tours"
    private const val REST_ISO = "30-40 s repos · respiration nasale"
    private const val REST_CIRCUIT = "20 s entre exos · 45 s entre tours"

    private const val WARMUP_DYN = "Mobilité dynamique 5' / hip openers / 20 squats / shadow 2'"
    private const val WARMUP_PUSH = "Mobilité épaules/T-spine 3' / 20 pompes mur / 10 dislocations / shadow 2'"
    private const val WARMUP_PULL = "Mobilité dos/hanches 3' / 15 superman / 20 bridges / shrimps 2x10"

    private data class WeekSpec(
        val sets: Int,
        val mainReps: Int,
        val plyoReps: Int,
        val isoSec: Int,
        val rounds: Int,
    )

    /**
     * Schémas par phase. Phase 1 = technique, Phase 2 = endurance/volume,
     * Phase 3 = puissance + conditionnement. La semaine 4 de chaque phase
     * est un deload.
     */
    private fun scheme(phase: Int): List<WeekSpec> = when (phase) {
        // Phase 1 — Technique & base
        0 -> listOf(
            WeekSpec(sets = 3, mainReps = 10, plyoReps = 8, isoSec = 30, rounds = 4),
            WeekSpec(sets = 3, mainReps = 12, plyoReps = 8, isoSec = 35, rounds = 4),
            WeekSpec(sets = 4, mainReps = 12, plyoReps = 10, isoSec = 40, rounds = 5),
            WeekSpec(sets = 3, mainReps = 8, plyoReps = 6, isoSec = 25, rounds = 3),
        )
        // Phase 2 — Endurance musculaire
        1 -> listOf(
            WeekSpec(sets = 4, mainReps = 15, plyoReps = 10, isoSec = 45, rounds = 5),
            WeekSpec(sets = 4, mainReps = 18, plyoReps = 12, isoSec = 50, rounds = 6),
            WeekSpec(sets = 5, mainReps = 20, plyoReps = 12, isoSec = 60, rounds = 6),
            WeekSpec(sets = 3, mainReps = 12, plyoReps = 8, isoSec = 30, rounds = 4),
        )
        // Phase 3 — Puissance + conditionnement combat
        else -> listOf(
            WeekSpec(sets = 5, mainReps = 10, plyoReps = 6, isoSec = 45, rounds = 6),
            WeekSpec(sets = 6, mainReps = 8, plyoReps = 8, isoSec = 50, rounds = 7),
            WeekSpec(sets = 6, mainReps = 10, plyoReps = 8, isoSec = 60, rounds = 8),
            WeekSpec(sets = 3, mainReps = 8, plyoReps = 6, isoSec = 30, rounds = 4),
        )
    }

    private fun s1(p: Int, w: Int, sp: WeekSpec) = Session(
        id = "${ID}_P${p + 1}W${w + 1}S1", programId = ID,
        phaseIndex = p, weekIndex = w, sessionIndex = 0,
        title = "Séance 1 — Bas du corps explosif (takedown / level change)",
        warmup = WARMUP_DYN,
        exercises = listOf(
            Exercise(
                "Squat sauté max", "${sp.sets}", "${sp.plyoReps} reps",
                rest = REST_EXPLO,
                annotation = "Saut le plus haut possible, réception silencieuse",
            ),
            Exercise(
                "Pistol squat (assisté si besoin)", "${sp.sets}", "${sp.mainReps / 2} par jambe",
                rest = REST_MAIN,
                annotation = "Au poteau / TRX pour assistance — force unilatérale",
            ),
            Exercise(
                "Sprawl + saut groupé", "${sp.sets}", "${sp.plyoReps} reps",
                rest = REST_EXPLO,
                isSuperset = true, supersetGroup = 1,
                annotation = "Descente brutale + remontée explosive",
            ),
            Exercise(
                "Fente sautée alternée", "${sp.sets}", "${sp.mainReps} par jambe",
                rest = REST_SUPER,
                isSuperset = true, supersetGroup = 1,
            ),
            Exercise(
                "Hindu squat (gymnastique)", "3", "${sp.mainReps + 5} reps",
                rest = REST_MAIN,
                annotation = "Mouvement souple, talons décollés, contrôle hanches",
            ),
            Exercise(
                "Wall sit lesté (sac à dos)", "3", "${sp.isoSec} s",
                oneRmKey = "Sac à dos lesté", percentage = 1.0,
                rest = REST_ISO,
                annotation = "Cuisses parallèles, sac sur les cuisses",
            ),
            Exercise(
                "Sprint sur place high knees", "${sp.rounds}", "30 s ON / 30 s OFF",
                rest = "—",
                annotation = "Cardio explosif final — genoux hauts vitesse max",
            ),
        ),
    )

    private fun s2(p: Int, w: Int, sp: WeekSpec) = Session(
        id = "${ID}_P${p + 1}W${w + 1}S2", programId = ID,
        phaseIndex = p, weekIndex = w, sessionIndex = 1,
        title = "Séance 2 — Push explosif, frappe & gainage avant",
        warmup = WARMUP_PUSH,
        exercises = listOf(
            Exercise(
                "Pompes pliométriques (claquées si possible)", "${sp.sets}", "${sp.plyoReps} reps",
                rest = REST_EXPLO,
                annotation = "Propulsion max, claquer si tu peux",
            ),
            Exercise(
                "Pompes diamant", "${sp.sets}", "${sp.mainReps} reps",
                rest = REST_SUPER,
                isSuperset = true, supersetGroup = 1,
                annotation = "Coudes serrés, focus triceps",
            ),
            Exercise(
                "Pompes archer (unilat alterné)", "${sp.sets}", "${sp.mainReps / 2} par côté",
                rest = REST_SUPER,
                isSuperset = true, supersetGroup = 1,
                annotation = "Bras tendu opposé, charge sur un côté",
            ),
            Exercise(
                "Dips entre 2 chaises", "${sp.sets}", "${sp.mainReps + 2} reps",
                rest = REST_MAIN,
                annotation = "Coudes en arrière, descente contrôlée",
            ),
            Exercise(
                "Pike push-up (épaules)", "3", "${sp.mainReps - 2} reps",
                rest = REST_MAIN,
                annotation = "Pieds surélevés si possible — angle plus vertical",
            ),
            Exercise(
                "Planche bras tendus", "3", "${sp.isoSec} s",
                rest = REST_ISO,
                annotation = "Corps gainé, gainage abdo profond",
            ),
            Exercise(
                "Shadow boxing combos 1-2-3-2", "${sp.rounds}", "1 min",
                rest = "30 s repos par round",
                annotation = "Vitesse > puissance, retour à la garde",
            ),
            Exercise(
                "Sprawl + pompe + jab", "${sp.rounds}", "30 s ON / 30 s OFF",
                rest = "—",
                annotation = "Cardio explosif final — combat conditioning",
            ),
        ),
    )

    private fun s3(p: Int, w: Int, sp: WeekSpec) = Session(
        id = "${ID}_P${p + 1}W${w + 1}S3", programId = ID,
        phaseIndex = p, weekIndex = w, sessionIndex = 2,
        title = "Séance 3 — Pull, dos & conditionnement grappling",
        warmup = WARMUP_PULL,
        exercises = listOf(
            Exercise(
                "Rowing inversé (table robuste)", "${sp.sets}", "${sp.mainReps} reps",
                rest = REST_MAIN,
                annotation = "Corps gainé, tirer la poitrine vers la table",
            ),
            Exercise(
                "Pull-up à la porte (serviette)", "${sp.sets}", "max reps",
                rest = REST_EXPLO,
                annotation = "Serviette pliée sur porte solide, ou barre si dispo",
            ),
            Exercise(
                "Bridge wrestling (cou)", "3", "${sp.isoSec} s",
                rest = REST_ISO,
                annotation = "Pont gymnique, charge progressive sur la nuque",
            ),
            Exercise(
                "Hip escape / shrimp", "3", "${sp.mainReps / 2} par côté",
                rest = REST_SUPER,
                isSuperset = true, supersetGroup = 1,
                annotation = "Mobilité hanches au sol, base BJJ",
            ),
            Exercise(
                "Bear crawl 10 m aller-retour", "${sp.sets}", "${sp.rounds / 2} AR",
                rest = REST_SUPER,
                isSuperset = true, supersetGroup = 1,
                annotation = "Genoux à 5 cm du sol, dos plat",
            ),
            Exercise(
                "Dragon flag (jambes tendues)", "3", "${sp.plyoReps - 2} reps max",
                rest = REST_MAIN,
                annotation = "Descente lente, gainage total",
            ),
            Exercise(
                "Hollow body hold", "3", "${sp.isoSec} s",
                rest = REST_ISO,
                annotation = "Bas du dos plaqué, bras et jambes décollés",
            ),
            Exercise(
                "Burpees + sprawl + shadow takedown", "${sp.rounds}", "30 s ON / 30 s OFF",
                rest = "—",
                annotation = "Cardio explosif final — simule un round MMA",
            ),
        ),
    )

    private fun phase(index: Int, title: String, description: String): Phase {
        val sp = scheme(index)
        val weeks = (0..3).map { w ->
            Week(
                index = w,
                label = "Semaine ${w + 1}",
                sessions = listOf(s1(index, w, sp[w]), s2(index, w, sp[w]), s3(index, w, sp[w])),
            )
        }
        return Phase(index = index, title = title, description = description, weeks = weeks)
    }

    fun build() = TrainingProgram(
        id = ID,
        name = "Maison — Combat / Sans matériel",
        shortName = "Maison",
        description = "Séances dures à la maison axées explosivité et endurance musculaire pour grappling, boxe et MMA. 3 phases : technique → volume → puissance + conditionnement combat.",
        phases = listOf(
            phase(
                0,
                "PHASE 1 — Technique & base explosive",
                "Exécution propre, amplitude, base pliométrique et isométrique.",
            ),
            phase(
                1,
                "PHASE 2 — Endurance musculaire",
                "Volume élevé, supersets, holds longs : tenir l'effort sous fatigue.",
            ),
            phase(
                2,
                "PHASE 3 — Puissance + conditionnement combat",
                "Pliométrie max, transferts spécifiques combat, HIIT proche du rythme d'un round.",
            ),
        ),
    )
}
