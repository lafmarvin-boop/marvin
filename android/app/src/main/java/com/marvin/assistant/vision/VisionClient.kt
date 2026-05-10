package com.marvin.assistant.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.marvin.assistant.util.ClaudeModel
import com.marvin.assistant.util.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Appelle Claude vision avec une image + une question. L'image est
 * downscalée et compressée en JPEG pour limiter la bande passante
 * (Claude accepte jusqu'à 5 Mo, mais 200 Ko suffit largement pour
 * une réponse correcte).
 */
class VisionClient(
    private val context: Context,
    private val settings: Settings
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Envoie [imageUri] à Claude avec [question]. Renvoie la réponse texte. */
    suspend fun describe(imageUri: Uri, question: String): String = withContext(Dispatchers.IO) {
        val apiKey = settings.anthropicApiKey
        if (apiKey.isBlank()) return@withContext "Clé API Claude absente."
        if (!settings.consumeDailyQuota()) {
            return@withContext "T'as atteint la limite quotidienne."
        }

        val base64 = try {
            encodeImage(imageUri)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to encode image", t)
            return@withContext "Erreur de lecture de la photo."
        }

        val content = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "image")
                put("source", JSONObject().apply {
                    put("type", "base64")
                    put("media_type", "image/jpeg")
                    put("data", base64)
                })
            })
            put(JSONObject().apply {
                put("type", "text")
                put("text", question)
            })
        }

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", content)
            })
        }

        val payload = JSONObject().apply {
            put("model", settings.claudeModel.id)
            put("max_tokens", 400)
            put("system", "Tu es Jarvis. Décris l'image en français, " +
                "1-3 phrases courtes, ton naturel comme à l'oral. " +
                "Pas de listes, pas de markdown.")
            put("messages", messages)
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return@withContext try {
            http.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful) {
                    Log.e(TAG, "Vision API ${resp.code}: $body")
                    return@use "Erreur d'analyse : ${resp.code}"
                }
                if (body == null) return@use "Pas de réponse."
                val json = JSONObject(body)
                val contentArr = json.optJSONArray("content") ?: return@use "Réponse vide."
                val text = StringBuilder()
                for (i in 0 until contentArr.length()) {
                    val block = contentArr.getJSONObject(i)
                    if (block.optString("type") == "text") {
                        text.append(block.optString("text"))
                    }
                }
                text.toString().trim().ifBlank { "Je n'arrive pas à analyser l'image." }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Vision HTTP failed", t)
            "Réseau indisponible pour l'analyse."
        }
    }

    private fun encodeImage(uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open URI: $uri")
        val bitmap = input.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalStateException("Cannot decode image: $uri")

        // Downscale: largeur max 1024px pour limiter le payload
        val scaled = if (bitmap.width > 1024) {
            val ratio = 1024f / bitmap.width
            val newH = (bitmap.height * ratio).toInt()
            Bitmap.createScaledBitmap(bitmap, 1024, newH, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } else bitmap

        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        scaled.recycle()
        val bytes = baos.toByteArray()
        Log.i(TAG, "Image encoded : ${bytes.size / 1024} Ko")
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    companion object { private const val TAG = "VisionClient" }
}
