package com.marvin.assistant.smarthome

import android.util.Log
import com.marvin.assistant.util.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client REST minimaliste pour Home Assistant.
 *
 * Permet à Jarvis de piloter :
 *  - Lumières (light.*) : on / off / brightness
 *  - Prises (switch.*) : on / off
 *  - Scènes (scene.*) : activate
 *  - États de capteurs (sensor.*) : lecture
 *
 * Setup :
 *  1. Sur Home Assistant : Profil → Long-Lived Access Tokens → Create Token
 *  2. Note l'URL de ton HA (ex. http://homeassistant.local:8123)
 *  3. Va dans Réglages Marvin → Smart home → colle URL + token
 *
 * On nomme les entités par leur friendly_name (ex. "lampe salon" plutôt
 * que "light.salon_3"). Le client fait la résolution.
 */
class HomeAssistantClient(private val settings: Settings) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean =
        settings.homeAssistantUrl.isNotBlank() && settings.homeAssistantToken.isNotBlank()

    /** Cherche une entité par friendly_name (case-insensitive, contains). */
    private suspend fun findEntity(friendlyName: String, prefix: String? = null): String? =
        withContext(Dispatchers.IO) {
            val states = getStates() ?: return@withContext null
            val target = friendlyName.lowercase().trim()
            for (i in 0 until states.length()) {
                val o = states.optJSONObject(i) ?: continue
                val id = o.optString("entity_id")
                if (prefix != null && !id.startsWith(prefix)) continue
                val name = o.optJSONObject("attributes")?.optString("friendly_name") ?: continue
                if (name.lowercase().contains(target)) return@withContext id
            }
            null
        }

    private suspend fun getStates(): JSONArray? = withContext(Dispatchers.IO) {
        val req = buildGet("/api/states") ?: return@withContext null
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "states ${resp.code}"); return@use null
                }
                JSONArray(resp.body?.string() ?: "[]")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "states failed", t); null
        }
    }

    private fun buildGet(path: String): Request? {
        val url = settings.homeAssistantUrl.trimEnd('/')
        val token = settings.homeAssistantToken
        if (url.isBlank() || token.isBlank()) return null
        return Request.Builder()
            .url(url + path)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .build()
    }

    private fun buildPost(path: String, body: JSONObject): Request? {
        val url = settings.homeAssistantUrl.trimEnd('/')
        val token = settings.homeAssistantToken
        if (url.isBlank() || token.isBlank()) return null
        return Request.Builder()
            .url(url + path)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    /**
     * Allume / éteint / règle la luminosité d'une lampe.
     * brightness 0-100 (% ; null = état précédent).
     */
    suspend fun setLight(name: String, on: Boolean, brightness: Int? = null): String =
        withContext(Dispatchers.IO) {
            if (!isConfigured()) return@withContext "Home Assistant n'est pas configuré dans les réglages."
            val entityId = findEntity(name, "light.")
                ?: return@withContext "Je n'ai pas trouvé la lampe « $name »."
            val service = if (on) "turn_on" else "turn_off"
            val body = JSONObject().apply {
                put("entity_id", entityId)
                if (on && brightness != null) {
                    put("brightness_pct", brightness.coerceIn(0, 100))
                }
            }
            callService("light", service, body)
        }

    /** Allume / éteint une prise. */
    suspend fun setSwitch(name: String, on: Boolean): String = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext "Home Assistant n'est pas configuré dans les réglages."
        val entityId = findEntity(name, "switch.")
            ?: return@withContext "Je n'ai pas trouvé la prise « $name »."
        val service = if (on) "turn_on" else "turn_off"
        callService("switch", service, JSONObject().apply { put("entity_id", entityId) })
    }

    /** Active une scène. */
    suspend fun activateScene(name: String): String = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext "Home Assistant n'est pas configuré dans les réglages."
        val entityId = findEntity(name, "scene.")
            ?: return@withContext "Je n'ai pas trouvé la scène « $name »."
        callService("scene", "turn_on", JSONObject().apply { put("entity_id", entityId) })
    }

    private suspend fun callService(domain: String, service: String, body: JSONObject): String =
        withContext(Dispatchers.IO) {
            val req = buildPost("/api/services/$domain/$service", body)
                ?: return@withContext "Configuration manquante."
            try {
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) "C'est fait."
                    else "Home Assistant a répondu ${resp.code}."
                }
            } catch (t: Throwable) {
                Log.e(TAG, "callService failed", t)
                "Pas de réponse de Home Assistant."
            }
        }

    companion object { private const val TAG = "HomeAssistant" }
}
