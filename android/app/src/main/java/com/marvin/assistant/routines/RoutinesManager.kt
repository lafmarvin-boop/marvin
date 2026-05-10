package com.marvin.assistant.routines

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Routines : enchaînements de commandes nommés.
 *
 * Exemple : "routine matin" = [
 *   "donne moi l'heure",
 *   "donne moi la météo",
 *   "lis mes notifications",
 *   "quelles sont les news importantes du jour"
 * ]
 *
 * L'utilisateur déclenche via "jarvis lance ma routine matin" ou
 * "jarvis routine matin". Jarvis exécute chaque étape séquentiellement
 * en gardant le contexte de discussion (la mémoire LLM est partagée).
 *
 * Stockage : chiffré dans EncryptedSharedPreferences (les routines
 * peuvent contenir des références à des contacts, lieux, etc.).
 */
class RoutinesManager(private val context: Context) {

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

    data class Routine(val name: String, val steps: List<String>)

    /** Renvoie toutes les routines, triées par nom. */
    fun all(): List<Routine> {
        val raw = prefs.getString(KEY_LIST, null)
        val arr = if (raw == null) defaultRoutines() else {
            try { JSONArray(raw) } catch (_: Throwable) { defaultRoutines() }
        }
        val out = mutableListOf<Routine>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            val stepsArr = o.optJSONArray("steps") ?: continue
            val steps = (0 until stepsArr.length()).map { stepsArr.optString(it) }
                .filter { it.isNotBlank() }
            if (name.isNotBlank() && steps.isNotEmpty()) out.add(Routine(name, steps))
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /** Cherche une routine par nom (recherche flexible : "matin" matchera "routine du matin"). */
    fun findByName(query: String): Routine? {
        val q = query.lowercase().trim()
        return all().firstOrNull {
            it.name.lowercase().contains(q) || q.contains(it.name.lowercase())
        }
    }

    /** Ajoute ou remplace une routine par nom. */
    fun put(name: String, steps: List<String>) {
        val current = all().filter { !it.name.equals(name, ignoreCase = true) }
        save(current + Routine(name, steps))
    }

    fun remove(name: String) {
        save(all().filter { !it.name.equals(name, ignoreCase = true) })
    }

    fun resetToDefaults() {
        prefs.edit().remove(KEY_LIST).apply()
    }

    private fun save(list: List<Routine>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(JSONObject().apply {
                put("name", r.name)
                put("steps", JSONArray(r.steps))
            })
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    /**
     * Routines pré-installées comme exemples / starter kit. L'utilisateur
     * peut les modifier ou supprimer depuis Réglages (à venir).
     */
    private fun defaultRoutines(): JSONArray = JSONArray().apply {
        put(JSONObject().apply {
            put("name", "matin")
            put("steps", JSONArray(listOf(
                "donne moi l'heure",
                "donne moi la météo",
                "quels sont les rendez-vous d'aujourd'hui",
                "lis mes notifications"
            )))
        })
        put(JSONObject().apply {
            put("name", "soir")
            put("steps", JSONArray(listOf(
                "donne moi la météo de demain",
                "quels sont les rendez-vous de demain"
            )))
        })
        put(JSONObject().apply {
            put("name", "news")
            put("steps", JSONArray(listOf(
                "quelles sont les news importantes du jour en France"
            )))
        })
    }

    companion object {
        private const val PREFS_NAME = "marvin_routines"
        private const val KEY_LIST = "list"
    }
}
