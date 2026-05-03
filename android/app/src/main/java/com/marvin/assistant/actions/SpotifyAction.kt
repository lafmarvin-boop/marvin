package com.marvin.assistant.actions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import com.marvin.assistant.nlu.MarvinIntent

/**
 * Spotify control via media buttons (play/pause/next/prev) and search via
 * deep link `spotify:search:<query>`.
 *
 * Pas besoin de l'API Spotify officielle ici: les media buttons fonctionnent
 * dès que Spotify est installé et que la session media est active.
 */
class SpotifyAction(private val context: Context) {

    fun handle(intent: MarvinIntent.Spotify): String = when (intent) {
        MarvinIntent.Spotify.Play -> {
            ensureSpotifyForeground()
            sendKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            "Musique."
        }
        MarvinIntent.Spotify.Pause -> { sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE); "Pause." }
        MarvinIntent.Spotify.Next -> { sendKey(KeyEvent.KEYCODE_MEDIA_NEXT); "Suivant." }
        MarvinIntent.Spotify.Previous -> { sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); "Précédent." }
        is MarvinIntent.Spotify.Search -> search(intent.query)
    }

    private fun search(query: String): String {
        val uri = Uri.parse("spotify:search:" + Uri.encode(query))
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage("com.spotify.music")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "Je cherche $query sur Spotify."
        } catch (t: Throwable) {
            "Spotify n'est pas installé."
        }
    }

    private fun ensureSpotifyForeground() {
        val launch = context.packageManager.getLaunchIntentForPackage("com.spotify.music")
        launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
    }

    private fun sendKey(keyCode: Int) {
        val down = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(
            Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        ).setComponent(ComponentName("com.spotify.music", "com.spotify.music.MediaButtonReceiver"))
        val up = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(
            Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, keyCode)
        ).setComponent(ComponentName("com.spotify.music", "com.spotify.music.MediaButtonReceiver"))
        try {
            context.sendBroadcast(down)
            context.sendBroadcast(up)
        } catch (_: Throwable) {
            // Fallback: global media-button broadcast
            context.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(
                Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            ))
        }
    }
}
