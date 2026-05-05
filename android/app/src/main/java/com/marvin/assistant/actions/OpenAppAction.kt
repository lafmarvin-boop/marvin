package com.marvin.assistant.actions

import android.content.Context
import android.content.Intent

class OpenAppAction(private val context: Context) {

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
        "meteo" to "com.samsung.android.weather"
    )

    fun open(appKey: String): String {
        val pkg = packageMap[appKey.lowercase()] ?: return "Je ne connais pas l'app « $appKey »."
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return "L'app $appKey n'est pas installée."
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return "$appKey ouverte."
    }
}
