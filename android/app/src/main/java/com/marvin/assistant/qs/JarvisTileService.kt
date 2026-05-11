package com.marvin.assistant.qs

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.marvin.assistant.service.AssistantService
import com.marvin.assistant.ui.DiscussionActivity

/**
 * Quick Settings Tile « Jarvis » : tap dans le panneau de notifications
 * (ou l'écran de verrouillage avec la tuile ajoutée) → lance directement
 * une interaction.
 *
 * Plus rapide que de dire le wake word : utile en réunion silencieuse,
 * sur lock screen, ou comme raccourci.
 *
 * Pour l'activer : tire le panneau Quick Settings, mode édition,
 * ajoute la tuile « Jarvis ».
 */
class JarvisTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = "Jarvis"
            updateTile()
        }
    }

    override fun onClick() {
        // Démarre le service Marvin et ouvre l'écran d'interaction
        AssistantService.start(applicationContext)
        val intent = Intent(this, DiscussionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // startActivityAndCollapse pour fermer le panneau QS
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0, intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
