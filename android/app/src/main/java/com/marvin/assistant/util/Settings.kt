package com.marvin.assistant.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LlmBackendChoice { CLOUD_CLAUDE, LOCAL_GEMMA }
enum class ClaudeModel(val id: String) {
    HAIKU("claude-haiku-4-5"),
    SONNET("claude-sonnet-4-6")
}

/**
 * Persisted user choices. La clé API Claude est stockée dans des
 * EncryptedSharedPreferences (chiffrement AES-256 piloté par le keystore
 * Android), le reste dans des prefs normales.
 */
class Settings(context: Context) {

    private val plain: SharedPreferences =
        context.getSharedPreferences("marvin", Context.MODE_PRIVATE)

    private val secure: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "marvin_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var backendChoice: LlmBackendChoice
        get() = LlmBackendChoice.valueOf(
            plain.getString(KEY_BACKEND, LlmBackendChoice.CLOUD_CLAUDE.name)!!
        )
        set(value) { plain.edit().putString(KEY_BACKEND, value.name).apply() }

    var claudeModel: ClaudeModel
        get() = ClaudeModel.valueOf(
            plain.getString(KEY_CLAUDE_MODEL, ClaudeModel.HAIKU.name)!!
        )
        set(value) { plain.edit().putString(KEY_CLAUDE_MODEL, value.name).apply() }

    var dailyLimit: Int
        get() = plain.getInt(KEY_DAILY_LIMIT, 50)
        set(value) { plain.edit().putInt(KEY_DAILY_LIMIT, value).apply() }

    var anthropicApiKey: String
        get() = secure.getString(KEY_ANTHROPIC_KEY, "") ?: ""
        set(value) { secure.edit().putString(KEY_ANTHROPIC_KEY, value).apply() }

    /** URL Home Assistant (ex. http://homeassistant.local:8123). Vide = désactivé. */
    var homeAssistantUrl: String
        get() = plain.getString(KEY_HA_URL, "") ?: ""
        set(value) { plain.edit().putString(KEY_HA_URL, value).apply() }

    /** Long-Lived Access Token Home Assistant. Stocké chiffré. */
    var homeAssistantToken: String
        get() = secure.getString(KEY_HA_TOKEN, "") ?: ""
        set(value) { secure.edit().putString(KEY_HA_TOKEN, value).apply() }

    /**
     * Quand true, Marvin demande oralement confirmation avant les actions
     * destructrices ou irréversibles (envoi SMS, appel, WhatsApp). Défaut: true.
     */
    var confirmSensitiveActions: Boolean
        get() = plain.getBoolean(KEY_CONFIRM_SENSITIVE, true)
        set(value) { plain.edit().putBoolean(KEY_CONFIRM_SENSITIVE, value).apply() }

    /** Outil activé ? Défaut: true. */
    fun isToolEnabled(toolName: String): Boolean =
        plain.getBoolean("tool_enabled_$toolName", true)

    fun setToolEnabled(toolName: String, enabled: Boolean) {
        plain.edit().putBoolean("tool_enabled_$toolName", enabled).apply()
    }

    /**
     * Efface toutes les données stockées par l'app (clé API, réglages, quota,
     * compteur). N'efface PAS les modèles Vosk/Gemma (ce sont des assets, pas
     * des données personnelles).
     */
    fun wipeAll() {
        plain.edit().clear().apply()
        secure.edit().clear().apply()
    }

    /**
     * PIN à 4-6 chiffres pour ouvrir l'écran Réglages. Stocké chiffré.
     * Vide = pas de PIN configuré.
     */
    fun isPinSet(): Boolean = secure.getString(KEY_PIN, "")?.isNotEmpty() == true

    fun setPin(pin: String) {
        if (pin.isEmpty()) secure.edit().remove(KEY_PIN).apply()
        else secure.edit().putString(KEY_PIN, pin).apply()
    }

    fun checkPin(input: String): Boolean {
        val stored = secure.getString(KEY_PIN, "") ?: return false
        // Comparaison constant-time pour éviter les timing attacks (négligeable
        // sur 4 chiffres mais bonne pratique).
        if (input.length != stored.length) return false
        var diff = 0
        for (i in input.indices) diff = diff or (input[i].code xor stored[i].code)
        return diff == 0
    }

    /**
     * Allowlist SMS: si non vide, l'outil [com.marvin.assistant.llm.Tools.getRecentSms]
     * ne retourne que les SMS provenant d'un contact dont le nom contient un
     * des fragments listés (insensible à la casse / accents).
     */
    var smsAllowlist: Set<String>
        get() = plain.getStringSet(KEY_SMS_ALLOWLIST, emptySet()) ?: emptySet()
        set(value) {
            plain.edit().putStringSet(KEY_SMS_ALLOWLIST, value).apply()
        }

    /**
     * Voice biometric: ne déclenche le wake word que si la voix matche
     * l'empreinte enrôlée. Off par défaut (activable seulement après
     * enrôlement réussi).
     */
    var voiceBiometricEnabled: Boolean
        get() = plain.getBoolean(KEY_VOICE_BIO_ENABLED, false)
        set(value) { plain.edit().putBoolean(KEY_VOICE_BIO_ENABLED, value).apply() }

    /**
     * Seuil de cosine similarity (0-1). Plus haut = plus strict (plus de
     * faux rejets, moins de faux positifs). Défaut 0.5 (équilibre raisonnable
     * pour les modèles WeSpeaker / 3D-Speaker sur voix française).
     */
    var voiceBiometricThreshold: Float
        get() = plain.getFloat(KEY_VOICE_BIO_THRESHOLD, 0.5f)
        set(value) { plain.edit().putFloat(KEY_VOICE_BIO_THRESHOLD, value.coerceIn(0f, 1f)).apply() }

    /**
     * Mode dodo : Marvin écoute toujours le wake word, mais ignore tout
     * sauf « bonjour » (qui le réveille). Persisté pour survivre aux
     * redémarrages du service.
     */
    var isSleeping: Boolean
        get() = plain.getBoolean(KEY_IS_SLEEPING, false)
        set(value) { plain.edit().putBoolean(KEY_IS_SLEEPING, value).apply() }

    /**
     * Recherche web Anthropic (server-side tool). Permet à Claude de chercher
     * sur internet pour répondre aux questions factuelles d'actualité.
     * Coût : ~1 ¢ par recherche (max 3 par requête). Désactivable pour
     * limiter les frais ou si Wi-Fi instable.
     */
    var webSearchEnabled: Boolean
        get() = plain.getBoolean(KEY_WEB_SEARCH, true)
        set(value) { plain.edit().putBoolean(KEY_WEB_SEARCH, value).apply() }

    /**
     * Annonce vocale des notifications entrantes (SMS, WhatsApp, appels
     * manqués). Off par défaut pour ne pas surprendre. Quand activé,
     * Jarvis lit automatiquement les notifications de la whitelist
     * via TTS.
     */
    var proactiveNotificationsEnabled: Boolean
        get() = plain.getBoolean(KEY_PROACTIVE_NOTIFS, false)
        set(value) { plain.edit().putBoolean(KEY_PROACTIVE_NOTIFS, value).apply() }

    /** Returns true and increments the counter if a request is allowed today. */
    @Synchronized
    fun consumeDailyQuota(): Boolean {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val storedDay = plain.getString(KEY_QUOTA_DAY, "")
        val used = if (storedDay == today) plain.getInt(KEY_QUOTA_USED, 0) else 0
        if (used >= dailyLimit) return false
        plain.edit()
            .putString(KEY_QUOTA_DAY, today)
            .putInt(KEY_QUOTA_USED, used + 1)
            .apply()
        return true
    }

    fun quotaUsedToday(): Int {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return if (plain.getString(KEY_QUOTA_DAY, "") == today)
            plain.getInt(KEY_QUOTA_USED, 0) else 0
    }

    companion object {
        private const val KEY_BACKEND = "backend"
        private const val KEY_CLAUDE_MODEL = "claude_model"
        private const val KEY_DAILY_LIMIT = "daily_limit"
        private const val KEY_ANTHROPIC_KEY = "anthropic_api_key"
        private const val KEY_HA_URL = "ha_url"
        private const val KEY_HA_TOKEN = "ha_token"
        private const val KEY_QUOTA_DAY = "quota_day"
        private const val KEY_QUOTA_USED = "quota_used"
        private const val KEY_CONFIRM_SENSITIVE = "confirm_sensitive"
        private const val KEY_PIN = "pin"
        private const val KEY_SMS_ALLOWLIST = "sms_allowlist"
        private const val KEY_VOICE_BIO_ENABLED = "voice_bio_enabled"
        private const val KEY_VOICE_BIO_THRESHOLD = "voice_bio_threshold"
        private const val KEY_IS_SLEEPING = "is_sleeping"
        private const val KEY_WEB_SEARCH = "web_search_enabled"
        private const val KEY_PROACTIVE_NOTIFS = "proactive_notifs_enabled"

        /** Apps dont on annonce les notifications quand le mode proactif est activé. */
        val PROACTIVE_NOTIF_PACKAGES = setOf(
            "com.android.mms",          // SMS Samsung
            "com.samsung.android.messaging",
            "com.google.android.apps.messaging", // SMS Google
            "com.whatsapp",
            "com.android.dialer",       // appel manqué
            "com.samsung.android.dialer"
        )

        /** Liste de tous les outils que Claude peut appeler, pour les toggles UI. */
        val ALL_TOOL_NAMES = listOf(
            "get_weather",
            "get_time",
            "get_location",
            "get_calendar_events",
            "get_battery",
            "get_device_info",
            "get_recent_sms",
            "get_recent_calls",
            "get_unread_notifications"
        )

        /** Libellés FR pour l'écran de réglages. */
        val TOOL_LABELS: Map<String, String> = mapOf(
            "get_weather" to "Météo (Open-Meteo)",
            "get_time" to "Heure / date",
            "get_location" to "Position GPS",
            "get_calendar_events" to "Agenda",
            "get_battery" to "Batterie",
            "get_device_info" to "Infos téléphone",
            "get_recent_sms" to "Lecture SMS",
            "get_recent_calls" to "Journal d'appels",
            "get_unread_notifications" to "Notifications actives"
        )
    }
}
