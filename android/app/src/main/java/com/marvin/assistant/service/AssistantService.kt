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
import com.marvin.assistant.audio.VoskModelHolder
import com.marvin.assistant.audio.WakeWordEngine
import com.marvin.assistant.llm.ChatMessage
import com.marvin.assistant.llm.ClaudeBackend
import com.marvin.assistant.llm.GemmaBackend
import com.marvin.assistant.llm.LlmBackend
import com.marvin.assistant.llm.LlmResult
import com.marvin.assistant.llm.Tools
import com.marvin.assistant.nlu.IntentParser
import com.marvin.assistant.nlu.MarvinIntent
import com.marvin.assistant.ui.DiscussionActivity
import com.marvin.assistant.ui.DiscussionPhase
import com.marvin.assistant.ui.DiscussionStateHolder
import com.marvin.assistant.ui.MainActivity
import com.marvin.assistant.util.LlmBackendChoice
import com.marvin.assistant.util.Settings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AssistantService : LifecycleService() {

    private lateinit var voskModel: VoskModelHolder
    private lateinit var wakeWord: WakeWordEngine
    private lateinit var stt: SpeechToText
    private lateinit var tts: TextToSpeechEngine
    private lateinit var parser: IntentParser
    private lateinit var executor: ActionExecutor
    private lateinit var settings: Settings
    private lateinit var tools: Tools
    private var claudeBackend: ClaudeBackend? = null
    private var gemmaBackend: GemmaBackend? = null
    private var pipelineJob: Job? = null

    /** True between « jarvis discutons » et « merci » / fin de discussion. */
    @Volatile private var inDiscussion = false
    private val discussionHistory = mutableListOf<ChatMessage>()

    override fun onCreate() {
        super.onCreate()
        startInForeground()

        settings = Settings(this)
        tools = Tools(this)
        voskModel = VoskModelHolder(this)
        wakeWord = WakeWordEngine(this, voskModel)
        stt = SpeechToText(this, voskModel)
        tts = TextToSpeechEngine(this)
        parser = IntentParser()
        executor = ActionExecutor(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (pipelineJob == null) {
            pipelineJob = lifecycleScope.launch(coroutineErrorHandler) {
                wakeWord.start { onWakeWordDetected() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pipelineJob?.cancel()
        wakeWord.release()
        tts.release()
        voskModel.release()
        gemmaBackend?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun onWakeWordDetected() {
        lifecycleScope.launch(coroutineErrorHandler) {
            wakeWord.pause()
            try {
                handleTurn()
            } finally {
                wakeWord.resume()
            }
        }
    }

    private suspend fun handleTurn() {
        tts.speak("Oui ?")
        val transcript = stt.listenOnce(silenceTimeoutMs = 1200L)
        Log.i(TAG, "Transcript: $transcript")
        if (transcript.isNullOrBlank()) { tts.speak("J'ai rien entendu."); return }

        val parsed = parser.parse(transcript)
        Log.i(TAG, "Parsed: $parsed")
        when (parsed) {
            is MarvinIntent.StartDiscussion -> enterDiscussion()
            is MarvinIntent.EndDiscussion -> { /* déjà hors discussion, no-op */ }
            is MarvinIntent.Unknown -> askBackend(transcript, useHistory = false)
            else -> {
                val feedback = executor.execute(parsed)
                if (feedback.isNotBlank()) tts.speak(feedback)
            }
        }
    }

    /**
     * Boucle de discussion multi-tours. Une fois entré, on n'a plus besoin
     * du wake word entre les tours — on repart en écoute après chaque
     * réponse. Sortie sur "merci" / EndDiscussion ou silence prolongé.
     */
    private suspend fun enterDiscussion() {
        inDiscussion = true
        discussionHistory.clear()
        DiscussionStateHolder.reset()
        startActivity(
            Intent(this, DiscussionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val intro = "Je t'écoute. Dis « merci » quand t'as fini."
        DiscussionStateHolder.setPhase(DiscussionPhase.Speaking(intro))
        tts.speak(intro)

        while (inDiscussion) {
            DiscussionStateHolder.setPhase(DiscussionPhase.Listening)
            val transcript = stt.listenOnce(silenceTimeoutMs = 2500L, maxDurationMs = 12_000L)
            if (transcript.isNullOrBlank()) {
                exitDiscussion("Discussion terminée.")
                break
            }
            val parsed = parser.parse(transcript)
            if (parsed is MarvinIntent.EndDiscussion) {
                exitDiscussion("OK, on arrête là.")
                break
            }
            DiscussionStateHolder.setLastUserText(transcript)
            askBackend(transcript, useHistory = true)
        }
    }

    private suspend fun exitDiscussion(message: String) {
        DiscussionStateHolder.setPhase(DiscussionPhase.Speaking(message))
        tts.speak(message)
        inDiscussion = false
        discussionHistory.clear()
        DiscussionStateHolder.reset() // ferme automatiquement DiscussionActivity
    }

    private suspend fun askBackend(userText: String, useHistory: Boolean) {
        if (inDiscussion) DiscussionStateHolder.setPhase(DiscussionPhase.Thinking)
        val backend = pickBackend()
        if (!backend.isReady()) {
            speakWithPhase(notReadyMessage(backend))
            return
        }
        val history: List<ChatMessage> = if (useHistory) {
            discussionHistory.add(ChatMessage(ChatMessage.Role.USER, userText))
            discussionHistory.toList()
        } else {
            listOf(ChatMessage(ChatMessage.Role.USER, userText))
        }

        when (val result = backend.ask(history)) {
            is LlmResult.Ok -> {
                if (useHistory) {
                    discussionHistory.add(ChatMessage(ChatMessage.Role.ASSISTANT, result.text))
                }
                speakWithPhase(result.text)
            }
            is LlmResult.QuotaExceeded ->
                speakWithPhase("T'as atteint la limite de ${result.limit} questions par jour.")
            is LlmResult.NoNetwork -> speakWithPhase("Pas de réseau.")
            is LlmResult.Error -> {
                Log.w(TAG, "Backend error: ${result.message}")
                speakWithPhase(result.message)
            }
        }
    }

    /** Speak + push phase Speaking pendant la lecture (utile pour le visualiseur). */
    private suspend fun speakWithPhase(text: String) {
        if (inDiscussion) DiscussionStateHolder.setPhase(DiscussionPhase.Speaking(text))
        tts.speak(text)
    }

    private fun pickBackend(): LlmBackend = when (settings.backendChoice) {
        LlmBackendChoice.CLOUD_CLAUDE -> claudeBackend
            ?: ClaudeBackend(this, settings, tools).also { claudeBackend = it }
        LlmBackendChoice.LOCAL_GEMMA -> gemmaBackend
            ?: GemmaBackend(this).also { gemmaBackend = it }
    }

    private fun notReadyMessage(backend: LlmBackend): String = when (backend) {
        is ClaudeBackend -> "Clé API Claude absente. Va dans les réglages de Marvin pour la coller."
        is GemmaBackend -> "Modèle Gemma absent. Suis les instructions du README."
        else -> "Le backend n'est pas prêt."
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
