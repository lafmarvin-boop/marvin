package com.marvin.assistant.audio

import android.content.Context
import android.util.Log
import com.marvin.assistant.util.Settings
import com.marvin.assistant.util.TtsBackend

/**
 * Choisit l'implémentation [TtsEngine] selon les préférences utilisateur.
 *
 * Modes :
 *  - AUTO       : ElevenLabs si clé + Piper si OK + Android sinon
 *  - ELEVENLABS : force ElevenLabs (nécessite clé)
 *  - PIPER      : force Piper local
 *  - ANDROID    : force TTS Android (qualité moindre)
 *
 * Le mode local strict (Settings.localOnlyMode) ignore ElevenLabs (réseau).
 */
object TtsEngineFactory {
    private const val TAG = "TtsFactory"

    fun create(context: Context): TtsEngine {
        val settings = Settings(context)
        val mode = settings.ttsBackend
        val canUseEleven = settings.elevenLabsApiKey.isNotBlank() && !settings.localOnlyMode

        return when (mode) {
            TtsBackend.ELEVENLABS -> {
                if (canUseEleven) {
                    Log.i(TAG, "Using ElevenLabs TTS")
                    ElevenLabsTtsEngine(context, settings)
                } else {
                    Log.w(TAG, "ElevenLabs forcé mais clé absente / mode local — fallback Piper/Android")
                    pickPiperOrAndroid(context)
                }
            }
            TtsBackend.PIPER -> pickPiperOrAndroid(context, forcePiper = true)
            TtsBackend.ANDROID -> {
                Log.i(TAG, "Using Android TTS (forcé)")
                TextToSpeechEngine(context)
            }
            TtsBackend.AUTO -> {
                if (canUseEleven) {
                    Log.i(TAG, "Using ElevenLabs TTS (auto)")
                    ElevenLabsTtsEngine(context, settings)
                } else {
                    pickPiperOrAndroid(context)
                }
            }
        }
    }

    private fun pickPiperOrAndroid(context: Context, forcePiper: Boolean = false): TtsEngine {
        val piper = try { PiperTtsEngine(context) } catch (t: Throwable) {
            Log.w(TAG, "PiperTtsEngine instantiation failed", t); null
        }
        if (piper != null && piper.isReady()) {
            Log.i(TAG, "Using Piper TTS")
            return piper
        }
        if (forcePiper) {
            Log.w(TAG, "Piper forcé mais pas prêt — fallback Android")
        } else {
            Log.i(TAG, "Using Android TTS (Piper not ready)")
        }
        piper?.release()
        return TextToSpeechEngine(context)
    }
}
