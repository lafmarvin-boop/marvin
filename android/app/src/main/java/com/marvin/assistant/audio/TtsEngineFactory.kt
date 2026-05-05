package com.marvin.assistant.audio

import android.content.Context
import android.util.Log

/**
 * Choisit la meilleure implémentation [TtsEngine] disponible :
 *  - Piper (sherpa-onnx) si l'AAR est sur classpath ET le modèle est
 *    dans `filesDir/piper/` → voix masculine grave naturelle
 *  - Sinon TTS Android natif (TextToSpeech)
 */
object TtsEngineFactory {
    private const val TAG = "TtsFactory"

    fun create(context: Context): TtsEngine {
        val piper = try { PiperTtsEngine(context) } catch (t: Throwable) {
            Log.w(TAG, "PiperTtsEngine instantiation failed", t); null
        }
        if (piper != null && piper.isReady()) {
            Log.i(TAG, "Using Piper TTS")
            return piper
        }
        Log.i(TAG, "Using Android TTS (Piper not ready: model files or sherpa-onnx AAR missing)")
        piper?.release()
        return TextToSpeechEngine(context)
    }
}
