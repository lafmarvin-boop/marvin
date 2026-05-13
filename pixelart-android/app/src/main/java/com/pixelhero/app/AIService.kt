package com.pixelhero.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Cloud AI service for generating images from text prompts.
 *
 * Default provider: Pollinations.ai (free, no API key, no signup required).
 * Optional provider: OpenAI DALL-E (better quality, requires user-provided
 * API key, paid per image).
 *
 * All methods are blocking (suspend funs) — call from a coroutine on
 * Dispatchers.IO so the UI thread doesn't stall.
 */
object AIService {

    /** Style preset prefixes that get prepended to the user prompt to steer the IA. */
    enum class Style(val displayName: String, val prefix: String, val suffix: String, val isDecor: Boolean = false) {
        FREE("Style libre", "", ""),
        KGC("King God Castle (chibi anime)",
            "high quality pixel art character sprite, chibi proportions, anime style, ",
            ", full body, isolated, plain white background, detailed armor, RPG character"),
        RPG_8BIT("RPG 8-bit (NES/SNES)",
            "pixel art sprite, 16-bit RPG style, ",
            ", full body, isolated, plain background, retro game character"),
        FIGHTING("Fighter (Street Fighter)",
            "pixel art fighting game sprite, ",
            ", action pose, full body, isolated, plain background"),
        SOULS("Dark fantasy",
            "pixel art dark fantasy character, ",
            ", detailed armor, gothic, isolated, plain dark background"),
        CUTE("Mignon / Stardew",
            "pixel art cute character, Stardew Valley style, ",
            ", smiling, full body, isolated, plain background"),
        DECOR_FANTASY("Décor fantasy",
            "pixel art background scene, fantasy game environment, ",
            ", detailed, side-scroller view, no characters, no text", isDecor = true),
        DECOR_DUNGEON("Décor donjon",
            "pixel art dungeon background, dark stone walls, torches, ",
            ", detailed, retro game environment, no characters", isDecor = true),
        DECOR_VILLAGE("Décor village",
            "pixel art village background, medieval houses, ",
            ", detailed, daytime, retro game environment, no characters", isDecor = true),
        DECOR_SCIFI("Décor sci-fi",
            "pixel art sci-fi background, futuristic city or spaceship interior, ",
            ", detailed, neon lights, no characters, no text", isDecor = true),
        DECOR_NATURE("Décor nature",
            "pixel art nature background, forest or mountains or beach, ",
            ", detailed, retro game environment, no characters", isDecor = true),
        TILE("Tile / texture (seamless)",
            "pixel art seamless tile texture, ",
            ", repeating pattern, top-down view, detailed, no characters", isDecor = true)
    }

    fun applyStyle(prompt: String, style: Style): String =
        "${style.prefix}$prompt${style.suffix}".trim()

    /** English camera/orientation description for a given character view. */
    fun viewDescriptor(view: ViewTransform.View): String = when (view) {
        ViewTransform.View.FRONT -> "front view, facing camera"
        ViewTransform.View.BACK -> "back view, facing away from camera, back of head visible"
        ViewTransform.View.SIDE_LEFT -> "side profile facing left, full side view"
        ViewTransform.View.SIDE_RIGHT -> "side profile facing right, full side view"
        ViewTransform.View.THREE_QUARTER_LEFT -> "three quarter view facing left"
        ViewTransform.View.THREE_QUARTER_RIGHT -> "three quarter view facing right"
    }

    /** Build a prompt that asks for the SAME character but in a specific view, isolated on white. */
    fun applyStyleWithView(prompt: String, style: Style, view: ViewTransform.View): String {
        val viewPart = viewDescriptor(view)
        return "${style.prefix}$prompt, $viewPart${style.suffix}, isolated on plain white background, no shadows, centered".trim()
    }

    /** Map an ARGB color int to a coarse English color word for AI prompts. */
    fun describeColor(c: Int): String {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
        val light = (mx + mn) / 2
        if (mx - mn < 20) return when {
            light < 40 -> "black"; light < 90 -> "dark gray"
            light < 170 -> "gray"; light < 220 -> "light gray"; else -> "white"
        }
        val isLight = light > 170; val isDark = light < 80
        val prefix = when { isDark -> "dark "; isLight -> "light "; else -> "" }
        val hue = when {
            r > g && r > b && g < 100 && b < 100 -> "red"
            r > 180 && g > 100 && b < 100 -> "orange"
            r > 180 && g > 180 && b < 120 -> "yellow"
            g > r && g > b -> "green"
            b > r && b > g && r < 120 -> "blue"
            r > 100 && b > 100 && g < r && g < b -> "purple"
            r > 120 && g > 60 && b < 60 -> "brown"
            else -> "neutral"
        }
        return (prefix + hue).trim()
    }

    /**
     * Generate an image via Pollinations.ai (free, no auth).
     * Returns null on network error.
     */
    fun generatePollinations(prompt: String, width: Int = 512, height: Int = 512, seed: Int = -1): Bitmap? {
        val encoded = URLEncoder.encode(prompt, "UTF-8")
        val seedParam = if (seed >= 0) "&seed=$seed" else ""
        val url = "https://image.pollinations.ai/prompt/$encoded?width=$width&height=$height&nologo=true$seedParam"
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 120_000  // image generation can be slow
                requestMethod = "GET"
                setRequestProperty("User-Agent", "PixelHero/1.0")
            }
            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }

    /**
     * Generate an image via OpenAI DALL-E 3 (requires user API key, paid).
     * Returns null on network or auth error.
     */
    fun generateOpenAI(prompt: String, apiKey: String, size: String = "1024x1024"): Bitmap? {
        if (apiKey.isBlank()) return null
        return runCatching {
            val conn = (URL("https://api.openai.com/v1/images/generations").openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 120_000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
            }
            val safePrompt = prompt.replace("\\", "\\\\").replace("\"", "\\\"")
            val body = """{"model":"dall-e-3","prompt":"$safePrompt","size":"$size","n":1,"response_format":"b64_json"}"""
            OutputStreamWriter(conn.outputStream).use { it.write(body); it.flush() }
            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            // Parse "b64_json": "..." manually to avoid heavy deps
            val key = "\"b64_json\":\""
            val idx = response.indexOf(key)
            if (idx < 0) return null
            val start = idx + key.length
            val end = response.indexOf('"', start)
            if (end < 0) return null
            val b64 = response.substring(start, end)
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    fun saveApiKey(context: android.content.Context, key: String) {
        context.getSharedPreferences("ai", android.content.Context.MODE_PRIVATE).edit()
            .putString("openai_key", key).apply()
    }

    fun loadApiKey(context: android.content.Context): String? {
        return context.getSharedPreferences("ai", android.content.Context.MODE_PRIVATE)
            .getString("openai_key", null)
    }
}
