package com.marvin.assistant.nlu

import java.util.Calendar

/**
 * Parser d'expressions temporelles françaises pour les rappels.
 *
 * Reconnaît :
 *  - "dans X minutes / heures / secondes" → relative
 *  - "à HH heures (MM)" → absolu aujourd'hui (ou demain si déjà passé)
 *  - "à HHhMM" / "à HH h MM"
 *  - "demain à HH heures"
 *  - "dans X minutes" en chiffres (10) ou en lettres (dix)
 *
 * Renvoie le timestamp ms ou null si pas de correspondance.
 */
object TimeParser {

    private val FRENCH_NUMBERS = mapOf(
        "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4,
        "cinq" to 5, "six" to 6, "sept" to 7, "huit" to 8, "neuf" to 9,
        "dix" to 10, "onze" to 11, "douze" to 12, "treize" to 13,
        "quatorze" to 14, "quinze" to 15, "seize" to 16, "vingt" to 20,
        "trente" to 30, "quarante" to 40, "cinquante" to 50, "soixante" to 60
    )

    /**
     * Renvoie le timestamp absolu (ms) déduit du texte, ou null si pas
     * d'expression temporelle reconnue.
     */
    fun parse(text: String, now: Long = System.currentTimeMillis()): Long? {
        val t = text.lowercase()

        // "dans X (sec|min|heure)"
        val relMatch = Regex(
            """dans\s+(\d+|[a-zéèêà]+)\s*(seconde|secondes|minute|minutes|heure|heures|h|min)\b"""
        ).find(t)
        if (relMatch != null) {
            val n = parseNumber(relMatch.groupValues[1]) ?: return null
            val unit = relMatch.groupValues[2]
            val ms = when {
                unit.startsWith("seconde") -> n * 1000L
                unit.startsWith("min") -> n * 60_000L
                unit.startsWith("heure") || unit == "h" -> n * 3_600_000L
                else -> return null
            }
            return now + ms
        }

        // "à HHhMM" ou "à HH h MM" ou "à HH heures (MM)"
        val absMatch = Regex(
            """(?:à |a |pour )?(\d{1,2})\s*(?:h|heure|heures)\s*(\d{0,2})"""
        ).find(t)
        if (absMatch != null) {
            val hour = absMatch.groupValues[1].toIntOrNull() ?: return null
            val minute = absMatch.groupValues[2].toIntOrNull() ?: 0
            if (hour !in 0..23 || minute !in 0..59) return null
            val cal = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            // Si "demain" présent, +1 jour
            if (t.contains("demain")) cal.add(Calendar.DAY_OF_YEAR, 1)
            // Sinon, si l'heure est déjà passée aujourd'hui, prend demain
            else if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
            return cal.timeInMillis
        }

        return null
    }

    private fun parseNumber(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        return FRENCH_NUMBERS[s.trim()]
    }
}
