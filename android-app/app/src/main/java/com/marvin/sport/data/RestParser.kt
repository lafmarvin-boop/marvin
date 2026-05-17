package com.marvin.sport.data

/**
 * Convertit la chaîne "repos" d'un exercice en nombre de secondes utilisables
 * par le mode guidé. Heuristiques :
 *   - "60-90 s"  → moyenne 75 s
 *   - "2-4 min"  → moyenne 3 min = 180 s
 *   - "30 s"     → 30 s
 *   - "—" ou vide → 30 s (défaut HIIT raisonnable)
 */
object RestParser {

    fun toSeconds(text: String): Int {
        val cleaned = text.trim()
        if (cleaned.isEmpty() || cleaned == "—") return 30
        val minMatch = Regex("(\\d+)(?:\\s*[-–à]\\s*(\\d+))?\\s*min").find(cleaned)
        if (minMatch != null) {
            val low = minMatch.groupValues[1].toIntOrNull() ?: 1
            val high = minMatch.groupValues[2].toIntOrNull() ?: low
            return ((low + high) / 2.0).toInt() * 60
        }
        val secMatch = Regex("(\\d+)(?:\\s*[-–à]\\s*(\\d+))?\\s*s").find(cleaned)
        if (secMatch != null) {
            val low = secMatch.groupValues[1].toIntOrNull() ?: 30
            val high = secMatch.groupValues[2].toIntOrNull() ?: low
            return ((low + high) / 2.0).toInt()
        }
        return 30
    }

    fun toSeriesCount(setsText: String): Int =
        setsText.trim().toIntOrNull()?.coerceAtLeast(1) ?: 1
}
