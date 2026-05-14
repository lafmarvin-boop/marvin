package com.marvin.sport.data

/**
 * Programme "Maison" — full body sans matériel (option sac à dos lesté).
 * 3 phases × 4 semaines × 3 séances : pour les jours sans accès à la salle.
 */
object HomeProgramBuilder {

    private const val ID = "home"

    private const val REST_MAIN = "60-90 s repos"
    private const val REST_CIRCUIT = "30 s entre exos · 60 s entre tours"
    private const val REST_ISO = "60 s repos"

    private const val WARMUP_FULL = "Mobilité 5' / 30 squats / 20 fentes / 20 pompes mur"
    private const val WARMUP_PUSH = "Mobilité épaules 3' / 20 pompes mur / 10 chats-vaches"
    private const val WARMUP_PULL = "Mobilité dos 3' / 20 superman / 10 cobras"

    private data class WeekSpec(val sets: Int, val reps: Int, val isoSec: Int, val rounds: Int)

    private fun scheme(phase: Int): List<WeekSpec> = when (phase) {
        0 -> listOf(WeekSpec(3, 10, 30, 3), WeekSpec(3, 12, 35, 4), WeekSpec(4, 12, 40, 4), WeekSpec(3, 10, 25, 3))
        1 -> listOf(WeekSpec(4, 12, 40, 4), WeekSpec(4, 15, 45, 5), WeekSpec(5, 15, 50, 5), WeekSpec(3, 10, 30, 3))
        else -> listOf(WeekSpec(5, 8, 45, 5), WeekSpec(5, 10, 50, 6), WeekSpec(6, 10, 60, 6), WeekSpec(3, 8, 30, 3))
    }

    private fun s1(p: Int, w: Int, sp: WeekSpec) = Session(
        id = "${ID}_P${p + 1}W${w + 1}S1", programId = ID,
        phaseIndex = p, weekIndex = w, sessionIndex = 0,
        title = "Séance 1 — Bas du corps maison",
        warmup = WARMUP_FULL,
        exercises = listOf(
            Exercise("Squat lesté (sac à dos)", "${sp.sets}", "${sp.reps + 4} reps", "Sac à dos lesté", 1.0, REST_MAIN),
            Exercise("Fente arrière alternée", "${sp.sets}", "${sp.reps} par jambe", rest = REST_MAIN, isSuperset = true, supersetGroup = 1),
            Exercise("Squat sauté", "${sp.sets}", "${sp.reps - 2} reps", rest = REST_MAIN, isSuperset = true, supersetGroup = 1),
            Exercise("Pont fessier monojambe", "3", "${sp.reps} par jambe", rest = REST_CIRCUIT),
            Exercise("Wall sit", "3", "${sp.isoSec} s", rest = REST_ISO, annotation = "Cuisses parallèles au sol"),
            Exercise("Mountain climbers", "${sp.rounds}", "30 s ON / 30 s OFF", rest = "—"),
        ),
    )

    private fun s2(p: Int, w: Int, sp: WeekSpec) = Session(
        id = "${ID}_P${p + 1}W${w + 1}S2", programId = ID,
        phaseIndex = p, weekIndex = w, sessionIndex = 1,
        title = "Séance 2 — Push & core",
        warmup = WARMUP_PUSH,
        exercises = listOf(
            Exercise("Pompes (lestées si possible)", "${sp.sets}", "${sp.reps} reps", "Sac à dos lesté", 0.5, REST_MAIN, annotation = "Coudes ~45°"),
            Exercise("Pompes diamant", "${sp.sets}", "${sp.reps - 4} reps", rest = REST_MAIN, isSuperset = true, supersetGroup = 1),
            Exercise("Dips entre 2 chaises", "${sp.sets}", "${sp.reps} reps", rest = REST_MAIN, isSuperset = true, supersetGroup = 1, annotation = "Coudes en arrière"),
            Exercise("Pike push-up (épaules)", "3", "${sp.reps - 2} reps", rest = REST_MAIN, annotation = "Pieds surélevés si possible"),
            Exercise("Planche bras tendus", "3", "${sp.isoSec} s", rest = REST_ISO),
            Exercise("Hollow body hold", "3", "${sp.isoSec} s", rest = REST_ISO),
        ),
    )

    private fun s3(p: Int, w: Int, sp: WeekSpec) = Session(
        id = "${ID}_P${p + 1}W${w + 1}S3", programId = ID,
        phaseIndex = p, weekIndex = w, sessionIndex = 2,
        title = "Séance 3 — Pull & conditionnement",
        warmup = WARMUP_PULL,
        exercises = listOf(
            Exercise("Rowing inversé (table robuste)", "${sp.sets}", "${sp.reps} reps", rest = REST_MAIN, annotation = "Corps gainé, tirer poitrine vers la table"),
            Exercise("Superman + Y-T-W", "${sp.sets}", "${sp.reps} reps de chaque", rest = REST_MAIN, isSuperset = true, supersetGroup = 1),
            Exercise("Bird-dog", "${sp.sets}", "${sp.reps} par côté", rest = REST_MAIN, isSuperset = true, supersetGroup = 1),
            Exercise("Burpees", "${sp.rounds}", "30 s ON / 30 s OFF", rest = "—", annotation = "Cadence constante"),
            Exercise("Corde à sauter (ou jumping jacks)", "${sp.rounds}", "1 min", rest = "30 s repos"),
            Exercise("Shadow boxing", "${sp.rounds}", "1 min", rest = "30 s repos", annotation = "Combos jab-cross-hook"),
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
        name = "Maison — Sans matériel",
        shortName = "Maison",
        description = "Plan d'urgence sans équipement (option sac à dos lesté). 3 phases techniques → volume → conditionnement.",
        phases = listOf(
            phase(0, "PHASE 1 — Technique", "Exécution propre, amplitude, contrôle du tempo."),
            phase(1, "PHASE 2 — Volume", "Augmentation des séries et reps, endurance musculaire."),
            phase(2, "PHASE 3 — Conditionnement", "Intensité haute, ratios courts, transferts spécifiques combat."),
        ),
    )
}
