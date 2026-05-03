package com.marvin.assistant.actions

import android.content.Context
import android.content.Intent
import com.marvin.assistant.nlu.EcovacsAction as EcovacsCmd

/**
 * Ecovacs Home n'a pas d'API publique. On ouvre l'app et on laisse
 * [com.marvin.assistant.service.MarvinAccessibilityService] cliquer sur
 * le bon bouton (start/pause/dock) une fois l'écran principal détecté.
 *
 * Tant que l'AccessibilityService n'est pas branché, on ne fait que lancer
 * l'app — c'est un stub explicite.
 */
class EcovacsAction(private val context: Context) {

    fun handle(command: EcovacsCmd): String {
        val pkg = "com.eco.global.app"
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return "Ecovacs Home n'est pas installée."
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return when (command) {
            EcovacsCmd.START -> "J'ouvre Ecovacs. Lance l'aspirateur depuis l'app."
            EcovacsCmd.PAUSE -> "J'ouvre Ecovacs. Mets en pause depuis l'app."
            EcovacsCmd.DOCK -> "J'ouvre Ecovacs. Renvoie au dock depuis l'app."
        }
    }
}
