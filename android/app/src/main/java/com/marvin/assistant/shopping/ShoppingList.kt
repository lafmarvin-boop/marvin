package com.marvin.assistant.shopping

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray

/**
 * Liste de courses simple, persistée et chiffrée. Les items sont des
 * strings libres (« pain », « 6 œufs », « lessive »).
 *
 * Pas de sync cloud, pas de quantités structurées — simple liste à
 * cocher mentalement. Pour partager avec famille, exporter via Google
 * Keep / WhatsApp ultérieurement.
 */
class ShoppingList(private val context: Context) {

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

    fun all(): List<String> {
        val raw = prefs.getString(KEY_LIST, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Throwable) { JSONArray() }
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }

    fun add(item: String) {
        val cleaned = item.trim()
        if (cleaned.isEmpty()) return
        val current = all().toMutableList()
        // Évite les doublons (insensible à la casse).
        if (current.any { it.equals(cleaned, ignoreCase = true) }) return
        current.add(cleaned)
        save(current)
    }

    /** Supprime un item (recherche par contenu, case-insensible). */
    fun remove(item: String) {
        val cleaned = item.trim().lowercase()
        save(all().filter { !it.lowercase().contains(cleaned) })
    }

    fun clear() = save(emptyList())

    fun size(): Int = all().size

    private fun save(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "marvin_shopping"
        private const val KEY_LIST = "list"
    }
}
