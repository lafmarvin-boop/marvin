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
                this@TextToSpeechEngine.tts.language = Locale.FRENCH
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
