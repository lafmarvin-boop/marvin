package com.marvin.assistant.llm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Backend local via MediaPipe LLM Inference + Gemma 2 2B IT (Q4).
 *
 * Limites assumées:
 *  - **Pas de tool use.** Gemma 2 2B n'a pas de native function calling. En
 *    mode local, l'assistant répond uniquement depuis sa connaissance
 *    d'entraînement — pas de météo en temps réel, pas d'agenda. Pour ça
 *    bascule en mode Cloud (Claude).
 *  - Qualité nettement inférieure à Claude Haiku.
 *  - Première inférence lente (chargement du modèle, ~3-5 s).
 *
 * Setup (cf. README):
 *  1. Va sur https://huggingface.co/google/gemma-2-2b-it (accepte la licence).
 *  2. Télécharge `gemma-2-2b-it-cpu-int4.task` (~1.3 GB).
 *  3. Push sur le téléphone:
 *       adb push gemma-2-2b-it-cpu-int4.task \
 *         /data/local/tmp/llm/gemma-2-2b-it-cpu-int4.task
 *     puis copie dans le dossier de l'app via le file picker des réglages
 *     (ou directement via `Settings → Apps → Marvin → Stockage`).
 */
class GemmaBackend(private val context: Context) : LlmBackend {

    override val displayName: String = "Gemma 2 2B (local)"

    private var llm: LlmInference? = null

    private val modelFile: File
        get() = File(context.filesDir, MODEL_FILENAME)

    override fun isReady(): Boolean = modelFile.exists() && modelFile.length() > 100_000_000

    override suspend fun ask(history: List<ChatMessage>): LlmResult = withContext(Dispatchers.Default) {
        if (!isReady()) return@withContext LlmResult.Error(
            "Modèle Gemma absent. Télécharge-le et place-le dans le dossier de l'app (cf. README)."
        )
        val engine = ensureEngine() ?: return@withContext LlmResult.Error("Échec du chargement de Gemma.")

        val prompt = buildPrompt(history)
        try {
            val output = engine.generateResponse(prompt) ?: return@withContext LlmResult.Error("Réponse vide.")
            val cleaned = output.trim().removePrefix("<start_of_turn>model").trim()
                .substringBefore("<end_of_turn>").trim()
            if (cleaned.isBlank()) LlmResult.Error("Réponse vide.") else LlmResult.Ok(cleaned)
        } catch (t: Throwable) {
            Log.e(TAG, "Gemma inference failed", t)
            LlmResult.Error("Erreur Gemma: ${t.message}")
        }
    }

    @Synchronized
    private fun ensureEngine(): LlmInference? {
        llm?.let { return it }
        return try {
            val options = LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(512)
                .setMaxTopK(40)
                .build()
            LlmInference.createFromOptions(context, options).also { llm = it }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load Gemma model", t)
            null
        }
    }

    /**
     * Format de prompt Gemma 2 IT:
     *   <start_of_turn>user\n...<end_of_turn>\n<start_of_turn>model\n
     */
    private fun buildPrompt(history: List<ChatMessage>): String {
        val sb = StringBuilder()
        sb.append("<start_of_turn>user\n").append(SYSTEM_PROMPT).append("\n")
        // Concatène le 1er user dans le même tour que le system prompt
        var firstUserAttached = false
        for (m in history) {
            when (m.role) {
                ChatMessage.Role.USER -> {
                    if (!firstUserAttached) {
                        sb.append("\n").append(m.content).append("<end_of_turn>\n")
                        firstUserAttached = true
                    } else {
                        sb.append("<start_of_turn>user\n").append(m.content).append("<end_of_turn>\n")
                    }
                }
                ChatMessage.Role.ASSISTANT -> {
                    sb.append("<start_of_turn>model\n").append(m.content).append("<end_of_turn>\n")
                }
            }
        }
        if (!firstUserAttached) sb.append("<end_of_turn>\n")
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    fun release() {
        llm?.close()
        llm = null
    }

    companion object {
        private const val TAG = "GemmaBackend"
        const val MODEL_FILENAME = "gemma-2-2b-it-cpu-int4.task"

        private const val SYSTEM_PROMPT = """Tu es Marvin, un assistant vocal en français. Réponds toujours en français, en 2-3 phrases courtes (c'est lu à voix haute, pas d'emoji ni de markdown). Si tu ne sais pas, dis-le simplement."""
    }
}
