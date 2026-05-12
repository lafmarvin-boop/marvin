package com.marvin.sport.data

/**
 * Programme original "Musculation" — extrait du fichier Excel de Marvin.
 *
 * 1RM de référence :
 *   Back Squat 100 / Barbell Row 60 / DVP Incliné 70
 *   Deadlift 140 / Tirage poulie haute 70 / Tirage poulie basse 60
 *   Fente 50 / DVP couché 70 / Écarté poulie 18
 *
 * Coefficients en fonction des répétitions :
 *   5 reps → 85%   8 reps → 80%   10 reps → 75%
 *   12 reps → 70%  15 reps → 65%
 */
object StrengthProgramBuilder {

    private const val PROGRAM_ID = "strength"

    private const val SQUAT = 100.0
    private const val ROW = 60.0
    private const val INCLINE = 70.0
    private const val DEAD = 140.0
    private const val PULL_HIGH = 70.0
    private const val PULL_LOW = 60.0
    private const val LUNGE = 50.0
    private const val BENCH = 70.0
    private const val FLY = 18.0

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
        id = "${PROGRAM_ID}_P${p + 1}W${w + 1}S1",
        programId = PROGRAM_ID,
        phaseIndex = p, weekIndex = w, sessionIndex = 0,
        title = "Séance 1 — Bas du corps / Tirage",
        warmup = WARMUP_LONG,
        exercises = listOf(
            Exercise("Back Squat", "$sets", "$rSquat RM", SQUAT * P85, REST_MAIN),
            Exercise("Barbell Row", "$sets", "$rRow RM", ROW * P80, REST_SUPER, isSuperset = true, supersetGroup = 1),
            Exercise("DVP Incliné", "$sets", "$rIncl RM", INCLINE * P70, REST_SUPER, isSuperset = true, supersetGroup = 1),
            Exercise("Frappe latérale", "$sets", "5 par côté", rest = REST_ABS),
            Exercise("Biceps / Deltoïde / Triceps", "$sets", "15 Reps", rest = REST_ARMS),
            Exercise("Cardio", "3", "3 min", rest = REST_CARDIO, annotation = "Vitesse max"),
        ),
    )

    private fun s2(p: Int, w: Int, sets: Int, rDead: Int, rHigh: Int, rLow: Int) = Session(
        id = "${PROGRAM_ID}_P${p + 1}W${w + 1}S2",
        programId = PROGRAM_ID,
        phaseIndex = p, weekIndex = w, sessionIndex = 1,
        title = "Séance 2 — Dos / Posterior chain",
        warmup = WARMUP_HIGH,
        exercises = listOf(
            Exercise("Deadlift", "$sets", "$rDead RM", DEAD * P85, REST_MAIN),
            Exercise("Tirage poulie haute", "$sets", "$rHigh RM", PULL_HIGH * P80, REST_SUPER, isSuperset = true, supersetGroup = 1),
            Exercise("Tirage poulie basse", "$sets", "$rLow RM", PULL_LOW * P80, REST_SUPER, isSuperset = true, supersetGroup = 1),
            Exercise("Dragon flag", "$sets", "12 Reps", rest = REST_ABS),
            Exercise("Biceps / Deltoïde / Triceps", "$sets", "15 Reps", rest = REST_ARMS),
            Exercise("Cardio", "3", "3 min", rest = REST_CARDIO, annotation = "Vitesse max"),
        ),
    )

    private fun s3(p: Int, w: Int, sets: Int, rLunge: Int, rBench: Int, rFly: Int) = Session(
        id = "${PROGRAM_ID}_P${p + 1}W${w + 1}S3",
        programId = PROGRAM_ID,
        phaseIndex = p, weekIndex = w, sessionIndex = 2,
        title = "Séance 3 — Jambes unilatéral / Push",
        warmup = WARMUP_LONG,
        exercises = listOf(
            Exercise("Fente", "$sets", "$rLunge RM", LUNGE * P75, REST_MAIN),
            Exercise("DVP couché", "$sets", "$rBench RM", BENCH * P80, REST_SUPER, isSuperset = true, supersetGroup = 1),
            Exercise("Écarté poulie", "$sets", "$rFly RM", FLY * P70, REST_SUPER, isSuperset = true, supersetGroup = 1),
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

    private fun phase1() = Phase(
        index = 0,
        title = "PHASE 1 — Technique",
        description = "Exécution technique / Amplitude / Tempo des répétitions",
        weeks = listOf(
            week(0, 0, 3, 5, 8, 12, 5, 8, 8, 10, 8, 12),
            week(0, 1, 3, 6, 9, 13, 6, 9, 9, 11, 9, 13),
            week(0, 2, 3, 7, 10, 14, 7, 10, 10, 12, 10, 14),
            week(0, 3, 3, 5, 8, 12, 5, 8, 8, 10, 8, 12),
        ),
    )

    private fun phase2() = Phase(
        index = 1,
        title = "PHASE 2 — Volume",
        description = "Augmentation du volume = nombre de séries / garder la technique",
        weeks = listOf(
            week(1, 0, 3, 5, 8, 12, 5, 8, 8, 10, 8, 12),
            week(1, 1, 4, 5, 8, 12, 5, 8, 8, 10, 8, 12),
            week(1, 2, 5, 5, 8, 12, 5, 8, 8, 10, 8, 12),
            week(1, 3, 3, 5, 8, 12, 5, 8, 8, 10, 8, 12),
        ),
    )

    private fun phase3() = Phase(
        index = 2,
        title = "PHASE 3 — Force maximale",
        description = "Conservation du volume de la phase 2 et augmentation des charges",
        weeks = listOf(
            week(2, 0, 3, 5, 8, 12, 5, 8, 8, 10, 8, 12),
            week(2, 1, 4, 5, 8, 12, 5, 8, 8, 10, 8, 12),
            week(2, 2, 5, 5, 8, 12, 5, 8, 8, 10, 8, 12),
            week(2, 3, 3, 5, 8, 12, 5, 8, 8, 10, 8, 12),
        ),
    )

    fun build() = TrainingProgram(
        id = PROGRAM_ID,
        name = "Musculation — Marvin",
        shortName = "Muscu",
        description = "Programme 12 semaines en 3 phases : technique, volume, force maximale.",
        phases = listOf(phase1(), phase2(), phase3()),
    )
}
