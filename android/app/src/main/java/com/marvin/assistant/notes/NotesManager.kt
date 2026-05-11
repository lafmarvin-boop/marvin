package com.marvin.assistant.notes

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Notes vocales : « Jarvis prends une note : ... »
 *
 * Liste chronologique chiffrée. Chaque note a un texte et un timestamp.
 */
class NotesManager(private val context: Context) {

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

    data class Note(val atMs: Long, val text: String)

    fun all(): List<Note> {
        val raw = prefs.getString(KEY_LIST, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Throwable) { JSONArray() }
        val out = mutableListOf<Note>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val at = o.optLong("at")
            val text = o.optString("text")
            if (text.isNotBlank()) out.add(Note(at, text))
        }
        return out.sortedByDescending { it.atMs }
    }

    fun add(text: String) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        val current = all().toMutableList()
        if (current.size >= MAX_NOTES) current.removeAt(current.lastIndex) // drop oldest
        current.add(0, Note(System.currentTimeMillis(), cleaned))
        save(current)
    }

    fun removeAt(index: Int) {
        val list = all().toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            save(list)
        }
    }

    fun clear() = save(emptyList())

    private fun save(list: List<Note>) {
        val arr = JSONArray()
        list.forEach { n ->
            arr.put(JSONObject().apply {
                put("at", n.atMs)
                put("text", n.text)
            })
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "marvin_notes"
        private const val KEY_LIST = "list"
        private const val MAX_NOTES = 200
    }
}
