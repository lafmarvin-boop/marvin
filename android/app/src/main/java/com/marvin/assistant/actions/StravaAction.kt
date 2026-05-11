package com.marvin.assistant.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Lance l'app Strava en mode enregistrement avec le sport souhaité.
 *
 * Strava expose un schéma deep link "strava://record" qui ouvre
 * l'écran d'enregistrement. Le paramètre `?type=` est interprété par
 * l'app (versions récentes) pour pré-sélectionner le sport.
 *
 * Pour les sports non reconnus par le deep link, on fallback sur le
 * lancement simple de l'app (l'utilisateur choisit manuellement).
 */
class StravaAction(private val context: Context) {

    fun start(sport: String?): String {
        val type = sport?.let { mapSport(it) }
        val packageName = "com.strava"
        return try {
            if (type != null) {
                // Tentative deep link avec type
                val uri = Uri.parse("strava://record?type=$type")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Strava lancé en mode ${sport ?: "auto"}."
            } else {
                // Fallback : juste ouvrir Strava
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                    ?: return "Strava n'est pas installé."
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Strava ouvert. Choisis ton sport."
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Strava deep link failed, fallback launch", t)
            // Fallback final : launch intent simple
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    "Strava ouvert."
                } else {
                    "Strava n'est pas installé."
                }
            } catch (t2: Throwable) {
                "Impossible de lancer Strava : ${t2.message}"
            }
        }
    }

    /**
     * Mappe un sport français à un type Strava (cf. strava.com/api
     * activity types). Renvoie null si pas de mapping (l'utilisateur
     * choisira dans l'app).
     */
    private fun mapSport(raw: String): String? {
        val s = raw.lowercase().trim()
        return when {
            // Course
            s.contains("course") && s.contains("pied") -> "Run"
            s.contains("trail") -> "TrailRun"
            s.contains("running") || s == "run" || s.matches(Regex(".*\\bcourse\\b.*")) -> "Run"
            s.contains("marche") && s.contains("rapide") -> "Walk"
            s.contains("marche") || s == "walk" -> "Walk"
            s.contains("randonn") || s.contains("rando") || s == "hike" -> "Hike"
            // Vélo
            s.contains("vtt") || s.contains("vélo de montagne") || s.contains("mountain bike") -> "MountainBikeRide"
            s.contains("gravel") -> "GravelRide"
            s.contains("vélo") && s.contains("route") -> "Ride"
            s.contains("vélo") && s.contains("électrique") -> "EBikeRide"
            s.contains("vélo") || s == "ride" || s.contains("cyclisme") -> "Ride"
            // Natation
            s.contains("natation") && s.contains("eau libre") -> "Swim"
            s.contains("natation") || s == "swim" -> "Swim"
            // Sports d'hiver
            s.contains("ski") && s.contains("fond") -> "NordicSki"
            s.contains("ski") && s.contains("rando") -> "BackcountrySki"
            s.contains("ski") -> "AlpineSki"
            s.contains("snowboard") -> "Snowboard"
            s.contains("raquette") || s.contains("snowshoe") -> "Snowshoe"
            // Salle
            s.contains("yoga") -> "Yoga"
            s.contains("muscu") || s.contains("musculation") || s.contains("weight") -> "WeightTraining"
            s.contains("crossfit") -> "Crossfit"
            s.contains("escalade") || s.contains("climbing") -> "RockClimbing"
            s.contains("workout") || s.contains("entrainement") || s.contains("entraînement") -> "Workout"
            s.contains("elliptique") || s.contains("elliptical") -> "Elliptical"
            s.contains("rameur") || s.contains("rowing") -> "Rowing"
            // Rame / pagaie
            s.contains("kayak") -> "Kayaking"
            s.contains("paddle") || s.contains("sup") -> "StandUpPaddling"
            s.contains("surf") -> "Surfing"
            // Patin
            s.contains("patin") && s.contains("glace") -> "IceSkate"
            s.contains("roller") || s.contains("inline") -> "InlineSkate"
            // Autre
            s.contains("skate") -> "Skateboard"
            else -> null
        }
    }

    companion object { private const val TAG = "StravaAction" }
}
