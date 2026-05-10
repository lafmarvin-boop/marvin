package com.marvin.assistant.audit

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Audit log : trace chaque interaction utilisateur ↔ Jarvis.
 *
 * Ce qui est enregistré :
 *  - Timestamp
 *  - Type (USER_SAID, JARVIS_SAID, ACTION, BACKEND_CALL)
 *  - Texte / détails
 *
 * Stockage : EncryptedSharedPreferences avec circular buffer (max 500
 * entrées pour éviter la croissance infinie).
 *
 * Visualisable via UI Réglages → "Historique" pour audit / debug.
 * Effaçable par l'utilisateur ou via la commande WipeAllData.
 */
class AuditLog(private val context: Context) {

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

    enum class Type { USER_SAID, JARVIS_SAID, ACTION, BACKEND_CALL, BACKEND_RESULT, ERROR }

    data class Entry(val atMs: Long, val type: Type, val text: String) {
        fun describe(): String {
            val df = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRENCH)
            return "[${df.format(Date(atMs))}] ${type.name}: $text"
        }
    }

    fun log(type: Type, text: String) {
        try {
            val list = readList().toMutableList()
            list.add(Entry(System.currentTimeMillis(), type, text))
            // Cap à MAX_ENTRIES — drop les plus vieilles
            while (list.size > MAX_ENTRIES) list.removeAt(0)
            saveList(list)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to log audit entry", t)
        }
    }

    fun all(): List<Entry> = readList().reversed() // plus récent en premier

    fun clear() = saveList(emptyList())

    fun export(): String {
        val arr = JSONArray()
        readList().forEach { e ->
            arr.put(JSONObject().apply {
                put("at", e.atMs)
                put("type", e.type.name)
                put("text", e.text)
            })
        }
        return arr.toString(2)
    }

    private fun readList(): List<Entry> {
        val raw = prefs.getString(KEY_LIST, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Throwable) { JSONArray() }
        val out = mutableListOf<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val typeName = o.optString("type")
            val type = try { Type.valueOf(typeName) } catch (_: Throwable) { continue }
            out.add(Entry(o.optLong("at"), type, o.optString("text")))
        }
        return out
    }

    private fun saveList(list: List<Entry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject().apply {
                put("at", e.atMs)
                put("type", e.type.name)
                put("text", e.text)
            })
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    companion object {
        private const val TAG = "AuditLog"
        private const val PREFS_NAME = "marvin_audit"
        private const val KEY_LIST = "list"
        private const val MAX_ENTRIES = 500
    }
}
