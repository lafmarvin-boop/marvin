package com.marvin.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Synthèse vocale via Piper (sherpa-onnx). Voix masculine grave française
 * (selon le modèle téléchargé), bien plus naturelle que le TTS Android.
 *
 * Reflection-based : compile et tourne même sans l'AAR sherpa-onnx
 * (auquel cas [isReady] renvoie false et le [TtsEngineFactory] retombe
 * sur le TTS Android natif).
 *
 * Setup (cf. README, section Piper TTS) :
 *  1. AAR sherpa-onnx-android dans `app/libs/`
 *  2. Télécharger un modèle Piper FR depuis le model zoo sherpa-onnx :
 *       https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/vits-piper.html
 *     Recommandé : `vits-piper-fr_FR-tom-medium` (voix masculine posée)
 *  3. Pousser sur le tel — 3 fichiers + 1 dossier dans `filesDir/piper/`:
 *       adb push fr_FR-tom-medium.onnx \
 *         /sdcard/Android/data/com.marvin.assistant/files/piper/voice.onnx
 *       adb push fr_FR-tom-medium.onnx.json \
 *         /sdcard/Android/data/com.marvin.assistant/files/piper/voice.onnx.json
 *       adb push tokens.txt \
 *         /sdcard/Android/data/com.marvin.assistant/files/piper/tokens.txt
 *       adb push -r espeak-ng-data \
 *         /sdcard/Android/data/com.marvin.assistant/files/piper/espeak-ng-data
 */
class PiperTtsEngine(private val context: Context) : TtsEngine {

    private val piperDir = File(context.filesDir, "piper")
    private val modelFile = File(piperDir, "voice.onnx")
    private val tokensFile = File(piperDir, "tokens.txt")
    private val espeakDir = File(piperDir, "espeak-ng-data")

    @Volatile private var tts: Any? = null
    @Volatile private var sampleRate: Int = 22050

    override fun isReady(): Boolean {
        if (!modelFile.exists() || !tokensFile.exists() || !espeakDir.exists()) return false
        return try {
            Class.forName("com.k2fsa.sherpa.onnx.OfflineTts"); true
        } catch (_: Throwable) { false }
    }

    @Synchronized
    private fun ensureTts(): Any? {
        tts?.let { return it }
        if (!isReady()) return null
        return try {
            // OfflineTtsVitsModelConfig(model, lexicon, tokens, dataDir, noiseScale, noiseScaleW, lengthScale, dictDir)
            val vitsCls = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig")
            val vits = vitsCls.getConstructor(
                String::class.java, String::class.java, String::class.java, String::class.java,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                String::class.java
            ).newInstance(
                modelFile.absolutePath, "", tokensFile.absolutePath, espeakDir.absolutePath,
                0.667f, 0.8f, 1.0f, ""
            )

            // Matcha et Kokoro: instances vides via constructeur sans-arg si dispo,
            // sinon tous les args à valeurs neutres.
            val matcha = newDefault("com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig")
            val kokoro = newDefault("com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig")

            // OfflineTtsModelConfig(vits, matcha, kokoro, numThreads, debug, provider)
            val modelCfgCls = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsModelConfig")
            val modelCfg = modelCfgCls.getConstructor(
                vitsCls, matcha::class.java, kokoro::class.java,
                Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java
            ).newInstance(vits, matcha, kokoro, 2, false, "cpu")

            // OfflineTtsConfig(model, ruleFsts, ruleFars, maxNumSentences)
            val cfgCls = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsConfig")
            val cfg = cfgCls.getConstructor(
                modelCfgCls, String::class.java, String::class.java, Int::class.javaPrimitiveType
            ).newInstance(modelCfg, "", "", 1)

            // OfflineTts(config)
            val ttsCls = Class.forName("com.k2fsa.sherpa.onnx.OfflineTts")
            val instance = ttsCls.getConstructor(cfgCls).newInstance(cfg)

            // Lit le sample rate du modèle (typiquement 22050 Hz pour Piper)
            sampleRate = try {
                ttsCls.getMethod("getSampleRate").invoke(instance) as Int
            } catch (_: Throwable) { 22050 }

            instance.also { tts = it }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load Piper TTS via reflection", t)
            null
        }
    }

    /** Tente le constructeur sans-arg, sinon le constructeur primaire (data class) avec defaults. */
    private fun newDefault(className: String): Any {
        val cls = Class.forName(className)
        // Tente le no-arg
        cls.declaredConstructors
            .firstOrNull { it.parameterCount == 0 }
            ?.let { it.isAccessible = true; return it.newInstance() }
        // Sinon le ctor primaire avec valeurs par défaut numériques/strings
        val ctor = cls.declaredConstructors.minBy { it.parameterCount }
        ctor.isAccessible = true
        val args = ctor.parameterTypes.map { defaultValue(it) }.toTypedArray()
        return ctor.newInstance(*args)
    }

    private fun defaultValue(t: Class<*>): Any? = when {
        t == String::class.java -> ""
        t == Int::class.javaPrimitiveType || t == Integer::class.java -> 0
        t == Float::class.javaPrimitiveType || t == java.lang.Float::class.java -> 0f
        t == Boolean::class.javaPrimitiveType || t == java.lang.Boolean::class.java -> false
        t == Long::class.javaPrimitiveType || t == java.lang.Long::class.java -> 0L
        t == Double::class.javaPrimitiveType || t == java.lang.Double::class.java -> 0.0
        else -> null
    }

    override suspend fun speak(text: String) = withContext(Dispatchers.IO) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return@withContext
        val tts = ensureTts() ?: return@withContext
        try {
            // generate(text, sid: Int = 0, speed: Float = 1.0f) -> GeneratedAudio
            val generateMethod = tts::class.java.getMethod(
                "generate",
                String::class.java,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            )
            val audio = generateMethod.invoke(tts, cleaned, 0, 1.0f) ?: return@withContext
            val samples = (audio::class.java.getMethod("getSamples").invoke(audio) as? FloatArray)
                ?: return@withContext
            val sr = (audio::class.java.getMethod("getSampleRate").invoke(audio) as? Int) ?: sampleRate
            playAudio(samples, sr)
        } catch (t: Throwable) {
            Log.e(TAG, "Piper TTS generate/play failed", t)
        }
    }

    private suspend fun playAudio(samples: FloatArray, sampleRate: Int) {
        if (samples.isEmpty()) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val byteSize = samples.size * 4
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(4096)
        val track = AudioTrack(
            attrs, format, maxOf(minBuf, byteSize), AudioTrack.MODE_STATIC, 0
        )
        try {
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            // Attend la fin de la lecture en yield-ant le coroutine
            val totalFrames = samples.size
            while (track.playbackHeadPosition < totalFrames && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                delay(40)
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    override fun release() {
        try { tts?.let { it::class.java.getMethod("release").invoke(it) } } catch (_: Throwable) {}
        tts = null
    }

    companion object { private const val TAG = "PiperTts" }
}
