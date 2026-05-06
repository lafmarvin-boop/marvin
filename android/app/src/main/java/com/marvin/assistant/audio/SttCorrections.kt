package com.marvin.assistant.audio

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * Dictionnaire de corrections de transcription persistant.
 *
 * Au fil du temps l'utilisateur enrichit son dictionnaire perso :
 *  - via commande vocale "Jarvis quand je dis X comprends Y"
 *  - via la liste éditable dans Réglages
 *
 * Les corrections sont appliquées sur les transcriptions Vosk (STT et
 * wake-word post-detection) avant traitement par le parser / LLM.
 *
 * Stockage : EncryptedSharedPreferences (les corrections peuvent
 * contenir des noms propres, infos perso → on chiffre par sécurité).
 */
class SttCorrections(private val context: Context) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    @Volatile private var cache: Map<String, String>? = null

    /** Renvoie toutes les corrections (clé = forme entendue, valeur = correction). */
    fun all(): Map<String, String> {
        cache?.let { return it }
        val raw = prefs.getString(KEY_MAP, "{}") ?: "{}"
        val map = mutableMapOf<String, String>()
        try {
            val json = JSONObject(raw)
            for (k in json.keys()) {
                map[k.lowercase().trim()] = json.optString(k).trim()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to parse corrections JSON", t)
        }
        cache = map
        return map
    }

    /** Ajoute ou met à jour une correction. */
    fun add(heard: String, meant: String) {
        val key = heard.lowercase().trim()
        val value = meant.trim()
        if (key.isEmpty() || value.isEmpty()) return
        val current = all().toMutableMap()
        current[key] = value
        save(current)
        Log.i(TAG, "Correction ajoutée : \"$key\" → \"$value\" (${current.size} au total)")
    }

    fun remove(heard: String) {
        val key = heard.lowercase().trim()
        val current = all().toMutableMap()
        current.remove(key)
        save(current)
    }

    fun clear() = save(emptyMap())

    private fun save(map: Map<String, String>) {
        val json = JSONObject(map as Map<*, *>)
        prefs.edit().putString(KEY_MAP, json.toString()).apply()
        cache = map.toMap()
    }

    /**
     * Applique les corrections sur un transcript. Substitution mot-à-mot
     * (sensible aux limites de mots) pour ne pas remplacer "l'air" dans
     * "l'aéroport".
     */
    fun apply(transcript: String): String {
        if (transcript.isBlank()) return transcript
        val corrections = all()
        if (corrections.isEmpty()) return transcript
        var out = transcript
        for ((heard, meant) in corrections) {
            // Regex avec word boundaries — gère apostrophes et tirets via
            // une logique simple : remplacer si entouré de non-alpha.
            val pattern = Regex(
                "(^|[^\\p{L}'])" + Regex.escape(heard) + "(?=[^\\p{L}']|$)",
                RegexOption.IGNORE_CASE
            )
            out = pattern.replace(out) { m -> m.groupValues[1] + meant }
        }
        if (out != transcript) {
            Log.i(TAG, "Correction appliquée : \"$transcript\" → \"$out\"")
        }
        return out
    }

    companion object {
        private const val TAG = "SttCorrections"
        private const val PREFS_NAME = "marvin_corrections"
        private const val KEY_MAP = "corrections_json"
    }
}
