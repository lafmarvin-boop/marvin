package com.marvin.sport.service

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Synthèse vocale dédiée aux annonces des transitions de bloc en course.
 * - Sortie sur le canal Musique (Bluetooth pendant la course)
 * - Locale française (avec fallback)
 * - Tolérant à un éventuel échec d'init : annonce.speak() devient un no-op
 */
class SpeechAnnouncer(context: Context) {

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val locale = Locale.FRENCH
                val res = tts?.setLanguage(locale)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.US)
                }
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) {}
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {}
                })
                ready = true
            }
        }
    }

    fun speak(text: String) {
        val engine = tts ?: return
        if (!ready) return
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "marvin_announce")
    }

    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ready = false
    }
}
