package com.marvin.assistant.llm

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Quatre outils gratuits que Claude peut appeler en mode discussion :
 *  - get_weather  → Open-Meteo (gratuit, sans clé)
 *  - get_time     → horloge du téléphone
 *  - get_location → GPS via FusedLocationProvider
 *  - get_calendar → événements Android Calendar
 *
 * Tous fonctionnent offline ou sur APIs gratuites. Aucun coût additionnel.
 */
class Tools(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun all(): List<Tool> = listOf(getWeather, getTime, getLocation, getCalendarEvents)

    private val getWeather = Tool(
        name = "get_weather",
        description = "Récupère la météo actuelle ou la prévision pour une ville donnée. " +
            "Utilise Open-Meteo. Si la ville n'est pas précisée, appelle d'abord get_location.",
        inputSchema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("city", JSONObject().apply {
                    put("type", "string")
                    put("description", "Nom de la ville (ex: Paris). Optionnel si latitude/longitude fournis.")
                })
                put("latitude", JSONObject().apply {
                    put("type", "number")
                    put("description", "Latitude en degrés décimaux")
                })
                put("longitude", JSONObject().apply {
                    put("type", "number")
                    put("description", "Longitude en degrés décimaux")
                })
                put("when", JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray().apply { put("now"); put("today"); put("tomorrow") })
                    put("description", "Quand : maintenant, aujourd'hui, ou demain. Défaut: now.")
                })
            })
        },
        execute = { input -> fetchWeather(input) }
    )

    private suspend fun fetchWeather(input: JSONObject): String {
        var lat = input.optDouble("latitude", Double.NaN)
        var lon = input.optDouble("longitude", Double.NaN)
        val city = input.optString("city", "")
        val whenArg = input.optString("when", "now")

        if (lat.isNaN() || lon.isNaN()) {
            if (city.isBlank()) return "Précise une ville ou utilise get_location d'abord."
            val geo = geocode(city) ?: return "Ville inconnue: $city"
            lat = geo.first
            lon = geo.second
        }

        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,wind_speed_10m" +
            "&daily=temperature_2m_max,temperature_2m_min,weather_code" +
            "&timezone=auto&forecast_days=2"
        val body = httpGet(url) ?: return "Météo indisponible."
        val json = JSONObject(body)
        return when (whenArg) {
            "tomorrow" -> formatDaily(json, dayIndex = 1, label = "Demain")
            "today" -> formatDaily(json, dayIndex = 0, label = "Aujourd'hui")
            else -> formatCurrent(json)
        }
    }

    private fun formatCurrent(json: JSONObject): String {
        val c = json.optJSONObject("current") ?: return "Pas de données."
        val temp = c.optDouble("temperature_2m")
        val code = c.optInt("weather_code")
        val wind = c.optDouble("wind_speed_10m")
        return "${weatherCodeLabel(code)}, ${temp.toInt()} degrés, vent ${wind.toInt()} km/h."
    }

    private fun formatDaily(json: JSONObject, dayIndex: Int, label: String): String {
        val d = json.optJSONObject("daily") ?: return "Pas de prévision."
        val tmax = d.optJSONArray("temperature_2m_max")?.optDouble(dayIndex) ?: return "Pas de prévision."
        val tmin = d.optJSONArray("temperature_2m_min")?.optDouble(dayIndex) ?: return "Pas de prévision."
        val code = d.optJSONArray("weather_code")?.optInt(dayIndex) ?: 0
        return "$label: ${weatherCodeLabel(code)}, entre ${tmin.toInt()} et ${tmax.toInt()} degrés."
    }

    private fun weatherCodeLabel(code: Int): String = when (code) {
        0 -> "ciel dégagé"
        1, 2 -> "partiellement nuageux"
        3 -> "couvert"
        45, 48 -> "brouillard"
        51, 53, 55 -> "bruine"
        61, 63, 65 -> "pluie"
        66, 67 -> "pluie verglaçante"
        71, 73, 75, 77 -> "neige"
        80, 81, 82 -> "averses"
        95 -> "orage"
        96, 99 -> "orage avec grêle"
        else -> "temps variable"
    }

    private fun geocode(city: String): Pair<Double, Double>? {
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=" +
            java.net.URLEncoder.encode(city, "UTF-8") + "&count=1&language=fr"
        val body = httpGet(url) ?: return null
        val results = JSONObject(body).optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val first = results.getJSONObject(0)
        return first.getDouble("latitude") to first.getDouble("longitude")
    }

    private fun httpGet(url: String): String? = try {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (_: Throwable) { null }

    private val getTime = Tool(
        name = "get_time",
        description = "Retourne l'heure et la date actuelles du téléphone.",
        inputSchema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject())
        },
        execute = {
            val df = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.FRENCH)
            df.timeZone = TimeZone.getDefault()
            df.format(Date())
        }
    )

    private val getLocation = Tool(
        name = "get_location",
        description = "Récupère la position GPS actuelle (latitude, longitude). " +
            "À appeler avant get_weather si l'utilisateur n'a pas précisé de ville.",
        inputSchema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject())
        },
        execute = { fetchLocation() }
    )

    @SuppressLint("MissingPermission")
    private suspend fun fetchLocation(): String {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return "Permission de localisation refusée."

        val client = LocationServices.getFusedLocationProviderClient(context)
        return suspendCancellableCoroutine { cont ->
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { loc: Location? ->
                    if (loc == null) {
                        if (cont.isActive) cont.resume("Position GPS indisponible.")
                    } else {
                        val s = "latitude=${"%.4f".format(loc.latitude)}, " +
                            "longitude=${"%.4f".format(loc.longitude)}"
                        if (cont.isActive) cont.resume(s)
                    }
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume("Erreur GPS: ${it.message}")
                }
        }
    }

    private val getCalendarEvents = Tool(
        name = "get_calendar_events",
        description = "Liste les événements du calendrier Android pour aujourd'hui ou demain.",
        inputSchema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("when", JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray().apply { put("today"); put("tomorrow") })
                    put("description", "Aujourd'hui ou demain. Défaut: today.")
                })
            })
        },
        execute = { input -> fetchCalendar(input.optString("when", "today")) }
    )

    @SuppressLint("MissingPermission")
    private fun fetchCalendar(whenArg: String): String {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return "Permission de calendrier refusée."

        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            if (whenArg == "tomorrow") add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        val start = cal.timeInMillis
        val end = start + 24L * 3600L * 1000L

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, start)
        ContentUris.appendId(builder, end)
        val cursor = context.contentResolver.query(
            builder.build(),
            arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION
            ),
            null, null,
            "${CalendarContract.Instances.BEGIN} ASC"
        ) ?: return "Pas de calendrier accessible."

        val events = mutableListOf<String>()
        val tf = SimpleDateFormat("HH:mm", Locale.FRENCH)
        cursor.use {
            while (it.moveToNext()) {
                val title = it.getString(0) ?: "(sans titre)"
                val begin = it.getLong(1)
                val finish = it.getLong(2)
                val loc = it.getString(3)
                val locStr = if (!loc.isNullOrBlank()) " à $loc" else ""
                events += "${tf.format(Date(begin))}-${tf.format(Date(finish))}: $title$locStr"
            }
        }
        if (events.isEmpty()) return "Aucun événement ${if (whenArg == "tomorrow") "demain" else "aujourd'hui"}."
        return events.joinToString("; ")
    }
}
