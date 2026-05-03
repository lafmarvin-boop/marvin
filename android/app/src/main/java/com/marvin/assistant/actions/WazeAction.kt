package com.marvin.assistant.actions

import android.content.Context
import android.content.Intent
import android.net.Uri

class WazeAction(private val context: Context) {

    fun navigate(destination: String): String {
        val url = "https://waze.com/ul?q=" + Uri.encode(destination) + "&navigate=yes"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setPackage("com.waze")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "Direction $destination."
        } catch (t: Throwable) {
            // Fallback: navigation générique (Maps)
            val geo = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("google.navigation:q=" + Uri.encode(destination))
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(geo)
                "Waze indisponible, j'ouvre Maps vers $destination."
            } catch (_: Throwable) {
                "Pas d'app de navigation installée."
            }
        }
    }
}
