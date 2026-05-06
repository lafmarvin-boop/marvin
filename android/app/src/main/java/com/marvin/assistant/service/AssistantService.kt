package com.marvin.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.marvin.assistant.R
import com.marvin.assistant.actions.ActionExecutor
import com.marvin.assistant.audio.SpeakerVerifier
import com.marvin.assistant.audio.SpeakerVerifierFactory
import com.marvin.assistant.audio.SpeechToText
import com.marvin.assistant.audio.SttCorrections
import com.marvin.assistant.audio.TtsEngine
import com.marvin.assistant.audio.TtsEngineFactory
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
    private lateinit var tts: TtsEngine
    private lateinit var parser: IntentParser
    private lateinit var executor: ActionExecutor
    private lateinit var settings: Settings
    private lateinit var tools: Tools
    private lateinit var speakerVerifier: SpeakerVerifier
    private lateinit var sttCorrections: SttCorrections
    private val toneGen by lazy {
        try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }
        catch (_: Throwable) { null }
    }
    private var claudeBackend: ClaudeBackend? = null
    private var gemmaBackend: GemmaBackend? = null
    private var pipelineJob: Job? = null

    /** True between « jarvis discutons » et « merci » / fin de discussion. */
    @Volatile private var inDiscussion = false
    private val discussionHistory = mutableListOf<ChatMessage>()

    override fun onCreate() {
        super.onCreate()
        // Init settings AVANT startInForeground: buildNotification() lit
        // settings.isSleeping pour choisir le titre de la notif.
        settings = Settings(this)
        startInForeground()

        tools = Tools(this, settings)
        voskModel = VoskModelHolder(this)
        speakerVerifier = SpeakerVerifierFactory.create(this)
        sttCorrections = SttCorrections(this)
        wakeWord = WakeWordEngine(
            context = this,
            voskModel = voskModel,
            speakerVerifier = speakerVerifier,
            voiceBiometricEnabled = { settings.voiceBiometricEnabled && speakerVerifier.isEnrolled() },
            voiceBiometricThreshold = { settings.voiceBiometricThreshold }
        )
        stt = SpeechToText(this, voskModel)
        tts = TtsEngineFactory.create(this)
        parser = IntentParser()
        executor = ActionExecutor(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (pipelineJob == null) {
            pipelineJob = lifecycleScope.launch(coroutineErrorHandler) {
                wakeWord.start { transcript -> onWakeWordDetected(transcript) }
            }
            // Met à jour la notification pour refléter l'état initial (réveillé/dodo).
            updateNotification()
        }
        return START_STICKY
    }

    private fun playWakeBeep() {
        // Bip court (~80 ms) qui remplace le TTS "Oui ?". N'overlap pas la
        // voix de l'utilisateur et confirme audiblement que Jarvis a entendu.
        toneGen?.startTone(ToneGenerator.TONE_PROP_PROMPT, 80)
    }

    override fun onDestroy() {
        pipelineJob?.cancel()
        wakeWord.release()
        tts.release()
        voskModel.release()
        gemmaBackend?.release()
        speakerVerifier.release()
        toneGen?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun onWakeWordDetected(wakeTranscript: String) {
        val sleeping = settings.isSleeping
        val saidBonjour = wakeTranscript.contains("bonjour", ignoreCase = true)
        val saidJarvis = JARVIS_VARIANTS.any { wakeTranscript.contains(it, ignoreCase = true) }

        // Mode dodo : on ne réagit qu'à "bonjour" pour réveiller.
        if (sleeping) {
            if (saidBonjour) {
                pipelineJob?.cancel()
                pipelineJob = lifecycleScope.launch(coroutineErrorHandler) {
                    wakeWord.pause()
                    try { wakeUp() } finally { wakeWord.resume() }
                }
            }
            return
        }

        // Mode éveillé : "bonjour" tout seul (sans "jarvis") = juste un
        // bonjour ambiant, on l'ignore.
        if (!saidJarvis) return

        // Strip wake word pour extraire la commande éventuelle.
        val command = stripWakeWord(wakeTranscript).takeIf { it.split(' ').size >= 2 }

        // BARGE-IN : si une réponse Jarvis est en cours, on l'interrompt
        // et on cancel le pipeline. Si l'utilisateur a juste dit "jarvis"
        // (sans commande), on s'arrête là — pas de nouveau bip qui dirait
        // "j'ai rien entendu".
        val wasActive = pipelineJob?.isActive == true
        if (wasActive) {
            Log.i(TAG, "Barge-in : annulation du pipeline en cours")
            tts.stop()
            pipelineJob?.cancel()
            if (command == null) return // rien à enchaîner, juste un "stop Jarvis"
        }

        pipelineJob = lifecycleScope.launch(coroutineErrorHandler) {
            handleTurn(prefilledTranscript = command)
        }
    }

    private fun stripWakeWord(transcript: String): String {
        var result = transcript
        for (variant in JARVIS_VARIANTS + listOf("bonjour")) {
            result = result.replace(variant, "", ignoreCase = true)
        }
        return result.trim().replace(Regex("\\s+"), " ")
    }

    private suspend fun wakeUp() {
        settings.isSleeping = false
        updateNotification()
        openJarvisVisual()
        try {
            speakWithPhase("Bonjour, je suis là.")
            kotlinx.coroutines.delay(600)
        } finally {
            DiscussionStateHolder.reset()
        }
    }

    private suspend fun goToSleep() {
        settings.isSleeping = true
        speakWithPhase("Bonne nuit. Dis « bonjour Jarvis » pour me réveiller.")
        updateNotification()
    }

    private suspend fun handleTurn(prefilledTranscript: String? = null) {
        // Ouvre l'écran "réacteur" futuriste pour CHAQUE interaction Jarvis.
        // Reste affiché pendant l'écoute / la réflexion / la réponse, puis
        // se ferme tout seul (DiscussionActivity finish() sur Phase.Idle).
        openJarvisVisual()
        try {
            handleTurnInner(prefilledTranscript)
        } finally {
            // Petite pause pour laisser l'utilisateur voir la dernière phase
            // avant que l'écran se ferme.
            kotlinx.coroutines.delay(600)
            // Si on a basculé en mode discussion (loop), enterDiscussion garde
            // le visuel ouvert lui-même. Sinon on ferme.
            if (!inDiscussion) DiscussionStateHolder.reset()
        }
    }

    private fun openJarvisVisual() {
        val phase = DiscussionStateHolder.phase.value
        if (phase == DiscussionPhase.Idle) {
            val intent = Intent(this, DiscussionActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            try {
                startActivity(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "startActivity bloqué (background-launch?), fallback notif full-screen", t)
            }
            // Fallback : poste une notif heads-up avec full-screen-intent —
            // Android 10+ bloque startActivity depuis background, mais
            // une fullScreenIntent passe (notamment sur lockscreen).
            postFullScreenIntent(intent)
        }
        DiscussionStateHolder.setPhase(DiscussionPhase.Listening)
    }

    private fun postFullScreenIntent(targetIntent: Intent) {
        val pi = PendingIntent.getActivity(
            this,
            VISUAL_REQUEST_CODE,
            targetIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = Notification.Builder(this, VISUAL_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Jarvis")
            .setContentText("Écoute en cours…")
            .setPriority(Notification.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        getSystemService(android.app.NotificationManager::class.java)
            ?.notify(VISUAL_NOTIFICATION_ID, notif)
    }

    private suspend fun handleTurnInner(prefilledTranscript: String? = null) {
        // Si l'utilisateur a enchaîné le wake word + commande
        // (ex. "jarvis donne moi l'heure"), on a déjà la commande, pas
        // besoin de bip + nouvelle écoute STT. Sinon on bipe + écoute.
        val rawTranscript = if (!prefilledTranscript.isNullOrBlank()) {
            Log.i(TAG, "Using prefilled transcript from wake-word stream: $prefilledTranscript")
            prefilledTranscript
        } else {
            playWakeBeep()
            wakeWord.pause()
            val captured = try {
                stt.listenOnce(silenceTimeoutMs = 1800L, maxDurationMs = 8_000L)
            } finally { wakeWord.resume() }
            Log.i(TAG, "Transcript: $captured")
            captured
        }
        if (rawTranscript.isNullOrBlank()) {
            speakWithPhase("J'ai rien entendu.")
            return
        }
        val transcript = sttCorrections.apply(rawTranscript)
        DiscussionStateHolder.setLastUserText(transcript)
        DiscussionStateHolder.setPhase(DiscussionPhase.Thinking)

        val parsed = parser.parse(transcript)
        Log.i(TAG, "Parsed: $parsed")

        // Wipe: confirmation orale obligatoire, indépendamment du toggle.
        if (parsed is MarvinIntent.WipeAllData) {
            if (awaitConfirmation(
                    "Tu veux vraiment effacer toutes les données de Marvin — clé API, " +
                        "réglages, historique ? Dis « oui efface » pour confirmer.",
                    requirePositiveWord = "efface"
                )) {
                doWipe()
            } else {
                speakWithPhase("OK, j'efface rien.")
            }
            return
        }

        // Actions sensibles: confirmation si toggle activé.
        if (isSensitive(parsed) && settings.confirmSensitiveActions) {
            val desc = describe(parsed)
            if (!awaitConfirmation("Je vais $desc. Tu confirmes ?")) {
                speakWithPhase("OK, j'annule.")
                return
            }
        }

        when (parsed) {
            is MarvinIntent.StartDiscussion -> { enterDiscussion(); return }
            is MarvinIntent.EndDiscussion -> { /* déjà hors discussion, no-op */ return }
            is MarvinIntent.GoToSleep -> { goToSleep(); return }
            is MarvinIntent.AddCorrection -> {
                sttCorrections.add(parsed.heard, parsed.meant)
                speakWithPhase("OK, désormais quand j'entends « ${parsed.heard} » je comprendrai « ${parsed.meant} ».")
            }
            is MarvinIntent.Unknown -> askBackend(transcript, useHistory = true)
            is MarvinIntent.WipeAllData -> return
            else -> {
                val feedback = executor.execute(parsed)
                if (feedback.isNotBlank()) speakWithPhase(feedback)
            }
        }

        // Conversation continue : après avoir répondu, on écoute encore
        // ~5 s pour une éventuelle question de suivi. Si l'utilisateur
        // parle, on enchaîne en gardant la mémoire de discussion. Sinon,
        // on retourne au wake word.
        followUpLoop()
    }

    private suspend fun followUpLoop() {
        while (true) {
            DiscussionStateHolder.setPhase(DiscussionPhase.Listening)
            // Auto-close après 5 s de silence : la conversation reste ouverte
            // 5 s pour permettre une question de suivi naturelle, puis se
            // ferme toute seule si l'utilisateur ne dit rien.
            wakeWord.pause()
            val rawFollowUp = try {
                stt.listenOnce(silenceTimeoutMs = 5_000L, maxDurationMs = 12_000L)
            } finally { wakeWord.resume() }
            if (rawFollowUp.isNullOrBlank()) return // silence → retour au wake word
            val followUp = sttCorrections.apply(rawFollowUp)

            val parsedFu = parser.parse(followUp)
            if (parsedFu is MarvinIntent.EndDiscussion) { speakWithPhase("OK."); return }
            if (parsedFu is MarvinIntent.GoToSleep) { goToSleep(); return }
            if (parsedFu is MarvinIntent.AddCorrection) {
                sttCorrections.add(parsedFu.heard, parsedFu.meant)
                speakWithPhase("OK, c'est noté : « ${parsedFu.heard} » sera compris comme « ${parsedFu.meant} ».")
                continue
            }
            if (parsedFu is MarvinIntent.WipeAllData) {
                if (awaitConfirmation(
                        "Tu veux vraiment effacer toutes les données ? Dis « oui efface ».",
                        requirePositiveWord = "efface"
                    )) doWipe() else speakWithPhase("OK, j'efface rien.")
                return
            }
            if (isSensitive(parsedFu) && settings.confirmSensitiveActions) {
                if (!awaitConfirmation("Je vais ${describe(parsedFu)}. Tu confirmes ?")) {
                    speakWithPhase("OK, j'annule.")
                    continue
                }
            }
            DiscussionStateHolder.setLastUserText(followUp)
            when (parsedFu) {
                is MarvinIntent.Unknown -> askBackend(followUp, useHistory = true)
                else -> {
                    val feedback = executor.execute(parsedFu)
                    if (feedback.isNotBlank()) speakWithPhase(feedback)
                }
            }
        }
    }

    private fun isSensitive(intent: MarvinIntent): Boolean = when (intent) {
        is MarvinIntent.SendSms,
        is MarvinIntent.CallContact,
        is MarvinIntent.WhatsAppMessage -> true
        else -> false
    }

    private fun describe(intent: MarvinIntent): String = when (intent) {
        is MarvinIntent.SendSms -> "envoyer un SMS à ${intent.recipient}"
        is MarvinIntent.CallContact -> "appeler ${intent.recipient}"
        is MarvinIntent.WhatsAppMessage -> "ouvrir WhatsApp pour écrire à ${intent.recipient}"
        else -> "exécuter cette action"
    }

    /**
     * Demande confirmation orale. Renvoie true si la réponse contient un
     * mot positif (oui, ok, confirme…). Si [requirePositiveWord] est fourni,
     * la réponse doit contenir ce mot précis (ex: "efface" pour le wipe) —
     * un simple "oui" ne suffit pas, pour éviter un wipe accidentel.
     */
    private suspend fun awaitConfirmation(
        prompt: String,
        requirePositiveWord: String? = null
    ): Boolean {
        tts.speak(prompt)
        wakeWord.pause()
        val answer = try {
            stt.listenOnce(silenceTimeoutMs = 1500L, maxDurationMs = 4_000L)
        } finally { wakeWord.resume() } ?: return false
        val a = answer.lowercase().trim()
        if (requirePositiveWord != null) {
            return a.contains(requirePositiveWord, ignoreCase = true) &&
                YES_PATTERN.containsMatchIn(a)
        }
        return YES_PATTERN.containsMatchIn(a) && !NO_PATTERN.containsMatchIn(a)
    }

    private suspend fun doWipe() {
        Log.w(TAG, "Wiping all data on user request")
        inDiscussion = false
        discussionHistory.clear()
        DiscussionStateHolder.reset()
        settings.wipeAll()
        tts.speak("Toutes les données effacées. Je m'arrête.")
        // Petite pause pour que la TTS ait le temps de finir.
        kotlinx.coroutines.delay(2000)
        stopSelf()
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
            wakeWord.pause()
            val transcript = try {
                stt.listenOnce(silenceTimeoutMs = 2500L, maxDurationMs = 12_000L)
            } finally { wakeWord.resume() }
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
        DiscussionStateHolder.setPhase(DiscussionPhase.Thinking)
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
        DiscussionStateHolder.setPhase(DiscussionPhase.Speaking(text))
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
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Met à jour la notif sans relancer le service (état dodo / éveillé). */
    private fun updateNotification() {
        val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val (title, text) = if (settings.isSleeping) {
            "Marvin dort 💤" to "Dis « bonjour Jarvis » pour me réveiller."
        } else {
            getString(R.string.notification_listening_title) to
                getString(R.string.notification_listening_text)
        }
        return NotificationCompat.Builder(this, getString(R.string.notification_channel_id))
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private val coroutineErrorHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Pipeline error", throwable)
    }

    companion object {
        private const val TAG = "MarvinService"
        private const val NOTIFICATION_ID = 0xCAFE
        private const val VISUAL_NOTIFICATION_ID = 0xCAFF
        private const val VISUAL_REQUEST_CODE = 0xC0DE
        const val VISUAL_CHANNEL_ID = "marvin_visual"

        /** Variantes de prononciation que Vosk peut produire pour "Jarvis".
         *  Volontairement large pour ne pas rater le wake word. La voix
         *  biométrique (si activée) filtre les faux positifs côté locuteur. */
        private val JARVIS_VARIANTS = listOf(
            "jarvis", "djarvis", "djarviss", "djarvisse", "jarvisse",
            "jarvi", "yarvis", "djarvi", "charvis", "tchavis", "charvi",
            "jarvice", "yves", "yvre", "tarvis"
        )

        private val YES_PATTERN = Regex(
            """\b(oui|ouais|yes|ok|d'accord|confirme(?:r)?|vas[- ]y|envoie|appelle|efface|supprime)\b""",
            RegexOption.IGNORE_CASE
        )
        private val NO_PATTERN = Regex(
            """\b(non|nan|annule(?:r)?|stop|laisse tomber|n'envoie pas|n'efface pas)\b""",
            RegexOption.IGNORE_CASE
        )

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
