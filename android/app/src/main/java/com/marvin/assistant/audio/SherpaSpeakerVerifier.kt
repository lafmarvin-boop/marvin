package com.marvin.assistant.audio

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.sqrt

/**
 * Vérification de locuteur via sherpa-onnx + un modèle d'embedding
 * (WeSpeaker, 3D-Speaker, NeMo TitaNet…).
 *
 * **Reflection-based** : zéro import direct des classes sherpa-onnx, donc
 * ce fichier compile même sans le AAR sherpa-onnx-android dans `app/libs/`.
 * À runtime :
 *  - Si l'AAR est présent : la reflection trouve les classes, le verifier
 *    fonctionne.
 *  - Si l'AAR est absent : `Class.forName` lève `ClassNotFoundException`,
 *    [isReady] renvoie false, le SpeakerVerifierFactory retombe sur NoOp.
 *
 * Setup (cf. README, section Voice biometric) :
 *  1. Télécharge l'AAR sherpa-onnx-android et place-le dans `app/libs/`.
 *  2. Télécharge un modèle `.onnx` (~26 MB) et push:
 *       adb push speaker.onnx /sdcard/Android/data/com.marvin.assistant/files/
 *  3. Enrôle ta voix via Réglages → Voice biometric → Enrôler
 *  4. Active le toggle « Voice biometric »
 */
class SherpaSpeakerVerifier(private val context: Context) : SpeakerVerifier {

    // On cherche le modèle d'abord en interne (filesDir/speaker.onnx) puis
    // en externe (getExternalFilesDir/speaker.onnx). Sur Samsung One UI le
    // scoped storage bloque parfois l'accès au dossier externe de l'app —
    // l'interne via `adb shell run-as` est plus fiable.
    private val modelFile: File = run {
        val internal = File(context.filesDir, MODEL_FILENAME)
        val external = File(context.getExternalFilesDir(null) ?: context.filesDir, MODEL_FILENAME)
        when {
            internal.exists() -> internal
            external.exists() -> external
            else -> internal
        }
    }
    private val referenceFile = File(context.filesDir, REFERENCE_FILENAME)

    @Volatile private var extractor: Any? = null
    private val pendingEmbeddings = mutableListOf<FloatArray>()
    @Volatile private var reference: FloatArray? = loadReference()

    override fun isReady(): Boolean {
        if (!modelFile.exists() || modelFile.length() <= 0) return false
        // Vérifie aussi que les classes sherpa-onnx sont sur le classpath.
        return try {
            Class.forName(EXTRACTOR_CLASS); true
        } catch (_: Throwable) { false }
    }

    override fun isEnrolled(): Boolean = reference != null

    @Synchronized
    private fun ensureExtractor(): Any? {
        extractor?.let { return it }
        if (!isReady()) return null
        return try {
            val configCls = Class.forName(CONFIG_CLASS)
            // SpeakerEmbeddingExtractorConfig(model, numThreads, debug, provider)
            val configCtor = configCls.getConstructor(
                String::class.java,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                String::class.java
            )
            val config = configCtor.newInstance(modelFile.absolutePath, 1, false, "cpu")
            val extCls = Class.forName(EXTRACTOR_CLASS)
            val extCtor = extCls.getConstructor(configCls)
            extCtor.newInstance(config).also { extractor = it }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load sherpa-onnx extractor via reflection", t)
            null
        }
    }

    override fun enrollSample(samples: ShortArray, sampleRate: Int) {
        val emb = extractEmbedding(samples, sampleRate) ?: return
        pendingEmbeddings.add(emb)
    }

    override fun pendingEnrollmentSamples(): Int = pendingEmbeddings.size

    override fun finalizeEnrollment() {
        require(pendingEmbeddings.isNotEmpty()) { "No samples enrolled." }
        val dim = pendingEmbeddings[0].size
        val mean = FloatArray(dim)
        for (e in pendingEmbeddings) {
            for (i in 0 until dim) mean[i] += e[i]
        }
        val n = pendingEmbeddings.size
        for (i in 0 until dim) mean[i] /= n
        normalizeInPlace(mean)
        reference = mean
        saveReference(mean)
        pendingEmbeddings.clear()
    }

    override fun resetEnrollment() {
        pendingEmbeddings.clear()
    }

    override fun clearEnrollment() {
        reference = null
        pendingEmbeddings.clear()
        runCatching { referenceFile.delete() }
    }

    override fun verify(samples: ShortArray, sampleRate: Int): Float {
        val ref = reference ?: return 0f
        val emb = extractEmbedding(samples, sampleRate) ?: return 0f
        return cosine(ref, emb)
    }

    override fun release() {
        try {
            extractor?.let { it::class.java.getMethod("release").invoke(it) }
        } catch (_: Throwable) { /* best-effort */ }
        extractor = null
    }

    private fun extractEmbedding(samples: ShortArray, sampleRate: Int): FloatArray? {
        val ext = ensureExtractor() ?: return null
        return try {
            val floats = FloatArray(samples.size) { samples[it].toFloat() / 32768f }
            // val stream = extractor.createStream()
            val stream = ext::class.java.getMethod("createStream").invoke(ext) ?: return null
            // stream.acceptWaveform(floats: FloatArray, sampleRate: Int)
            stream::class.java
                .getMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType)
                .invoke(stream, floats, sampleRate)
            // stream.inputFinished()
            stream::class.java.getMethod("inputFinished").invoke(stream)
            // raw = extractor.compute(stream)
            val raw = ext::class.java
                .getMethod("compute", Class.forName(STREAM_CLASS))
                .invoke(ext, stream) as FloatArray
            // stream.release()
            stream::class.java.getMethod("release").invoke(stream)
            normalize(raw)
        } catch (t: Throwable) {
            Log.e(TAG, "extractEmbedding failed", t); null
        }
    }

    private fun saveReference(emb: FloatArray) {
        try {
            DataOutputStream(FileOutputStream(referenceFile)).use { out ->
                out.writeInt(REFERENCE_MAGIC)
                out.writeInt(emb.size)
                for (v in emb) out.writeFloat(v)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to save reference embedding", t)
        }
    }

    private fun loadReference(): FloatArray? {
        if (!referenceFile.exists()) return null
        return try {
            DataInputStream(FileInputStream(referenceFile)).use { input ->
                val magic = input.readInt()
                if (magic != REFERENCE_MAGIC) return null
                val size = input.readInt()
                if (size <= 0 || size > 4096) return null
                FloatArray(size) { input.readFloat() }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load reference embedding", t); null
        }
    }

    companion object {
        private const val TAG = "SherpaVerify"
        const val MODEL_FILENAME = "speaker.onnx"
        private const val REFERENCE_FILENAME = "speaker_reference.bin"
        private const val REFERENCE_MAGIC = 0x4D5650FF.toInt() // "MVP\xff"

        private const val CONFIG_CLASS = "com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig"
        private const val EXTRACTOR_CLASS = "com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor"
        private const val STREAM_CLASS = "com.k2fsa.sherpa.onnx.OnlineStream"

        fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return 0f
            var dot = 0.0
            for (i in a.indices) dot += a[i] * b[i]
            return dot.toFloat().coerceIn(-1f, 1f)
        }

        fun normalize(v: FloatArray): FloatArray {
            val out = v.copyOf()
            normalizeInPlace(out)
            return out
        }

        fun normalizeInPlace(v: FloatArray) {
            var sum = 0.0
            for (x in v) sum += x * x
            val norm = sqrt(sum).toFloat()
            if (norm > 1e-9f) {
                for (i in v.indices) v[i] = v[i] / norm
            }
        }
    }
}
