package com.marvin.sport.data

/**
 * Programme "Musculation" original — 3 phases × 4 semaines × 3 séances.
 * Les charges sont définies via une référence 1RM + un pourcentage : c'est
 * l'utilisateur qui paramètre les 1RM dans l'écran Charges.
 */
object StrengthProgramBuilder {

    private const val ID = "strength"

    private const val P85 = 0.85
    private const val P80 = 0.80
    private const val P75 = 0.75
    private const val P70 = 0.70

    private const val REST_MAIN = "2-4 min repos"
    private const val REST_SUPER = "30-90s repos après superset"
    private const val REST_ABS = "30-60s repos après 2 séries"
    private const val REST_ARMS = "30-90s repos après chaque exo"
    private const val REST_CARDIO = "1 min repos"

    private const val WARMUP_LONG = "Mobilité / échauffement / 3x10 sauts longueur"
    private const val WARMUP_HIGH = "Mobilité / échauffement / 3x10 sauts hauteur"

    private fun s1(p: Int, w: Int, sets: Int, rSquat: Int, rRow: Int, rIncl: Int) = Session(
        id = "${ID}_P${p + 1}W${w + 1}S1", programId = ID,
        phaseIndex = p, weekIndex = w, sessionIndex = 0,
        title = "Séance 1 — Bas du corps / Tirage",
        warmup = WARMUP_LONG,
        exercises = listOf(
            Exercise("Back Squat", "$sets", "$rSquat RM", "Back Squat", P85, REST_MAIN),
            Exercise("Barbell Row", "$sets", "$rRow RM", "Barbell Row", P80, REST_SUPER, isSuperset = true, supersetGroup = 1),
            Exercise("DVP Incliné", "$sets", "$rIncl RM", "DVP Incliné", P70, REST_SUPER, isSuperset = true, supersetGroup = 1),
            Exercise("Frappe latérale", "$sets", "5 par côté", rest = REST_ABS),
            Exercise("Biceps / Deltoïde / Triceps", "$sets", "15 Reps", rest = REST_ARMS),
            Exercise("Cardio", "3", "3 min", rest = REST_CARDIO, annotation = "Vitesse max"),
        ),
    )

    private fun s2(p: Int, w: Int, sets: Int, rDead: Int, rHigh: Int, rLow: Int) = Session(
        id = "${ID}_P${p + 1}W${w + 1}S2", programId = ID,
        phaseIndex = p, weekIndex = w, sessionIndex = 1,
        title = "Séance 2 — Dos / Posterior chain",
        warmup = WARMUP_HIGH,
        exercises = listOf(
            Exercise("Deadlift", "$sets", "$rDead RM", "Deadlift", P85, REST_MAIN),
            Exercise("Tirage poulie haute", "$sets", "$rHigh RM", "Tirage poulie haute", P80, REST_SUPER, isSuperset = true, supersetGroup = 1),
            Exercise("Tirage poulie basse", "$sets", "$rLow RM", "Tirage poulie basse", P80, REST_SUPER, isSuperset = true, supersetGroup = 1),
            Exercise("Dragon flag", "$sets", "12 Reps", rest = REST_ABS),
            Exercise("Biceps / Deltoïde / Triceps", "$sets", "15 Reps", rest = REST_ARMS),
            Exercise("Cardio", "3", "3 min", rest = REST_CARDIO, annotation = "Vitesse max"),
        ),
    )

    private fun s3(p: Int, w: Int, sets: Int, rLunge: Int, rBench: Int, rFly: Int) = Session(
        id = "${ID}_P${p + 1}W${w + 1}S3", programId = ID,
        phaseIndex = p, weekIndex = w, sessionIndex = 2,
        title = "Séance 3 — Jambes unilatéral / Push",
        warmup = WARMUP_LONG,
        exercises = listOf(
            Exercise("Fente", "$sets", "$rLunge RM", "Fente", P75, REST_MAIN),
            Exercise("DVP couché", "$sets", "$rBench RM", "DVP couché", P80, REST_SUPER, isSuperset = true, supersetGroup = 1),
            Exercise("Écarté poulie", "$sets", "$rFly RM", "Écarté poulie", P70, REST_SUPER, isSuperset = true, supersetGroup = 1),
            Exercise("Abdos en rotation", "$sets", "12 Reps", rest = REST_ABS),
            Exercise("Biceps / Deltoïde / Triceps", "$sets", "15 Reps", rest = REST_ARMS),
            Exercise("Cardio", "3", "3 min", rest = REST_CARDIO, annotation = "Vitesse max"),
        ),
    )

    private fun week(
        p: Int, w: Int, sets: Int,
        rSquat: Int, rRow: Int, rIncl: Int,
        rDead: Int, rHigh: Int, rLow: Int,
        rLunge: Int, rBench: Int, rFly: Int,
    ) = Week(
        index = w,
        label = "Semaine ${w + 1}",
        sessions = listOf(
            s1(p, w, sets, rSquat, rRow, rIncl),
            s2(p, w, sets, rDead, rHigh, rLow),
            s3(p, w, sets, rLunge, rBench, rFly),
        ),
    )

    fun build() = TrainingProgram(
        id = ID,
        name = "Musculation — Marvin",
        shortName = "Muscu",
        description = "Programme 12 semaines en 3 phases : technique, volume, force maximale.",
        phases = listOf(
            Phase(
                index = 0,
                title = "PHASE 1 — Technique",
                description = "Exécution technique / Amplitude / Tempo des répétitions",
                weeks = listOf(
                    week(0, 0, 3, 5, 8, 12, 5, 8, 8, 10, 8, 12),
                    week(0, 1, 3, 6, 9, 13, 6, 9, 9, 11, 9, 13),
                    week(0, 2, 3, 7, 10, 14, 7, 10, 10, 12, 10, 14),
                    week(0, 3, 3, 5, 8, 12, 5, 8, 8, 10, 8, 12),
                ),
            ),
            Phase(
                index = 1,
                title = "PHASE 2 — Volume",
                description = "Augmentation du volume = nombre de séries / garder la technique",
                weeks = listOf(
                    week(1, 0, 3, 5, 8, 12, 5, 8, 8, 10, 8, 12),
                    week(1, 1, 4, 5, 8, 12, 5, 8, 8, 10, 8, 12),
                    week(1, 2, 5, 5, 8, 12, 5, 8, 8, 10, 8, 12),
                    week(1, 3, 3, 5, 8, 12, 5, 8, 8, 10, 8, 12),
                ),
            ),
            Phase(
                index = 2,
                title = "PHASE 3 — Force max",
                description = "Conservation du volume + augmentation des charges via tes 1RM",
                weeks = listOf(
                    week(2, 0, 3, 5, 8, 12, 5, 8, 8, 10, 8, 12),
                    week(2, 1, 4, 5, 8, 12, 5, 8, 8, 10, 8, 12),
                    week(2, 2, 5, 5, 8, 12, 5, 8, 8, 10, 8, 12),
                    week(2, 3, 3, 5, 8, 12, 5, 8, 8, 10, 8, 12),
                ),
            ),
        ),
    )
}
