package com.marvin.assistant.music

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.marvin.assistant.util.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Reconnaissance musicale via AudD (https://audd.io/).
 *
 * Setup :
 *  - Crée un compte gratuit sur audd.io (10 000 reqs/mois gratuit en
 *    démo, ou ~5 €/mois pour usage perso)
 *  - Récupère l'API token
 *  - Colle dans Réglages → "Reconnaissance musicale" → API token
 *
 * Workflow :
 *  - Enregistre 5 s d'audio du micro (la musique en cours via les
 *    enceintes captée par le mic)
 *  - Envoie en WAV à api.audd.io/recognize
 *  - Récupère titre + artiste + lien
 */
class MusicRecognizer(
    private val context: Context,
    private val settings: Settings
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean = settings.auddApiKey.isNotBlank()

    @SuppressLint("MissingPermission")
    suspend fun recognize(): String = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext "Reconnaissance musicale non configurée. Ajoute un token AudD dans Réglages."
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return@withContext "Permission micro requise."

        val pcm = try { recordPcm(5) } catch (t: Throwable) {
            Log.e(TAG, "Recording failed", t); return@withContext "Erreur d'enregistrement."
        }
        val wav = pcmToWav(pcm, SAMPLE_RATE)

        return@withContext try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("api_token", settings.auddApiKey)
                .addFormDataPart("return", "apple_music,spotify")
                .addFormDataPart(
                    "file",
                    "sample.wav",
                    wav.toRequestBody("audio/wav".toMediaType())
                )
                .build()
            val req = Request.Builder()
                .url("https://api.audd.io/recognize")
                .post(body)
                .build()
            http.newCall(req).execute().use { resp ->
                val txt = resp.body?.string() ?: return@use "Pas de réponse d'AudD."
                val json = JSONObject(txt)
                val status = json.optString("status")
                if (status != "success") return@use "AudD a retourné une erreur."
                val r = json.optJSONObject("result")
                    ?: return@use "Je n'ai pas reconnu cette musique."
                val title = r.optString("title")
                val artist = r.optString("artist")
                if (title.isBlank()) "Pas de match."
                else "C'est « $title » de $artist."
            }
        } catch (t: Throwable) {
            Log.e(TAG, "AudD HTTP failed", t)
            "Réseau indisponible pour la reconnaissance."
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordPcm(seconds: Int): ByteArray {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, minBuffer
        )
        val out = ByteArrayOutputStream()
        val buf = ByteArray(minBuffer)
        recorder.startRecording()
        val deadline = System.currentTimeMillis() + seconds * 1000L
        try {
            while (System.currentTimeMillis() < deadline) {
                val n = recorder.read(buf, 0, buf.size)
                if (n > 0) out.write(buf, 0, n)
            }
        } finally {
            recorder.stop(); recorder.release()
        }
        return out.toByteArray()
    }

    /** Convertit un blob PCM 16-bit mono en fichier WAV (header inclus). */
    private fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val totalDataLen = pcm.size + 36
        val byteRate = sampleRate * 2L
        out.write("RIFF".toByteArray())
        out.write(intToBytes(totalDataLen))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(intToBytes(16))
        out.write(shortToBytes(1))           // PCM
        out.write(shortToBytes(1))           // mono
        out.write(intToBytes(sampleRate))
        out.write(intToBytes(byteRate.toInt()))
        out.write(shortToBytes(2))           // block align
        out.write(shortToBytes(16))          // bits per sample
        out.write("data".toByteArray())
        out.write(intToBytes(pcm.size))
        out.write(pcm)
        return out.toByteArray()
    }

    private fun intToBytes(v: Int): ByteArray = byteArrayOf(
        (v and 0xff).toByte(),
        ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(),
        ((v shr 24) and 0xff).toByte()
    )

    private fun shortToBytes(v: Int): ByteArray = byteArrayOf(
        (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte()
    )

    companion object {
        private const val TAG = "MusicRecognizer"
        private const val SAMPLE_RATE = 44_100
    }
}
