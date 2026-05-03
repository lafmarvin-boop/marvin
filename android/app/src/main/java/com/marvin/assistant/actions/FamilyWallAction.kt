package com.marvin.assistant.actions

import android.content.Context
import android.content.Intent

/**
 * FamilyWall has no public API. For l'instant on se contente d'ouvrir l'app
 * sur l'écran de la carte des localisations. Une vraie automatisation (lire
 * les positions des membres et les annoncer) demandera d'enrichir
 * [com.marvin.assistant.service.MarvinAccessibilityService].
 */
class FamilyWallAction(private val context: Context) {

    fun showLocations(): String {
        val pkg = "com.familywall"
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return "FamilyWall n'est pas installée."
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return "J'ouvre FamilyWall."
    }
}
