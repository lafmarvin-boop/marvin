package com.marvin.assistant.audio

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OnlineStream
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
 * Imports directs des classes sherpa-onnx (l'AAR est requis à la compilation).
 * Si le modèle `speaker.onnx` est absent, [isReady] renvoie false et le
 * SpeakerVerifierFactory retombe sur NoOp.
 *
 * Setup :
 *  1. Télécharge le modèle `.onnx` (ex. wespeaker_en_voxceleb_resnet34, ~26 Mo)
 *     et push en interne via run-as :
 *       adb push speaker.onnx /data/local/tmp/
 *       adb shell "run-as com.marvin.assistant.debug cp /data/local/tmp/speaker.onnx files/speaker.onnx"
 *  2. Enrôle ta voix via Réglages → Voice biometric → Enrôler
 *  3. Active le toggle « Voice biometric »
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

    @Volatile private var extractor: SpeakerEmbeddingExtractor? = null
    private val pendingEmbeddings = mutableListOf<FloatArray>()
    @Volatile private var reference: FloatArray? = loadReference()

    override fun isReady(): Boolean {
        val ok = modelFile.exists() && modelFile.length() > 0
        if (!ok) {
            Log.i(TAG, "isReady=false — speaker.onnx introuvable ($modelFile)")
        }
        return ok
    }

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
                provider = "cpu",
            )
            val instance = SpeakerEmbeddingExtractor(config = config)
            Log.i(TAG, "Speaker embedding extractor loaded, dim=${instance.dim()}")
            instance.also { extractor = it }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to instantiate SpeakerEmbeddingExtractor", t)
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
        try { extractor?.release() } catch (_: Throwable) { /* best-effort */ }
        extractor = null
    }

    private fun extractEmbedding(samples: ShortArray, sampleRate: Int): FloatArray? {
        val ext = ensureExtractor() ?: return null
        var stream: OnlineStream? = null
        return try {
            val floats = FloatArray(samples.size) { samples[it].toFloat() / 32768f }
            stream = ext.createStream()
            stream.acceptWaveform(floats, sampleRate)
            stream.inputFinished()
            val raw = ext.compute(stream)
            normalize(raw)
        } catch (t: Throwable) {
            Log.e(TAG, "extractEmbedding failed", t); null
        } finally {
            try { stream?.release() } catch (_: Throwable) {}
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
