package com.marvin.sport.data

/**
 * Programme d'entraînement Marvin — extrait du fichier Excel d'origine.
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
object Program {

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
    private const val P65 = 0.65

    private const val REST_MAIN = "2-4 min repos"
    private const val REST_SUPER = "30-90s repos après superset"
    private const val REST_ABS = "30-60s repos après 2 séries"
    private const val REST_ARMS = "30-90s repos après chaque exo"
    private const val REST_CARDIO = "1 min repos"

    private const val WARMUP_LONG = "Mobilité / échauffement / 3x10 sauts longueur"
    private const val WARMUP_HIGH = "Mobilité / échauffement / 3x10 sauts hauteur"

    /** Construit la séance type pour une semaine de phase (sets, reps configurables). */
    private fun buildSession1(
        phaseIndex: Int,
        weekIndex: Int,
        sets: Int,
        repsSquat: Int,
        repsRow: Int,
        repsIncline: Int,
    ): Session = Session(
        id = "P${phaseIndex + 1}W${weekIndex + 1}S1",
        phaseIndex = phaseIndex,
        weekIndex = weekIndex,
        sessionIndex = 0,
        title = "Séance 1 — Bas du corps / Tirage",
        warmup = WARMUP_LONG,
        exercises = listOf(
            Exercise(
                name = "Back Squat",
                sets = "$sets",
                reps = "$repsSquat RM",
                baseLoadKg = SQUAT * P85,
                rest = REST_MAIN,
            ),
            Exercise(
                name = "Barbell Row",
                sets = "$sets",
                reps = "$repsRow RM",
                baseLoadKg = ROW * P80,
                rest = REST_SUPER,
                isSuperset = true,
                supersetGroup = 1,
            ),
            Exercise(
                name = "DVP Incliné",
                sets = "$sets",
                reps = "$repsIncline RM",
                baseLoadKg = INCLINE * P70,
                rest = REST_SUPER,
                isSuperset = true,
                supersetGroup = 1,
            ),
            Exercise(
                name = "Frappe latérale",
                sets = "$sets",
                reps = "5 par côté",
                rest = REST_ABS,
            ),
            Exercise(
                name = "Biceps / Deltoïde / Triceps",
                sets = "$sets",
                reps = "15 Reps",
                rest = REST_ARMS,
            ),
            Exercise(
                name = "Cardio",
                sets = "3",
                reps = "3 min",
                rest = REST_CARDIO,
                annotation = "Vitesse max",
            ),
        ),
    )

    private fun buildSession2(
        phaseIndex: Int,
        weekIndex: Int,
        sets: Int,
        repsDead: Int,
        repsPullHigh: Int,
        repsPullLow: Int,
    ): Session = Session(
        id = "P${phaseIndex + 1}W${weekIndex + 1}S2",
        phaseIndex = phaseIndex,
        weekIndex = weekIndex,
        sessionIndex = 1,
        title = "Séance 2 — Dos / Posterior chain",
        warmup = WARMUP_HIGH,
        exercises = listOf(
            Exercise(
                name = "Deadlift",
                sets = "$sets",
                reps = "$repsDead RM",
                baseLoadKg = DEAD * P85,
                rest = REST_MAIN,
            ),
            Exercise(
                name = "Tirage poulie haute",
                sets = "$sets",
                reps = "$repsPullHigh RM",
                baseLoadKg = PULL_HIGH * P80,
                rest = REST_SUPER,
                isSuperset = true,
                supersetGroup = 1,
            ),
            Exercise(
                name = "Tirage poulie basse",
                sets = "$sets",
                reps = "$repsPullLow RM",
                baseLoadKg = PULL_LOW * P80,
                rest = REST_SUPER,
                isSuperset = true,
                supersetGroup = 1,
            ),
            Exercise(
                name = "Dragon flag",
                sets = "$sets",
                reps = "12 Reps",
                rest = REST_ABS,
            ),
            Exercise(
                name = "Biceps / Deltoïde / Triceps",
                sets = "$sets",
                reps = "15 Reps",
                rest = REST_ARMS,
            ),
            Exercise(
                name = "Cardio",
                sets = "3",
                reps = "3 min",
                rest = REST_CARDIO,
                annotation = "Vitesse max",
            ),
        ),
    )

    private fun buildSession3(
        phaseIndex: Int,
        weekIndex: Int,
        sets: Int,
        repsLunge: Int,
        repsBench: Int,
        repsFly: Int,
    ): Session = Session(
        id = "P${phaseIndex + 1}W${weekIndex + 1}S3",
        phaseIndex = phaseIndex,
        weekIndex = weekIndex,
        sessionIndex = 2,
        title = "Séance 3 — Jambes unilatéral / Push",
        warmup = WARMUP_LONG,
        exercises = listOf(
            Exercise(
                name = "Fente",
                sets = "$sets",
                reps = "$repsLunge RM",
                baseLoadKg = LUNGE * P75,
                rest = REST_MAIN,
            ),
            Exercise(
                name = "DVP couché",
                sets = "$sets",
                reps = "$repsBench RM",
                baseLoadKg = BENCH * P80,
                rest = REST_SUPER,
                isSuperset = true,
                supersetGroup = 1,
            ),
            Exercise(
                name = "Écarté poulie",
                sets = "$sets",
                reps = "$repsFly RM",
                baseLoadKg = FLY * P70,
                rest = REST_SUPER,
                isSuperset = true,
                supersetGroup = 1,
            ),
            Exercise(
                name = "Abdos en rotation",
                sets = "$sets",
                reps = "12 Reps",
                rest = REST_ABS,
            ),
            Exercise(
                name = "Biceps / Deltoïde / Triceps",
                sets = "$sets",
                reps = "15 Reps",
                rest = REST_ARMS,
            ),
            Exercise(
                name = "Cardio",
                sets = "3",
                reps = "3 min",
                rest = REST_CARDIO,
                annotation = "Vitesse max",
            ),
        ),
    )

    private fun buildWeek(
        phaseIndex: Int,
        weekIndex: Int,
        sets: Int,
        repsSquat: Int,
        repsRow: Int,
        repsIncline: Int,
        repsDead: Int,
        repsPullHigh: Int,
        repsPullLow: Int,
        repsLunge: Int,
        repsBench: Int,
        repsFly: Int,
    ): Week = Week(
        index = weekIndex,
        label = "Semaine ${weekIndex + 1}",
        sessions = listOf(
            buildSession1(phaseIndex, weekIndex, sets, repsSquat, repsRow, repsIncline),
            buildSession2(phaseIndex, weekIndex, sets, repsDead, repsPullHigh, repsPullLow),
            buildSession3(phaseIndex, weekIndex, sets, repsLunge, repsBench, repsFly),
        ),
    )

    private fun buildPhase1(): Phase = Phase(
        index = 0,
        title = "PHASE 1 — Technique",
        description = "Exécution technique / Amplitude de mouvement / Tempo des répétitions",
        weeks = listOf(
            // Semaine 1 : reps de base
            buildWeek(0, 0, sets = 3,
                repsSquat = 5, repsRow = 8, repsIncline = 12,
                repsDead = 5, repsPullHigh = 8, repsPullLow = 8,
                repsLunge = 10, repsBench = 8, repsFly = 12),
            // Semaine 2 : +1 rep
            buildWeek(0, 1, sets = 3,
                repsSquat = 6, repsRow = 9, repsIncline = 13,
                repsDead = 6, repsPullHigh = 9, repsPullLow = 9,
                repsLunge = 11, repsBench = 9, repsFly = 13),
            // Semaine 3 : +2 reps
            buildWeek(0, 2, sets = 3,
                repsSquat = 7, repsRow = 10, repsIncline = 14,
                repsDead = 7, repsPullHigh = 10, repsPullLow = 10,
                repsLunge = 12, repsBench = 10, repsFly = 14),
            // Semaine 4 : deload
            buildWeek(0, 3, sets = 3,
                repsSquat = 5, repsRow = 8, repsIncline = 12,
                repsDead = 5, repsPullHigh = 8, repsPullLow = 8,
                repsLunge = 10, repsBench = 8, repsFly = 12),
        ),
    )

    private fun buildPhase2(): Phase = Phase(
        index = 1,
        title = "PHASE 2 — Volume",
        description = "Augmentation du volume = Nombre de séries / Garder la technique / Conserver ou légèrement augmenter les poids",
        weeks = listOf(
            buildWeek(1, 0, sets = 3,
                repsSquat = 5, repsRow = 8, repsIncline = 12,
                repsDead = 5, repsPullHigh = 8, repsPullLow = 8,
                repsLunge = 10, repsBench = 8, repsFly = 12),
            buildWeek(1, 1, sets = 4,
                repsSquat = 5, repsRow = 8, repsIncline = 12,
                repsDead = 5, repsPullHigh = 8, repsPullLow = 8,
                repsLunge = 10, repsBench = 8, repsFly = 12),
            buildWeek(1, 2, sets = 5,
                repsSquat = 5, repsRow = 8, repsIncline = 12,
                repsDead = 5, repsPullHigh = 8, repsPullLow = 8,
                repsLunge = 10, repsBench = 8, repsFly = 12),
            buildWeek(1, 3, sets = 3,
                repsSquat = 5, repsRow = 8, repsIncline = 12,
                repsDead = 5, repsPullHigh = 8, repsPullLow = 8,
                repsLunge = 10, repsBench = 8, repsFly = 12),
        ),
    )

    private fun buildPhase3(): Phase = Phase(
        index = 2,
        title = "PHASE 3 — Force maximale",
        description = "Conservation du volume de la phase 2 et augmentation des charges = Augmentation de la force maximale",
        weeks = listOf(
            buildWeek(2, 0, sets = 3,
                repsSquat = 5, repsRow = 8, repsIncline = 12,
                repsDead = 5, repsPullHigh = 8, repsPullLow = 8,
                repsLunge = 10, repsBench = 8, repsFly = 12),
            buildWeek(2, 1, sets = 4,
                repsSquat = 5, repsRow = 8, repsIncline = 12,
                repsDead = 5, repsPullHigh = 8, repsPullLow = 8,
                repsLunge = 10, repsBench = 8, repsFly = 12),
            buildWeek(2, 2, sets = 5,
                repsSquat = 5, repsRow = 8, repsIncline = 12,
                repsDead = 5, repsPullHigh = 8, repsPullLow = 8,
                repsLunge = 10, repsBench = 8, repsFly = 12),
            buildWeek(2, 3, sets = 3,
                repsSquat = 5, repsRow = 8, repsIncline = 12,
                repsDead = 5, repsPullHigh = 8, repsPullLow = 8,
                repsLunge = 10, repsBench = 8, repsFly = 12),
        ),
    )

    val program: TrainingProgram = TrainingProgram(
        phases = listOf(buildPhase1(), buildPhase2(), buildPhase3()),
    )
}
