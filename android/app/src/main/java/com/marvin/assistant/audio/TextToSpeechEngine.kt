package com.marvin.assistant.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

class TextToSpeechEngine(context: Context) {

    private val pending = ConcurrentHashMap<String, () -> Unit>()
    @Volatile private var ready = false
    private val tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = this@TextToSpeechEngine.tts
                engine.language = Locale.FRENCH
                // Best-effort: pick a deep male French voice if available.
                pickMaleFrenchVoice(engine)?.let { engine.voice = it }
                engine.setPitch(0.85f) // un peu plus grave que la voix par défaut
                engine.setSpeechRate(0.95f) // légèrement plus posé
                ready = true
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { pending.remove(utteranceId)?.invoke() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { pending.remove(utteranceId)?.invoke() }
        })
    }

    private fun pickMaleFrenchVoice(engine: TextToSpeech): android.speech.tts.Voice? {
        val voices = try { engine.voices } catch (_: Throwable) { null } ?: return null
        // 1) FR + nom contenant "male" (mais pas "female")
        val explicitMale = voices.firstOrNull {
            it.locale.language == "fr" &&
                it.name.contains("male", ignoreCase = true) &&
                !it.name.contains("female", ignoreCase = true)
        }
        if (explicitMale != null) return explicitMale
        // 2) FR sans réseau, premier dispo
        return voices.firstOrNull { it.locale.language == "fr" && !it.isNetworkConnectionRequired }
    }

    suspend fun speak(text: String) = suspendCancellableCoroutine<Unit> { cont ->
        if (!ready) { cont.resume(Unit); return@suspendCancellableCoroutine }
        val id = UUID.randomUUID().toString()
        pending[id] = { if (cont.isActive) cont.resume(Unit) }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun release() {
        tts.stop()
        tts.shutdown()
    }
}
