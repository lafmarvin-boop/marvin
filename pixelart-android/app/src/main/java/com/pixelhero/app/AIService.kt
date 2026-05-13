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
            "King God Castle game style, mobile RPG chibi sprite, super-deformed anime proportions, " +
            "oversized expressive head with large round eyes, tiny body, short stubby legs, " +
            "high quality pixel art, vibrant saturated colors, soft cel-shading with clean black outline, ",
            ", full body front view, detailed ornate armor with metallic highlights, " +
            "decorative cape and accessories, weapon held in hand, " +
            "isolated on plain white background, centered, no shadows, no text, cute mascot vibe"),
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
        // Strong anchoring: repeat the description, emphasize "same character", lock the canvas.
        return ("${style.prefix}the exact same character: $prompt, " +
            "shown from $viewPart, full body sprite, same outfit and same proportions, " +
            "${style.suffix}, isolated on plain white background, no shadows, centered, " +
            "neutral standing pose").trim()
    }

    /**
     * Per-frame motion descriptors for an action. Each entry is added to the user's
     * base prompt to produce a sequence of phase-consistent frames. Using the SAME
     * seed across frames keeps the character roughly identical, while the action
     * suffix advances the pose.
     */
    enum class AnimationPreset(val displayName: String, val frameDescriptors: List<String>) {
        SWORD_ATTACK("Coup d'épée", listOf(
            "neutral idle pose, sword down at side, full body",
            "wind-up, sword raised diagonally behind body, knees slightly bent",
            "wind-up peak, sword overhead ready to strike, arms extended up",
            "mid-swing, sword arcing forward, dynamic action pose",
            "strike impact, sword horizontal in front of body, motion blur lines",
            "follow-through, sword pointing down-forward, body twisted",
            "recovery, sword lifting back up to guard, body returning",
            "back to idle stance, sword nearly at rest"
        )),
        WALK("Marche", listOf(
            "walking, left leg forward, right arm forward, mid-stride",
            "walking, legs near vertical, arms passing body",
            "walking, right leg forward, left arm forward, mid-stride",
            "walking, legs near vertical, arms passing body opposite direction"
        )),
        RUN("Course", listOf(
            "running, left leg high forward, right arm forward bent, dynamic",
            "running, both legs off ground, sprint pose",
            "running, right leg high forward, left arm forward bent, dynamic",
            "running, push-off pose, leaning forward"
        )),
        IDLE("Idle (respiration)", listOf(
            "idle standing pose, arms at side, chest relaxed",
            "idle standing pose, slight inhale, chest slightly raised",
            "idle standing pose, exhale, chest relaxed",
            "idle standing pose, neutral"
        )),
        CAST_SPELL("Lancer un sort", listOf(
            "neutral pose, hands at side, full body",
            "raising both arms forward, gathering energy",
            "arms extended forward, glowing magic energy between hands",
            "releasing spell, arms pushed fully forward, magical projectile beginning",
            "follow-through, slight recoil, hands open",
            "return to neutral, hands lowering"
        )),
        JUMP("Saut", listOf(
            "crouched pose, knees bent ready to jump",
            "starting to jump, legs extending, arms swinging up",
            "mid-air apex, legs tucked, arms up",
            "falling, legs extending downward, arms balanced",
            "landing, knees absorbing impact, body lowered"
        )),
        DEATH("Mort", listOf(
            "neutral standing pose",
            "staggered, body leaning back, surprised",
            "falling backward, arms outstretched",
            "on the ground, lying on back, limbs sprawled",
            "still on the ground, motionless"
        )),
        WAVE("Salut", listOf(
            "neutral pose, arms at side",
            "raising right arm forward and up",
            "right arm overhead, waving slightly left",
            "right arm overhead, waving slightly right",
            "lowering right arm back to side"
        ))
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

    /** Last error message captured from a generate*() call. Inspected by the UI on failure. */
    @Volatile var lastError: String? = null
        private set

    /**
     * Generate an image via Pollinations.ai (free, no auth).
     * Returns null on network error; check [lastError] for details.
     */
    fun generatePollinations(prompt: String, width: Int = 512, height: Int = 512, seed: Int = -1): Bitmap? {
        val encoded = URLEncoder.encode(prompt, "UTF-8")
        val seedParam = if (seed >= 0) "&seed=$seed" else ""
        val url = "https://image.pollinations.ai/prompt/$encoded?width=$width&height=$height&nologo=true$seedParam"
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 120_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "PixelHero/1.0")
            }
            val code = conn.responseCode
            if (code != 200) {
                lastError = "Pollinations HTTP $code"
                conn.disconnect()
                return null
            }
            val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it) }
            if (bmp == null) lastError = "Pollinations: image décodée invalide"
            bmp
        }.onFailure { lastError = "Pollinations: ${it.message ?: it.javaClass.simpleName}" }.getOrNull()
    }

    /**
     * Generate an image via OpenAI. Tries gpt-image-1 first (newer model with
     * transparent-background support), falls back to dall-e-3 if the account
     * doesn't have access. Returns null on network or auth error;
     * check [lastError] for details.
     */
    fun generateOpenAI(prompt: String, apiKey: String, size: String = "1024x1024"): Bitmap? {
        if (apiKey.isBlank()) { lastError = "Clé OpenAI vide"; return null }
        // 1) Try gpt-image-1 with transparent background (saves background-removal work).
        val primary = tryOpenAIModel(prompt, apiKey, "gpt-image-1", size, transparent = true)
        if (primary != null) return primary
        val primaryError = lastError
        // 2) Fallback: dall-e-3.
        val fallback = tryOpenAIModel(prompt, apiKey, "dall-e-3", size, transparent = false)
        if (fallback != null) return fallback
        // Both failed — combine errors so the user sees both attempts.
        lastError = "Aucun modèle OpenAI accessible :\n" +
            "• gpt-image-1: ${primaryError ?: "?"}\n" +
            "• dall-e-3: ${lastError ?: "?"}"
        return null
    }

    private fun tryOpenAIModel(prompt: String, apiKey: String, model: String, size: String, transparent: Boolean): Bitmap? {
        return runCatching {
            val conn = (URL("https://api.openai.com/v1/images/generations").openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 120_000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
            }
            val safePrompt = prompt
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("\t", " ")
                .take(3900)
            val body = if (model == "gpt-image-1") {
                // gpt-image-1: native transparent background, quality medium = ~$0.04 per image
                val bg = if (transparent) ",\"background\":\"transparent\",\"output_format\":\"png\"" else ""
                """{"model":"gpt-image-1","prompt":"$safePrompt","size":"$size","n":1,"quality":"medium"$bg}"""
            } else {
                // dall-e-3: response_format=b64_json, no transparent option
                """{"model":"dall-e-3","prompt":"$safePrompt","size":"$size","n":1,"response_format":"b64_json"}"""
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body); it.flush() }
            val code = conn.responseCode
            if (code != 200) {
                val errBody = (conn.errorStream ?: conn.inputStream).bufferedReader().use { it.readText() }
                lastError = "$model HTTP $code: ${errBody.take(400)}"
                conn.disconnect()
                return@runCatching null
            }
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val key = "\"b64_json\":\""
            val idx = response.indexOf(key)
            if (idx < 0) { lastError = "$model: réponse sans b64_json: ${response.take(200)}"; return@runCatching null }
            val start = idx + key.length
            val end = response.indexOf('"', start)
            if (end < 0) { lastError = "$model: réponse tronquée"; return@runCatching null }
            val b64 = response.substring(start, end)
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: run { lastError = "$model: image décodée invalide"; null }
        }.onFailure { lastError = "$model: ${it.message ?: it.javaClass.simpleName}" }.getOrNull()
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
