package com.marvin.assistant.nlu

/**
 * Parser de calculs arithmétiques en français.
 *
 * Reconnaît les expressions vocales typiques :
 *  - "combien font 47 fois 23"
 *  - "calcule 12 plus 8"
 *  - "1500 divisé par 12"
 *  - "racine carrée de 144"
 *  - "12 puissance 3"
 *  - "10 % de 250"
 *
 * Évite l'aller-retour Claude pour les calculs simples — réponse instantanée
 * et zéro coût d'API. Si l'expression n'est pas reconnue, on tombe sur le
 * LLM via Unknown.
 */
object CalcParser {

    private val OPERATOR_WORDS = mapOf(
        "plus" to "+", "moins" to "-",
        "fois" to "*", "multiplié par" to "*", "multiplier par" to "*",
        "divisé par" to "/", "diviser par" to "/",
        "modulo" to "%", "mod" to "%",
        "puissance" to "^", "à la puissance" to "^", "exposant" to "^"
    )

    private val FRENCH_NUMBERS = mapOf(
        "zéro" to 0, "un" to 1, "une" to 1, "deux" to 2, "trois" to 3,
        "quatre" to 4, "cinq" to 5, "six" to 6, "sept" to 7, "huit" to 8,
        "neuf" to 9, "dix" to 10, "onze" to 11, "douze" to 12,
        "treize" to 13, "quatorze" to 14, "quinze" to 15, "seize" to 16,
        "vingt" to 20, "trente" to 30, "quarante" to 40, "cinquante" to 50,
        "soixante" to 60, "cent" to 100, "mille" to 1000
    )

    /**
     * Tente de calculer le résultat d'une phrase. Renvoie le texte de la
     * réponse formaté pour TTS, ou null si pas reconnu comme calcul.
     */
    fun tryCompute(text: String): String? {
        val t = text.lowercase().trim()

        // Strip les préfixes "combien font" / "calcule" / "que vaut" etc.
        val cleaned = t
            .replace(Regex("""^(combien (?:font|fait|vaut|valent)|calcule(?:r)?|que vaut|c'est combien)\s+"""), "")
            .replace(Regex("""\?+\s*$"""), "")
            .trim()

        // Cas spéciaux
        // racine carrée de N
        Regex("""racine carrée de (\d+(?:[.,]\d+)?)""").find(cleaned)?.let { m ->
            val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            if (n < 0) return "La racine carrée d'un nombre négatif n'existe pas."
            return "La racine carrée de ${formatNum(n)} est ${formatNum(kotlin.math.sqrt(n))}."
        }
        // X % de Y
        Regex("""(\d+(?:[.,]\d+)?)\s*(?:%|pour\s*cent|pourcent) de (\d+(?:[.,]\d+)?)""").find(cleaned)?.let { m ->
            val pct = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val of = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
            return "${formatNum(pct)} pour cent de ${formatNum(of)} font ${formatNum(pct * of / 100)}."
        }
        // X puissance Y / X exposant Y
        Regex("""(\d+(?:[.,]\d+)?)\s*(?:puissance|à la puissance|exposant)\s*(\d+(?:[.,]\d+)?)""").find(cleaned)?.let { m ->
            val a = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val b = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
            return "${formatNum(a)} puissance ${formatNum(b)} font ${formatNum(Math.pow(a, b))}."
        }

        // Opération binaire simple : N1 OP N2
        val binary = Regex(
            """(-?\d+(?:[.,]\d+)?|[a-zéèêà -]+?)\s+(plus|moins|fois|multiplié par|multiplier par|divisé par|diviser par|modulo|mod)\s+(-?\d+(?:[.,]\d+)?|[a-zéèêà -]+)$"""
        ).find(cleaned) ?: return null

        val a = parseNumber(binary.groupValues[1]) ?: return null
        val opWord = binary.groupValues[2]
        val b = parseNumber(binary.groupValues[3]) ?: return null
        val op = OPERATOR_WORDS[opWord] ?: return null
        val result = when (op) {
            "+" -> a + b
            "-" -> a - b
            "*" -> a * b
            "/" -> if (b == 0.0) return "On ne divise pas par zéro."
                else a / b
            "%" -> if (b == 0.0) return "On ne fait pas de modulo par zéro."
                else a % b
            else -> return null
        }
        val opSpoken = when (op) {
            "+" -> "plus"; "-" -> "moins"; "*" -> "fois"; "/" -> "divisé par"; "%" -> "modulo"
            else -> op
        }
        return "${formatNum(a)} $opSpoken ${formatNum(b)} font ${formatNum(result)}."
    }

    private fun parseNumber(raw: String): Double? {
        val clean = raw.trim().replace(',', '.')
        clean.toDoubleOrNull()?.let { return it }
        // Tentative basique sur les nombres en lettres : juste les valeurs
        // simples du dictionnaire (on ne gère pas "vingt-trois" → 23 pour
        // l'instant, c'est rare en parlé).
        return FRENCH_NUMBERS[clean]?.toDouble()
    }

    private fun formatNum(n: Double): String {
        // Si c'est un entier, pas de décimales. Sinon 2 max.
        return if (n == n.toLong().toDouble()) n.toLong().toString()
        else "%.2f".format(n).trimEnd('0').trimEnd('.', ',')
    }
}
