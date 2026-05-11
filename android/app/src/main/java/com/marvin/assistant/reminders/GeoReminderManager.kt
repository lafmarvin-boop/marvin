package com.marvin.assistant.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rappels géolocalisés : « rappelle-moi d'acheter du pain quand je
 * passerai près de la boulangerie ».
 *
 * Stocke un dictionnaire `lieu → texte de rappel` chiffré. Utilise
 * l'API Geofencing de Google Play Services. Quand l'utilisateur entre
 * dans un rayon de 200 m du lieu, le receiver déclenche un TTS.
 *
 * Limitation : il faut connaître la latitude/longitude. Pour MVP, on
 * supporte juste les coordonnées hardcodées par lieu (la lookup auto
 * via geocoding viendra plus tard).
 */
class GeoReminderManager(private val context: Context) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, PREFS_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    data class GeoReminder(
        val id: String,
        val text: String,
        val lat: Double,
        val lng: Double,
        val radiusMeters: Float = 200f
    )

    private val client: GeofencingClient by lazy {
        LocationServices.getGeofencingClient(context)
    }

    fun all(): List<GeoReminder> {
        val raw = prefs.getString(KEY_LIST, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Throwable) { JSONArray() }
        val out = mutableListOf<GeoReminder>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(GeoReminder(
                id = o.optString("id"),
                text = o.optString("text"),
                lat = o.optDouble("lat"),
                lng = o.optDouble("lng"),
                radiusMeters = o.optDouble("radius", 200.0).toFloat()
            ))
        }
        return out
    }

    @SuppressLint("MissingPermission")
    fun add(reminder: GeoReminder): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(TAG, "ACCESS_FINE_LOCATION refusee, abort geofence")
            return false
        }
        save(all() + reminder)
        val geofence = Geofence.Builder()
            .setRequestId(reminder.id)
            .setCircularRegion(reminder.lat, reminder.lng, reminder.radiusMeters)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()
        val req = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()
        val pi = pendingIntentFor(reminder)
        client.addGeofences(req, pi)
            .addOnSuccessListener { Log.i(TAG, "Geofence ajoute : ${reminder.id}") }
            .addOnFailureListener { Log.e(TAG, "addGeofence failed", it) }
        return true
    }

    fun remove(id: String) {
        save(all().filter { it.id != id })
        client.removeGeofences(listOf(id))
    }

    fun clear() {
        val ids = all().map { it.id }
        save(emptyList())
        if (ids.isNotEmpty()) client.removeGeofences(ids)
    }

    private fun pendingIntentFor(reminder: GeoReminder): PendingIntent {
        val intent = Intent(context, GeoReminderReceiver::class.java).apply {
            action = GeoReminderReceiver.ACTION_GEO_FIRE
            putExtra("text", reminder.text)
        }
        return PendingIntent.getBroadcast(
            context, reminder.id.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun save(list: List<GeoReminder>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(JSONObject().apply {
                put("id", r.id); put("text", r.text)
                put("lat", r.lat); put("lng", r.lng)
                put("radius", r.radiusMeters)
            })
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    companion object {
        private const val TAG = "GeoReminders"
        private const val PREFS_NAME = "marvin_geo_reminders"
        private const val KEY_LIST = "list"
    }
}
