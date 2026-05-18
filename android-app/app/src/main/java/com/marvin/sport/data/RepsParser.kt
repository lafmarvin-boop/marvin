package com.marvin.sport.data

/**
 * Détecte si une chaîne "reps" décrit un effort temporel (planche 30 s,
 * cardio 1 min, dead-hang 45 s, intervalle 30 s ON / 30 s OFF…) et
 * renvoie la durée d'une rep en secondes. Si la rep est en nombre de
 * répétitions ("12 reps", "5 par côté"), renvoie `null` — l'utilisateur
 * valide alors manuellement.
 */
object RepsParser {

    fun toSeconds(text: String): Int? {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return null
        // Si on trouve un nombre suivi de "Reps" / "reps" / "rep", c'est un comptage de répétitions
        if (Regex("\\d+\\s*[Rr]eps?\\b").containsMatchIn(cleaned)) return null
        // Cas particulier "par côté/jambe/main" sans "s" → comptage
        if (Regex("\\d+\\s*par\\s+(côté|jambe|main)", RegexOption.IGNORE_CASE).containsMatchIn(cleaned)
            && !Regex("\\d+\\s*s\\b").containsMatchIn(cleaned)
        ) return null

        // Minutes
        val minMatch = Regex("(\\d+)(?:\\s*[-–à]\\s*(\\d+))?\\s*min").find(cleaned)
        if (minMatch != null) {
            val low = minMatch.groupValues[1].toIntOrNull() ?: 1
            val high = minMatch.groupValues[2].toIntOrNull() ?: low
            return ((low + high) / 2.0).toInt() * 60
        }
        // Secondes — on borne avec \b pour éviter "set" etc.
        val secMatch = Regex("(\\d+)(?:\\s*[-–à]\\s*(\\d+))?\\s*s\\b").find(cleaned)
        if (secMatch != null) {
            val low = secMatch.groupValues[1].toIntOrNull() ?: 30
            val high = secMatch.groupValues[2].toIntOrNull() ?: low
            return ((low + high) / 2.0).toInt()
        }
        return null
    }
}
