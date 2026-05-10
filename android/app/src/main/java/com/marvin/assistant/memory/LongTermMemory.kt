package com.marvin.assistant.memory

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mémoire long terme de Jarvis.
 *
 * Deux types d'éléments persistés entre sessions :
 *
 *  1. **Faits** (facts) : informations stables que l'utilisateur a confiées
 *     à Jarvis, ex. « Ma femme s'appelle Marie », « Je travaille chez X »,
 *     « Mon code wifi est ... ». Ajoutés via la commande
 *     « Jarvis souviens-toi que ... ».
 *
 *  2. **Résumés de session** : tous les N tours de discussion, on demande
 *     à Claude de résumer la conversation en 1-2 phrases, et on stocke ce
 *     résumé. Permet à Jarvis de garder du contexte sans envoyer
 *     l'historique complet à chaque appel (qui ferait exploser le quota).
 *
 * Tout est chiffré (EncryptedSharedPreferences). Inclus dans le system
 * prompt de Claude pour qu'il en tienne compte.
 */
class LongTermMemory(private val context: Context) {

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

    fun facts(): List<String> {
        val raw = prefs.getString(KEY_FACTS, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Throwable) { JSONArray() }
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }

    fun addFact(fact: String) {
        val cleaned = fact.trim().take(500)
        if (cleaned.isEmpty()) return
        val current = facts().toMutableList()
        if (current.size >= MAX_FACTS) current.removeAt(0)
        current.add(cleaned)
        save(KEY_FACTS, current)
    }

    fun forgetFact(query: String): Boolean {
        val q = query.lowercase().trim()
        val current = facts()
        val filtered = current.filterNot { it.lowercase().contains(q) }
        if (filtered.size == current.size) return false
        save(KEY_FACTS, filtered)
        return true
    }

    fun clearFacts() = save(KEY_FACTS, emptyList())

    fun summaries(): List<String> {
        val raw = prefs.getString(KEY_SUMMARIES, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Throwable) { JSONArray() }
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }

    fun addSummary(summary: String) {
        val cleaned = summary.trim().take(800)
        if (cleaned.isEmpty()) return
        val current = summaries().toMutableList()
        if (current.size >= MAX_SUMMARIES) current.removeAt(0)
        current.add(cleaned)
        save(KEY_SUMMARIES, current)
    }

    fun clearSummaries() = save(KEY_SUMMARIES, emptyList())

    /** Bloc à insérer dans le system prompt pour donner du contexte à Claude. */
    fun buildContextBlock(): String {
        val f = facts()
        val s = summaries().takeLast(5) // garde les 5 derniers résumés
        val sb = StringBuilder()
        if (f.isNotEmpty()) {
            sb.append("\n\nCe que tu sais sur l'utilisateur (faits durables) :\n")
            f.forEach { sb.append("- ").append(it).append('\n') }
        }
        if (s.isNotEmpty()) {
            sb.append("\nRésumés des dernières conversations :\n")
            s.forEach { sb.append("- ").append(it).append('\n') }
        }
        return sb.toString()
    }

    private fun save(key: String, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "marvin_memory"
        private const val KEY_FACTS = "facts"
        private const val KEY_SUMMARIES = "summaries"
        private const val MAX_FACTS = 50
        private const val MAX_SUMMARIES = 30
    }
}
