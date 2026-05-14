package com.marvin.sport.data

/**
 * Programme "Parc" — street workout (barres, dips, espace ouvert pour sprint).
 * 3 phases × 4 semaines × 3 séances pour profiter d'un parc avec aire de
 * street workout.
 */
object ParkProgramBuilder {

    private const val ID = "park"

    private const val REST_MAIN = "2 min repos"
    private const val REST_EXPLO = "90 s repos"
    private const val REST_CIRCUIT = "30 s entre exos · 60 s entre tours"

    private const val WARMUP_PULL = "Mobilité épaules / dead-hang 2x20 s / 20 fermières / shadow 2 min"
    private const val WARMUP_PUSH = "Mobilité épaules/T-spine / 20 pompes mur / dips assistés 2x10"
    private const val WARMUP_LEG = "Mobilité hanches/chevilles / 30 squats / 20 fentes / skipping 3x10"

    private data class WeekSpec(val sets: Int, val mainReps: Int, val sprintRounds: Int)

    private fun scheme(phase: Int): List<WeekSpec> = when (phase) {
        0 -> listOf(WeekSpec(3, 6, 4), WeekSpec(4, 8, 5), WeekSpec(4, 10, 6), WeekSpec(3, 6, 3))
        1 -> listOf(WeekSpec(4, 8, 5), WeekSpec(5, 10, 6), WeekSpec(5, 12, 7), WeekSpec(3, 8, 4))
        else -> listOf(WeekSpec(5, 5, 6), WeekSpec(5, 4, 7), WeekSpec(4, 3, 8), WeekSpec(3, 4, 5))
    }

    private fun s1(p: Int, w: Int, sp: WeekSpec) = Session(
        id = "${ID}_P${p + 1}W${w + 1}S1", programId = ID,
        phaseIndex = p, weekIndex = w, sessionIndex = 0,
        title = "Séance 1 — Pull & grip street",
        warmup = WARMUP_PULL,
        exercises = listOf(
            Exercise("Tractions strictes (barre fixe)", "${sp.sets}", "${sp.mainReps} reps", "Traction lestée", 1.0, REST_MAIN, annotation = "Lester si possible"),
            Exercise("Australian row (barre basse)", "${sp.sets}", "${sp.mainReps + 4} reps", rest = REST_EXPLO, isSuperset = true, supersetGroup = 1),
            Exercise("Sprint 60 m", "2", "${sp.sprintRounds} rounds", rest = REST_CIRCUIT, isSuperset = true, supersetGroup = 1, annotation = "Récup marche retour"),
            Exercise("Dead-hang lesté", "3", "30-45 s", "Traction lestée", 1.0, REST_EXPLO, annotation = "Grip isométrique"),
            Exercise("Knees-to-elbow", "3", "${sp.mainReps + 2} reps", rest = REST_CIRCUIT),
        ),
    )

    private fun s2(p: Int, w: Int, sp: WeekSpec) = Session(
        id = "${ID}_P${p + 1}W${w + 1}S2", programId = ID,
        phaseIndex = p, weekIndex = w, sessionIndex = 1,
        title = "Séance 2 — Push & saut",
        warmup = WARMUP_PUSH,
        exercises = listOf(
            Exercise("Dips lestés (barres parallèles)", "${sp.sets}", "${sp.mainReps} reps", "Dip lesté", 1.0, REST_MAIN, annotation = "Descente contrôlée"),
            Exercise("Pompes claquées au sol", "${sp.sets}", "${sp.mainReps - 2} reps", rest = REST_EXPLO, isSuperset = true, supersetGroup = 1),
            Exercise("Broad jump (saut en longueur)", "${sp.sets}", "5 reps", rest = REST_EXPLO, isSuperset = true, supersetGroup = 1, annotation = "Réception silencieuse"),
            Exercise("Pike push-up (épaules)", "3", "${sp.mainReps} reps", rest = REST_MAIN),
            Exercise("Saut sur banc", "${sp.sprintRounds}", "5 reps", rest = REST_CIRCUIT, annotation = "Triple extension"),
        ),
    )

    private fun s3(p: Int, w: Int, sp: WeekSpec) = Session(
        id = "${ID}_P${p + 1}W${w + 1}S3", programId = ID,
        phaseIndex = p, weekIndex = w, sessionIndex = 2,
        title = "Séance 3 — Jambes & conditionnement",
        warmup = WARMUP_LEG,
        exercises = listOf(
            Exercise("Pistol squat assisté", "${sp.sets}", "${sp.mainReps} par jambe", rest = REST_MAIN, annotation = "Au poteau si besoin"),
            Exercise("Bulgarian split squat (banc)", "${sp.sets}", "${sp.mainReps} par jambe", rest = REST_EXPLO),
            Exercise("Saut groupé (knees-to-chest)", "${sp.sets}", "${sp.mainReps - 2} reps", rest = REST_EXPLO, isSuperset = true, supersetGroup = 1),
            Exercise("Sprawl + sprint 20 m", "${sp.sprintRounds}", "8 reps", rest = REST_CIRCUIT, isSuperset = true, supersetGroup = 1, annotation = "Combat conditioning"),
            Exercise("Bear crawl 20 m", "${sp.sprintRounds}", "Aller-retour", rest = REST_CIRCUIT),
            Exercise("Shadow grappling intense", "${sp.sprintRounds}", "1 min", rest = "30 s repos"),
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
        name = "Parc — Street workout",
        shortName = "Parc",
        description = "Exploitation d'un parc avec aire de street workout. 3 phases technique → volume → puissance.",
        phases = listOf(
            phase(0, "PHASE 1 — Technique", "Exécution stricte, contrôle, base de force avec lest."),
            phase(1, "PHASE 2 — Volume", "Volume élevé, endurance de force, conditionnement aérobie."),
            phase(2, "PHASE 3 — Puissance", "Sauts, sprints, transferts vers combat."),
        ),
    )
}
