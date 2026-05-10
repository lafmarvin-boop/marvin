package com.marvin.assistant.backup

import android.content.Context
import android.util.Log
import com.marvin.assistant.audio.SttCorrections
import com.marvin.assistant.memory.LongTermMemory
import com.marvin.assistant.reminders.RemindersManager
import com.marvin.assistant.routines.RoutinesManager
import com.marvin.assistant.shopping.ShoppingList
import com.marvin.assistant.util.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * Export / import des données utilisateur dans un blob chiffré par mot
 * de passe (AES-256-GCM avec dérivation PBKDF2).
 *
 * Utile pour :
 *  - Réinstaller l'app sans tout reconfigurer
 *  - Migrer vers un nouveau téléphone
 *  - Sauvegarde de sécurité
 *
 * Ce qu'on exporte :
 *  - Clé API Anthropic + ElevenLabs + Home Assistant
 *  - Settings (wake word, toggles, voice biometric threshold...)
 *  - Routines + corrections STT + liste de courses + faits long terme
 *  - Rappels actifs (avec timestamps absolus)
 *
 * Ce qu'on N'exporte PAS :
 *  - Référence vocale biométrique (le fichier binaire .bin reste local)
 *  - Audit log (vie privée - tu peux le re-générer en utilisant l'app)
 *  - Modèles Vosk / Piper / speaker (trop lourds, à pusher manuellement)
 */
class BackupManager(private val context: Context) {

    /** Crée un blob chiffré. Renvoie les bytes prêts à écrire dans un fichier. */
    fun export(password: String): ByteArray {
        val payload = buildPayload()
        return encrypt(payload.toString().toByteArray(), password)
    }

    /** Décrypte et restore. Renvoie true si succès, false si mot de passe faux. */
    fun import(blob: ByteArray, password: String): Boolean {
        val decrypted = try { decrypt(blob, password) } catch (t: Throwable) {
            Log.w(TAG, "Decrypt failed (mauvais mot de passe ?)", t)
            return false
        }
        val json = try { JSONObject(String(decrypted)) } catch (t: Throwable) {
            Log.w(TAG, "Parse JSON failed", t); return false
        }
        applyPayload(json)
        return true
    }

    private fun buildPayload(): JSONObject {
        val s = Settings(context)
        return JSONObject().apply {
            put("v", 1)
            put("at", System.currentTimeMillis())
            put("settings", JSONObject().apply {
                put("anthropic_key", s.anthropicApiKey)
                put("eleven_key", s.elevenLabsApiKey)
                put("eleven_voice", s.elevenLabsVoiceId)
                put("ha_url", s.homeAssistantUrl)
                put("ha_token", s.homeAssistantToken)
                put("wake_word", s.wakeWord)
                put("backend", s.backendChoice.name)
                put("claude_model", s.claudeModel.name)
                put("tts_backend", s.ttsBackend.name)
                put("daily_limit", s.dailyLimit)
                put("local_only", s.localOnlyMode)
                put("web_search", s.webSearchEnabled)
                put("voice_bio_threshold", s.voiceBiometricThreshold.toDouble())
                put("voice_bio_enabled", s.voiceBiometricEnabled)
                put("confirm_sensitive", s.confirmSensitiveActions)
                put("proactive_notifs", s.proactiveNotificationsEnabled)
                put("proactive_cal", s.proactiveCalendarAnnouncementsEnabled)
            })
            put("corrections", JSONObject(SttCorrections(context).all() as Map<*, *>))
            put("routines", routinesArray())
            put("shopping", JSONArray(ShoppingList(context).all()))
            put("memory", JSONArray(LongTermMemory(context).facts()))
            put("reminders", remindersArray())
        }
    }

    private fun routinesArray(): JSONArray {
        val mgr = RoutinesManager(context)
        val arr = JSONArray()
        mgr.all().forEach { r ->
            arr.put(JSONObject().apply {
                put("name", r.name)
                put("steps", JSONArray(r.steps))
            })
        }
        return arr
    }

    private fun remindersArray(): JSONArray {
        val mgr = RemindersManager(context)
        val arr = JSONArray()
        mgr.all().forEach { r ->
            arr.put(JSONObject().apply {
                put("text", r.text)
                put("trigger", r.triggerAtMs)
            })
        }
        return arr
    }

    private fun applyPayload(json: JSONObject) {
        val s = Settings(context)
        json.optJSONObject("settings")?.let { o ->
            o.optString("anthropic_key").takeIf { it.isNotBlank() }?.let { s.anthropicApiKey = it }
            o.optString("eleven_key").takeIf { it.isNotBlank() }?.let { s.elevenLabsApiKey = it }
            o.optString("eleven_voice").takeIf { it.isNotBlank() }?.let { s.elevenLabsVoiceId = it }
            o.optString("ha_url").takeIf { it.isNotBlank() }?.let { s.homeAssistantUrl = it }
            o.optString("ha_token").takeIf { it.isNotBlank() }?.let { s.homeAssistantToken = it }
            o.optString("wake_word").takeIf { it.isNotBlank() }?.let { s.wakeWord = it }
            o.optInt("daily_limit", -1).takeIf { it > 0 }?.let { s.dailyLimit = it }
            s.localOnlyMode = o.optBoolean("local_only", s.localOnlyMode)
            s.webSearchEnabled = o.optBoolean("web_search", s.webSearchEnabled)
            s.voiceBiometricEnabled = o.optBoolean("voice_bio_enabled", s.voiceBiometricEnabled)
            s.confirmSensitiveActions = o.optBoolean("confirm_sensitive", s.confirmSensitiveActions)
            s.proactiveNotificationsEnabled = o.optBoolean("proactive_notifs", s.proactiveNotificationsEnabled)
            s.proactiveCalendarAnnouncementsEnabled = o.optBoolean("proactive_cal", s.proactiveCalendarAnnouncementsEnabled)
        }
        json.optJSONObject("corrections")?.let { o ->
            val corr = SttCorrections(context)
            corr.clear()
            for (k in o.keys()) corr.add(k, o.optString(k))
        }
        json.optJSONArray("routines")?.let { arr ->
            val mgr = RoutinesManager(context)
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val name = r.optString("name")
                val steps = r.optJSONArray("steps") ?: continue
                mgr.put(name, (0 until steps.length()).map { steps.optString(it) })
            }
        }
        json.optJSONArray("shopping")?.let { arr ->
            val sl = ShoppingList(context)
            sl.clear()
            for (i in 0 until arr.length()) sl.add(arr.optString(i))
        }
        json.optJSONArray("memory")?.let { arr ->
            val mem = LongTermMemory(context)
            mem.clearFacts()
            for (i in 0 until arr.length()) mem.addFact(arr.optString(i))
        }
        json.optJSONArray("reminders")?.let { arr ->
            val rem = RemindersManager(context)
            rem.clearAll()
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val t = r.optString("text")
                val ts = r.optLong("trigger")
                if (t.isNotBlank() && ts > System.currentTimeMillis()) rem.add(t, ts)
            }
        }
    }

    // ==== Crypto AES-256-GCM avec PBKDF2-derived key ====

    private fun encrypt(data: ByteArray, password: String): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        val ct = cipher.doFinal(data)
        // [magic 4][salt 16][iv 12][ct N]
        return MAGIC + salt + iv + ct
    }

    private fun decrypt(blob: ByteArray, password: String): ByteArray {
        if (blob.size < 4 + 16 + 12 + 16) error("Blob trop petit")
        if (!blob.copyOfRange(0, 4).contentEquals(MAGIC)) error("Magic invalide")
        val salt = blob.copyOfRange(4, 20)
        val iv = blob.copyOfRange(20, 32)
        val ct = blob.copyOfRange(32, blob.size)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        return cipher.doFinal(ct)
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, 100_000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    /** Chemin par défaut où exporter le backup (Downloads de l'app). */
    fun defaultExportFile(): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, "marvin-backup-${System.currentTimeMillis()}.mvb")
    }

    companion object {
        private const val TAG = "BackupManager"
        private val MAGIC = byteArrayOf(0x4D, 0x56, 0x42, 0x01) // "MVB\x01"
    }
}
