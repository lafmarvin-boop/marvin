package com.marvin.assistant.audio

import android.content.Context
import android.util.Log

/**
 * Crée une instance de [SpeakerVerifier]. Tente d'instancier
 * [SherpaSpeakerVerifier] via reflection ; si la classe n'est pas dispo
 * (AAR sherpa-onnx absent au build), retombe sur [NoOpSpeakerVerifier].
 *
 * Permet à l'app de builder et tourner même sans le AAR sherpa-onnx.
 */
object SpeakerVerifierFactory {

    private const val TAG = "VerifierFactory"
    private const val SHERPA_CLASS = "com.marvin.assistant.audio.SherpaSpeakerVerifier"

    fun create(context: Context): SpeakerVerifier {
        return try {
            val cls = Class.forName(SHERPA_CLASS)
            val ctor = cls.getConstructor(Context::class.java)
            ctor.newInstance(context) as SpeakerVerifier
        } catch (t: Throwable) {
            Log.i(TAG, "sherpa-onnx not available; using NoOp verifier (${t.javaClass.simpleName})")
            NoOpSpeakerVerifier()
        }
    }
}
