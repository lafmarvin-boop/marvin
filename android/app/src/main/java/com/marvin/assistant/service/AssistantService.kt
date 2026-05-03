package com.marvin.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.marvin.assistant.R
import com.marvin.assistant.actions.ActionExecutor
import com.marvin.assistant.audio.SpeechToText
import com.marvin.assistant.audio.TextToSpeechEngine
import com.marvin.assistant.audio.WakeWordEngine
import com.marvin.assistant.nlu.IntentParser
import com.marvin.assistant.ui.MainActivity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AssistantService : LifecycleService() {

    private lateinit var wakeWord: WakeWordEngine
    private lateinit var stt: SpeechToText
    private lateinit var tts: TextToSpeechEngine
    private lateinit var parser: IntentParser
    private lateinit var executor: ActionExecutor
    private var pipelineJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()

        wakeWord = WakeWordEngine(this)
        stt = SpeechToText(this)
        tts = TextToSpeechEngine(this)
        parser = IntentParser()
        executor = ActionExecutor(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (pipelineJob == null) {
            pipelineJob = lifecycleScope.launch(coroutineErrorHandler) { runPipeline() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pipelineJob?.cancel()
        wakeWord.release()
        stt.release()
        tts.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private suspend fun runPipeline() {
        wakeWord.start { onWakeWordDetected() }
    }

    private fun onWakeWordDetected() {
        lifecycleScope.launch(coroutineErrorHandler) {
            wakeWord.pause()
            tts.speak("Oui ?")
            val transcript = stt.listenOnce()
            Log.i(TAG, "Transcript: $transcript")
            if (transcript.isNullOrBlank()) {
                tts.speak("J'ai rien entendu.")
            } else {
                val parsed = parser.parse(transcript)
                Log.i(TAG, "Parsed: $parsed")
                val feedback = executor.execute(parsed)
                if (feedback.isNotBlank()) tts.speak(feedback)
            }
            wakeWord.resume()
        }
    }

    private fun startInForeground() {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(
            this,
            getString(R.string.notification_channel_id)
        )
            .setContentTitle(getString(R.string.notification_listening_title))
            .setContentText(getString(R.string.notification_listening_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private val coroutineErrorHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Pipeline error", throwable)
    }

    companion object {
        private const val TAG = "MarvinService"
        private const val NOTIFICATION_ID = 0xCAFE

        fun start(context: Context) {
            val intent = Intent(context, AssistantService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AssistantService::class.java))
        }
    }
}
