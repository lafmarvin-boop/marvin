package com.marvin.assistant.audio

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
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
 * Setup (cf. README, section Voice biometric) :
 *  1. Télécharge l'AAR sherpa-onnx-android et place-le dans `app/libs/`
 *     → permet au build de compiler ce fichier.
 *  2. Télécharge un modèle `.onnx` (recommandé:
 *     `3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx` ~26MB
 *     ou `wespeaker-en-voxceleb-resnet34-LM.onnx` ~25MB) et push:
 *       adb push <model>.onnx /sdcard/Android/data/com.marvin.assistant/files/speaker.onnx
 *  3. Enrôle ta voix via Réglages → Voice biometric → Enrôler
 *  4. Active le toggle « Voice biometric »
 *
 * Pourquoi un modèle d'embedding plutôt qu'un système end-to-end:
 *  - Léger (25 MB vs 100+ MB)
 *  - Permet l'enrôlement avec très peu de samples (3-5 suffisent)
 *  - L'embedding extrait est un vecteur de ~192-256 floats — comparable
 *    par cosine similarity, c'est le standard de la litterature.
 */
class SherpaSpeakerVerifier(context: Context) : SpeakerVerifier {

    private val modelFile = File(context.filesDir, MODEL_FILENAME)
    private val referenceFile = File(context.filesDir, REFERENCE_FILENAME)

    @Volatile private var extractor: SpeakerEmbeddingExtractor? = null
    private val pendingEmbeddings = mutableListOf<FloatArray>()
    @Volatile private var reference: FloatArray? = loadReference()

    override fun isReady(): Boolean = modelFile.exists() && modelFile.length() > 0

    override fun isEnrolled(): Boolean = reference != null

    @Synchronized
    private fun ensureExtractor(): SpeakerEmbeddingExtractor? {
        extractor?.let { return it }
        if (!isReady()) return null
        return try {
            val config = SpeakerEmbeddingExtractorConfig(
                model = modelFile.absolutePath,
                numThreads = 1,
                debug = false,
                provider = "cpu"
            )
            SpeakerEmbeddingExtractor(config).also { extractor = it }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load sherpa-onnx extractor", t)
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
        extractor?.release()
        extractor = null
    }

    private fun extractEmbedding(samples: ShortArray, sampleRate: Int): FloatArray? {
        val ext = ensureExtractor() ?: return null
        return try {
            val floats = FloatArray(samples.size) { samples[it].toFloat() / 32768f }
            val stream = ext.createStream()
            // sherpa-onnx Android API: acceptWaveform(samples: FloatArray, sampleRate: Int)
            stream.acceptWaveform(floats, sampleRate)
            stream.inputFinished()
            val raw = ext.compute(stream)
            stream.release()
            normalize(raw)
        } catch (t: Throwable) {
            Log.e(TAG, "extractEmbedding failed", t)
            null
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

        /** Cosine similarity entre deux vecteurs normalisés. */
        fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return 0f
            var dot = 0.0
            for (i in a.indices) dot += a[i] * b[i]
            // Vecteurs déjà normalisés → cosine = dot product.
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
