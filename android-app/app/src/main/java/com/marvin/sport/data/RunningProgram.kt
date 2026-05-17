package com.marvin.sport.data

/**
 * Modèle léger pour les séances de course à pied structurées.
 *   - "Z2" / endurance fondamentale → travail aérobie de base
 *   - "Intervalles" → équivalent ratio combat (round / récup)
 *   - "Tempo" → seuil, capacité à soutenir une intensité élevée
 *
 * Toutes les séances sont calibrées pour rester ≤ 10 km, adaptées aux
 * athlètes grappling / boxe / MMA (priorité au cardio explosif et au
 * conditionnement aérobie-anaérobie mixte).
 */

data class RunBlock(
    val label: String,
    val durationMin: Int? = null,
    val distanceM: Int? = null,
    val intensity: String,
    val repeat: Int = 1,
) {
    /** Durée totale du bloc en secondes pour le suivi temps réel. */
    val trackingDurationSec: Int
        get() = when {
            durationMin != null -> durationMin * 60 * repeat.coerceAtLeast(1)
            distanceM != null -> 0
            else -> repeat * 60
        }

    val trackingDistanceM: Int get() = distanceM ?: 0

    val isDistanceBased: Boolean get() = distanceM != null
}

data class RunSession(
    val id: String,
    val weekIndex: Int,
    val sessionIndex: Int,
    val title: String,
    val type: String,
    val targetKm: Double,
    val description: String,
    val blocks: List<RunBlock>,
)

data class RunWeek(val index: Int, val label: String, val sessions: List<RunSession>)

data class RunningProgram(
    val name: String,
    val description: String,
    val weeks: List<RunWeek>,
)

object RunningProgramBuilder {

    private fun week1() = RunWeek(
        index = 0,
        label = "Semaine 1 — Base",
        sessions = listOf(
            RunSession(
                id = "run_W1S1",
                weekIndex = 0, sessionIndex = 0,
                title = "Endurance fondamentale",
                type = "Z2",
                targetKm = 5.0,
                description = "Allure conversation, rythme cardiaque bas. Construit la base aérobie.",
                blocks = listOf(
                    RunBlock("Échauffement", durationMin = 8, intensity = "Marche rapide → footing"),
                    RunBlock("Z2", durationMin = 25, intensity = "Conversation possible"),
                    RunBlock("Retour au calme", durationMin = 5, intensity = "Marche"),
                ),
            ),
            RunSession(
                id = "run_W1S2",
                weekIndex = 0, sessionIndex = 1,
                title = "Intervalles courts (rounds combat)",
                type = "Intervalles",
                targetKm = 5.0,
                description = "Ratio 1:1 calqué sur un round MMA / boxe. Vitesse contrôlée mais soutenue.",
                blocks = listOf(
                    RunBlock("Échauffement footing", durationMin = 10, intensity = "Z2"),
                    RunBlock("Sprint 30 s / récup 30 s", durationMin = null, intensity = "Sprint puis trot", repeat = 10),
                    RunBlock("Retour au calme", durationMin = 8, intensity = "Marche → étirements"),
                ),
            ),
            RunSession(
                id = "run_W1S3",
                weekIndex = 0, sessionIndex = 2,
                title = "Sortie longue contrôlée",
                type = "Long Z2",
                targetKm = 7.0,
                description = "Volume aérobie. Souffle stable du début à la fin.",
                blocks = listOf(
                    RunBlock("Échauffement", durationMin = 5, intensity = "Marche rapide"),
                    RunBlock("Footing continu Z2", durationMin = 40, intensity = "Conversation possible"),
                    RunBlock("Étirements", durationMin = 5, intensity = "Statique"),
                ),
            ),
        ),
    )

    private fun week2() = RunWeek(
        index = 1,
        label = "Semaine 2 — Spécifique combat",
        sessions = listOf(
            RunSession(
                id = "run_W2S1",
                weekIndex = 1, sessionIndex = 0,
                title = "Endurance + foulées",
                type = "Z2 + accélérations",
                targetKm = 6.0,
                description = "Z2 ponctué de courtes accélérations pour réveiller la puissance.",
                blocks = listOf(
                    RunBlock("Échauffement", durationMin = 10, intensity = "Z2"),
                    RunBlock("Z2 + accélération 100 m", durationMin = null, intensity = "Z2 puis sprint fluide", repeat = 6),
                    RunBlock("Retour au calme", durationMin = 5, intensity = "Marche"),
                ),
            ),
            RunSession(
                id = "run_W2S2",
                weekIndex = 1, sessionIndex = 1,
                title = "Rounds 3x3 min",
                type = "Intervalles longs",
                targetKm = 6.0,
                description = "3 blocs de 3 min vitesse combat, 1 min récup. Reproduit un combat MMA 3 rounds.",
                blocks = listOf(
                    RunBlock("Échauffement footing", durationMin = 12, intensity = "Z2"),
                    RunBlock("3 min vitesse soutenue / 1 min trot", durationMin = null, intensity = "Tempo soutenu", repeat = 3),
                    RunBlock("Retour au calme", durationMin = 8, intensity = "Marche"),
                ),
            ),
            RunSession(
                id = "run_W2S3",
                weekIndex = 1, sessionIndex = 2,
                title = "Sortie longue",
                type = "Long Z2",
                targetKm = 8.0,
                description = "Volume hebdo, allure régulière. Limite respiratoire.",
                blocks = listOf(
                    RunBlock("Échauffement", durationMin = 5, intensity = "Marche → footing"),
                    RunBlock("Footing Z2", durationMin = 45, intensity = "Conversation possible"),
                    RunBlock("Étirements", durationMin = 5, intensity = "Statique"),
                ),
            ),
        ),
    )

    private fun week3() = RunWeek(
        index = 2,
        label = "Semaine 3 — Intensité",
        sessions = listOf(
            RunSession(
                id = "run_W3S1",
                weekIndex = 2, sessionIndex = 0,
                title = "Tempo run",
                type = "Tempo",
                targetKm = 6.0,
                description = "Maintenir une allure inconfortable mais tenable. Améliore le seuil.",
                blocks = listOf(
                    RunBlock("Échauffement", durationMin = 12, intensity = "Z2"),
                    RunBlock("Tempo seuil", durationMin = 20, intensity = "Respiration courte"),
                    RunBlock("Retour au calme", durationMin = 8, intensity = "Marche"),
                ),
            ),
            RunSession(
                id = "run_W3S2",
                weekIndex = 2, sessionIndex = 1,
                title = "VMA courtes 30/30",
                type = "Intervalles courts",
                targetKm = 5.0,
                description = "12 × 30 s vite / 30 s lent. Spécifique combat (ratio round explosif).",
                blocks = listOf(
                    RunBlock("Échauffement footing", durationMin = 12, intensity = "Z2"),
                    RunBlock("30 s sprint / 30 s trot", durationMin = null, intensity = "Sprint", repeat = 12),
                    RunBlock("Retour au calme", durationMin = 8, intensity = "Marche → étirements"),
                ),
            ),
            RunSession(
                id = "run_W3S3",
                weekIndex = 2, sessionIndex = 2,
                title = "Long progressif",
                type = "Long progressif",
                targetKm = 10.0,
                description = "10 km en accélération progressive. Limite haute du programme.",
                blocks = listOf(
                    RunBlock("Échauffement", durationMin = 5, intensity = "Marche → footing"),
                    RunBlock("Z2 → Tempo → Sprint final", durationMin = 55, intensity = "Progressif"),
                    RunBlock("Retour au calme", durationMin = 8, intensity = "Marche"),
                ),
            ),
        ),
    )

    val program = RunningProgram(
        name = "Course combat — 3 semaines",
        description = "Plan running adapté grappling / boxe / MMA : aérobie, ratios combat, VMA. Max 10 km, 3 séances par semaine.",
        weeks = listOf(week1(), week2(), week3()),
    )
}
