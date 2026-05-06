package com.marvin.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Synthèse vocale via Piper (sherpa-onnx). Voix masculine grave française
 * (selon le modèle téléchargé), bien plus naturelle que le TTS Android.
 *
 * Utilise les imports directs des classes sherpa-onnx (l'AAR
 * `app/libs/sherpa-onnx-android.aar` les fournit). Si l'AAR est absent,
 * le projet ne compile pas — c'est OK puisqu'on a déjà documenté que
 * l'AAR est nécessaire pour cette feature.
 *
 * [TtsEngineFactory] retombe gracieusement sur le TTS Android natif
 * si [isReady] retourne false (modèle absent, par ex.).
 */
class PiperTtsEngine(private val context: Context) : TtsEngine {

    // On regarde dans 2 endroits possibles, dans cet ordre:
    //  1. filesDir/piper (interne, accessible via `adb shell run-as`)
    //  2. getExternalFilesDir/piper (externe, accessible via `adb push` direct)
    // Sur Samsung One UI, l'externe est souvent bloqué par le scoped storage
    // même pour le dossier de l'app, donc l'interne est plus fiable.
    private val piperDir: File = run {
        val internal = File(context.filesDir, "piper")
        val external = (context.getExternalFilesDir(null) ?: context.filesDir)
            .let { File(it, "piper") }
        when {
            File(internal, "voice.onnx").exists() -> internal
            File(external, "voice.onnx").exists() -> external
            else -> internal
        }
    }
    private val modelFile = File(piperDir, "voice.onnx")
    private val tokensFile = File(piperDir, "tokens.txt")
    private val espeakDir = File(piperDir, "espeak-ng-data")

    @Volatile private var tts: OfflineTts? = null
    @Volatile private var sampleRate: Int = 22050

    override fun isReady(): Boolean {
        val haveModel = modelFile.exists()
        val haveTokens = tokensFile.exists()
        val haveEspeak = espeakDir.exists() && espeakDir.isDirectory
        if (!haveModel || !haveTokens || !haveEspeak) {
            Log.i(
                TAG,
                "isReady=false — voice.onnx=$haveModel ($modelFile), " +
                    "tokens.txt=$haveTokens ($tokensFile), " +
                    "espeak-ng-data=$haveEspeak ($espeakDir)"
            )
            return false
        }
        Log.i(TAG, "isReady=true — files OK")
        return true
    }

    @Synchronized
    private fun ensureTts(): OfflineTts? {
        tts?.let { return it }
        if (!isReady()) return null
        return try {
            // Args nommés + valeurs par défaut Kotlin → les champs qu'on ne
            // connaît pas (ajouts d'une version à l'autre) prennent leur
            // défaut automatiquement, plus de signature mismatch.
            // On garde la vitesse normale (lengthScale = défaut 1.0) pour ne
            // pas paraître ralenti, mais on baisse les noiseScale pour réduire
            // les variations prosodiques → ton plus contrôlé sans lenteur.
            val vits = OfflineTtsVitsModelConfig(
                model = modelFile.absolutePath,
                tokens = tokensFile.absolutePath,
                dataDir = espeakDir.absolutePath,
                noiseScale = 0.5f,        // défaut 0.667 — moins de variation prosodique
                noiseScaleW = 0.6f,       // défaut 0.8  — moins de variation de durée
                // lengthScale laissé au défaut (1.0) — vitesse normale
            )
            val modelCfg = OfflineTtsModelConfig(
                vits = vits,
                numThreads = 2,
                debug = false,
                provider = "cpu",
            )
            val cfg = OfflineTtsConfig(model = modelCfg)
            val instance = OfflineTts(config = cfg)
            sampleRate = try { instance.sampleRate() } catch (_: Throwable) { 22050 }
            Log.i(TAG, "Piper TTS engine loaded, sampleRate=$sampleRate")
            instance.also { tts = it }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to instantiate OfflineTts", t)
            null
        }
    }

    override suspend fun speak(text: String) = withContext(Dispatchers.IO) {
        val cleaned = text.trim()
        Log.i(TAG, "speak() called, len=${cleaned.length}")
        if (cleaned.isEmpty()) return@withContext
        val engine = ensureTts()
        if (engine == null) {
            Log.e(TAG, "speak: ensureTts returned null — TTS pas chargé")
            return@withContext
        }
        try {
            val audio = engine.generate(text = cleaned, sid = 0, speed = 1.0f)
            if (audio.samples.isEmpty()) {
                Log.e(TAG, "speak: generate retourné 0 samples")
                return@withContext
            }
            Log.i(TAG, "speak: ${audio.samples.size} samples @ ${audio.sampleRate} Hz")
            playAudio(audio.samples, audio.sampleRate)
            Log.i(TAG, "speak: playback fini")
        } catch (t: Throwable) {
            Log.e(TAG, "speak failed", t)
        }
    }

    override fun release() {
        try { tts?.release() } catch (_: Throwable) {}
        tts = null
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
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(8192)
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            var offset = 0
            val chunk = 4096
            while (offset < samples.size) {
                val n = minOf(chunk, samples.size - offset)
                val written = track.write(samples, offset, n, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    Log.e(TAG, "AudioTrack.write returned $written, abort")
                    break
                }
                offset += written
            }
            while (track.playbackHeadPosition < samples.size) {
                delay(40)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "playAudio failed", t)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    companion object { private const val TAG = "PiperTts" }
}
