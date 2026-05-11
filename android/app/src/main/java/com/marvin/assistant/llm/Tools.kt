package com.marvin.assistant.llm

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.marvin.assistant.service.NotificationCaptureService
import com.marvin.assistant.util.Contacts
import com.marvin.assistant.util.Settings
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
 * Outils que Claude peut appeler. Trois familles :
 *  - **Sans permission** : weather, time, location, calendar, battery,
 *    device_info.
 *  - **Permissions runtime** : recent_sms (READ_SMS), recent_calls
 *    (READ_CALL_LOG). Si la perm n'est pas accordée, l'outil renvoie un
 *    message explicite.
 *  - **Accès spécial** : unread_notifications (NotificationListenerService
 *    activé dans Paramètres → Apps → Accès aux notifications).
 *
 * En mode Cloud, les contenus retournés par ces outils sont envoyés à
 * api.anthropic.com. C'est ton choix — si tu veux désactiver une catégorie,
 * dis-le et j'ajoute des toggles dans les Réglages.
 */
class Tools(
    private val context: Context,
    private val settings: Settings
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /** Tous les outils déclarés. */
    private fun allDeclared(): List<Tool> = listOf(
        getWeather, getTime, getLocation, getCalendarEvents,
        getBattery, getDeviceInfo, getRecentSms, getRecentCalls, getUnreadNotifications
    )

    /** Outils activés par l'utilisateur (filtrés par les toggles des réglages). */
    fun all(): List<Tool> = allDeclared().filter { settings.isToolEnabled(it.name) }

    // ==== API directe (pour AssistantService — court-circuite Claude) ====

    /** Lit les SMS récents et retourne une chaîne formatée pour TTS. */
    fun readSmsDirect(fromContact: String? = null, limit: Int = 3): String {
        val input = JSONObject().apply {
            if (!fromContact.isNullOrBlank()) put("from_contact", fromContact)
            put("limit", limit)
        }
        return readSms(input)
    }

    /** Lit les appels manqués récents et retourne une chaîne formatée. */
    fun readMissedCallsDirect(limit: Int = 5): String {
        val input = JSONObject().apply {
            put("missed_only", true)
            put("limit", limit)
        }
        return readCallLog(input)
    }

    /** Lit les emails non lus via les notifications Gmail / Outlook. */
    fun readEmailsDirect(): String {
        if (!NotificationCaptureService.isActive()) {
            return "Accès aux notifications non activé pour les emails."
        }
        val mailPackages = setOf(
            "com.google.android.gm",                 // Gmail
            "com.microsoft.office.outlook",          // Outlook
            "ch.protonmail.android",                 // ProtonMail
            "com.fastmail.app",                      // Fastmail
            "com.samsung.android.email.provider",    // Samsung Email
            "com.yahoo.mobile.client.android.mail"   // Yahoo
        )
        val notifs = NotificationCaptureService.snapshot(20)
            .filter { it.packageName in mailPackages }
        if (notifs.isEmpty()) return "Tu n'as aucun nouvel email."
        return "Tu as ${notifs.size} email${if (notifs.size > 1) "s" else ""} non lu${if (notifs.size > 1) "s" else ""} : " +
            notifs.take(5).joinToString(". ") { n ->
                "${n.title} — ${n.text.take(120)}"
            }
    }

    /** Lit les notifications non lues. */
    fun readUnreadNotificationsDirect(): String {
        if (!NotificationCaptureService.isActive()) {
            return "Accès aux notifications non activé. Va dans Paramètres → Apps → " +
                "Accès aux notifications → Marvin pour l'activer."
        }
        val notifs = NotificationCaptureService.snapshot(10)
        if (notifs.isEmpty()) return "Aucune notification active."
        val tf = SimpleDateFormat("HH:mm", Locale.FRENCH)
        return "Tu as ${notifs.size} notification${if (notifs.size > 1) "s" else ""} : " +
            notifs.joinToString(". ") { n ->
                val app = appLabel(n.packageName)
                val text = n.text.take(120).replace("\n", " ")
                "à ${tf.format(Date(n.postedAtMs))}, $app — ${n.title} : $text"
            }
    }

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

    // ---- Batterie ----

    private val getBattery = Tool(
        name = "get_battery",
        description = "Niveau de batterie du téléphone et état de charge.",
        inputSchema = JSONObject().apply {
            put("type", "object"); put("properties", JSONObject())
        },
        execute = {
            val bm = context.getSystemService(BatteryManager::class.java)
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val charging = bm?.isCharging == true
            if (level < 0) "Niveau de batterie indisponible."
            else "Batterie à $level%${if (charging) ", en charge" else ""}."
        }
    )

    // ---- Infos device ----

    private val getDeviceInfo = Tool(
        name = "get_device_info",
        description = "Informations techniques du téléphone : modèle, version Android, espace libre.",
        inputSchema = JSONObject().apply {
            put("type", "object"); put("properties", JSONObject())
        },
        execute = {
            val freeBytes = try {
                val stat = StatFs(context.filesDir.absolutePath)
                stat.availableBlocksLong * stat.blockSizeLong
            } catch (_: Throwable) { -1L }
            val freeGb = if (freeBytes > 0) "%.1f".format(freeBytes / 1_000_000_000.0) else "?"
            "Modèle: ${Build.MANUFACTURER} ${Build.MODEL}. " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}). " +
                "Espace libre: $freeGb Go."
        }
    )

    // ---- SMS récents ----

    private val getRecentSms = Tool(
        name = "get_recent_sms",
        description = "Lit les SMS récents (READ_SMS requis). Optionnel: filtre par contact.",
        inputSchema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("from_contact", JSONObject().apply {
                    put("type", "string")
                    put("description", "Nom de contact pour filtrer (ex: \"Marie\"). Optionnel.")
                })
                put("limit", JSONObject().apply {
                    put("type", "integer")
                    put("description", "Nombre maximum de SMS à retourner (défaut 5, max 20).")
                })
            })
        },
        execute = { input -> readSms(input) }
    )

    @SuppressLint("MissingPermission")
    private fun readSms(input: JSONObject): String {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return "Permission de lecture SMS refusée."

        val limit = input.optInt("limit", 5).coerceIn(1, 20)
        val contactFilter = input.optString("from_contact", "").trim()
        val targetNumber = if (contactFilter.isNotBlank())
            Contacts.findPhoneNumber(context, contactFilter) else null
        if (contactFilter.isNotBlank() && targetNumber == null)
            return "Contact \"$contactFilter\" introuvable."

        val selection = if (targetNumber != null) "${Telephony.Sms.ADDRESS} LIKE ?" else null
        val args = if (targetNumber != null)
            arrayOf("%${targetNumber.takeLast(8)}%") else null

        // Allowlist contacts: si non vide, on charge plus large et on filtre.
        val allowlist = settings.smsAllowlist
        val effectiveLimit = if (allowlist.isNotEmpty()) limit * 5 else limit

        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            selection, args,
            "${Telephony.Sms.DATE} DESC LIMIT $effectiveLimit"
        ) ?: return "Pas de SMS accessibles."

        val tf = SimpleDateFormat("d/MM HH:mm", Locale.FRENCH)
        val items = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                val from = it.getString(0) ?: ""
                val body = it.getString(1)?.replace("\n", " ")?.take(140) ?: ""
                val date = it.getLong(2)
                val name = Contacts.nameOfNumber(context, from) ?: from
                if (allowlist.isNotEmpty() && !matchesAllowlist(name, allowlist)) continue
                items += "[${tf.format(Date(date))}] $name: $body"
                if (items.size >= limit) break
            }
        }
        return when {
            items.isNotEmpty() -> items.joinToString(" || ")
            allowlist.isNotEmpty() -> "Aucun SMS d'un contact autorisé (allowlist active)."
            else -> "Aucun SMS."
        }
    }

    /** Renvoie true si [contactName] contient un des fragments de l'allowlist
     *  (insensible à la casse et aux accents). */
    private fun matchesAllowlist(contactName: String, allowlist: Set<String>): Boolean {
        val n = java.text.Normalizer.normalize(contactName, java.text.Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .lowercase(Locale.FRENCH)
        return allowlist.any { fragment ->
            val f = java.text.Normalizer.normalize(fragment, java.text.Normalizer.Form.NFD)
                .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
                .lowercase(Locale.FRENCH)
            f.isNotBlank() && n.contains(f)
        }
    }

    // ---- Appels récents ----

    private val getRecentCalls = Tool(
        name = "get_recent_calls",
        description = "Liste les appels récents (READ_CALL_LOG requis). Type: entrant, sortant, manqué.",
        inputSchema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("limit", JSONObject().apply {
                    put("type", "integer")
                    put("description", "Nombre d'appels à retourner (défaut 5, max 20).")
                })
                put("missed_only", JSONObject().apply {
                    put("type", "boolean")
                    put("description", "Si true, ne retourne que les appels manqués.")
                })
            })
        },
        execute = { input -> readCallLog(input) }
    )

    @SuppressLint("MissingPermission")
    private fun readCallLog(input: JSONObject): String {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return "Permission de lecture du journal d'appels refusée."

        val limit = input.optInt("limit", 5).coerceIn(1, 20)
        val missedOnly = input.optBoolean("missed_only", false)
        val selection = if (missedOnly) "${CallLog.Calls.TYPE} = ?" else null
        val args = if (missedOnly) arrayOf(CallLog.Calls.MISSED_TYPE.toString()) else null

        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE,
                CallLog.Calls.DURATION, CallLog.Calls.CACHED_NAME),
            selection, args,
            "${CallLog.Calls.DATE} DESC LIMIT $limit"
        ) ?: return "Pas de journal d'appels accessible."

        val tf = SimpleDateFormat("d/MM HH:mm", Locale.FRENCH)
        val items = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                val number = it.getString(0) ?: ""
                val type = it.getInt(1)
                val date = it.getLong(2)
                val duration = it.getLong(3)
                val cached = it.getString(4)
                val who = if (!cached.isNullOrBlank()) cached
                    else Contacts.nameOfNumber(context, number) ?: number
                val typeStr = when (type) {
                    CallLog.Calls.INCOMING_TYPE -> "entrant"
                    CallLog.Calls.OUTGOING_TYPE -> "sortant"
                    CallLog.Calls.MISSED_TYPE -> "manqué"
                    CallLog.Calls.REJECTED_TYPE -> "rejeté"
                    else -> "appel"
                }
                val durStr = if (duration > 0 && type != CallLog.Calls.MISSED_TYPE)
                    " (${duration / 60}min ${duration % 60}s)" else ""
                items += "[${tf.format(Date(date))}] $typeStr $who$durStr"
            }
        }
        return if (items.isEmpty()) "Aucun appel récent." else items.joinToString(" || ")
    }

    // ---- Notifications non lues ----

    private val getUnreadNotifications = Tool(
        name = "get_unread_notifications",
        description = "Liste les notifications actives sur le téléphone " +
            "(toutes apps confondues). Nécessite que l'accès aux notifications soit activé pour Marvin.",
        inputSchema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("limit", JSONObject().apply {
                    put("type", "integer")
                    put("description", "Nombre max de notifications (défaut 10, max 30).")
                })
            })
        },
        execute = { input ->
            if (!NotificationCaptureService.isActive()) {
                return@Tool "Accès aux notifications non activé. Va dans Paramètres → Apps → " +
                    "Accès aux notifications → Marvin pour l'activer."
            }
            val limit = input.optInt("limit", 10).coerceIn(1, 30)
            val notifs = NotificationCaptureService.snapshot(limit)
            if (notifs.isEmpty()) {
                "Aucune notification active."
            } else {
                val tf = SimpleDateFormat("HH:mm", Locale.FRENCH)
                notifs.joinToString(" || ") { n ->
                    val app = appLabel(n.packageName)
                    val text = n.text.take(120).replace("\n", " ")
                    "[${tf.format(Date(n.postedAtMs))}] $app — ${n.title}: $text"
                }
            }
        }
    )

    private fun appLabel(packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Throwable) { packageName }
}
