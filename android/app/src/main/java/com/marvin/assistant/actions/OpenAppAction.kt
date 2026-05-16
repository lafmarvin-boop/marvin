package com.marvin.assistant.actions

import android.content.Context
import android.content.Intent

/**
 * Lance n'importe quelle app installée sur le téléphone.
 *
 * Stratégie :
 *  1. Cherche d'abord dans le mapping hardcodé (alias rapides pour les apps
 *     courantes, plus rapide que de scanner).
 *  2. Sinon délègue à [AppCatalog] qui fait du fuzzy matching sur le label
 *     de TOUTES les apps installées.
 *
 * Du coup l'utilisateur peut dire « lance Discord », « ouvre Photos »,
 * « démarre Netflix », « ouvre Téléphone » — n'importe quoi, tant que
 * l'app est installée sur le téléphone.
 */
class OpenAppAction(private val context: Context) {

    private val catalog = AppCatalog(context)

    /** Mapping rapide pour les noms ambigus / courants. */
    private val packageMap = mapOf(
        "spotify" to "com.spotify.music",
        "whatsapp" to "com.whatsapp",
        "waze" to "com.waze",
        "familywall" to "com.familywall",
        "boursobank" to "com.boursorama.android.clients",
        "banque pop" to "fr.banquepopulaire.cyberplus",
        "banque populaire" to "fr.banquepopulaire.cyberplus",
        "ecovacs" to "com.eco.global.app",
        "ecovacs home" to "com.eco.global.app",
        "météo" to "com.samsung.android.weather",
        "meteo" to "com.samsung.android.weather",
        "strava" to "com.strava"
    )

    fun open(appKey: String): String {
        val cleaned = appKey.trim()
        // 1. Mapping hardcodé
        val pkg = packageMap[cleaned.lowercase()]
        if (pkg != null) {
            return launch(pkg, cleaned)
        }
        // 2. Fuzzy match dans toutes les apps installées
        val app = catalog.find(cleaned)
            ?: return "Je n'ai pas trouvé d'application correspondant à « $cleaned »."
        return launch(app.packageName, app.label)
    }

    private fun launch(packageName: String, label: String): String {
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return "L'app « $label » n'est pas installée."
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launch)
            "J'ouvre $label."
        } catch (t: Throwable) {
            "Impossible de lancer $label : ${t.message}"
        }
    }
}
