package com.marvin.assistant.llm

/** Une discussion = liste de tours user/assistant. */
data class ChatMessage(val role: Role, val content: String) {
    enum class Role { USER, ASSISTANT }
}

sealed class LlmResult {
    data class Ok(val text: String) : LlmResult()
    data class QuotaExceeded(val limit: Int) : LlmResult()
    data class NoNetwork(val message: String) : LlmResult()
    data class Error(val message: String) : LlmResult()
}

/**
 * Implémentation interchangeable Cloud (Claude API) ou Local (Gemma 2B).
 * Le choix se fait dans les réglages, et `AssistantService` recrée le backend
 * quand le réglage change.
 */
interface LlmBackend {
    suspend fun ask(history: List<ChatMessage>): LlmResult

    /**
     * Variante streaming : émet des deltas de texte au fur et à mesure.
     * Le callback [onDelta] reçoit chaque nouveau morceau de texte au
     * fur et à mesure qu'il arrive. À la fin, renvoie le LlmResult complet.
     *
     * Implémentation par défaut : appelle ask() puis émet le résultat
     * entier en un seul delta. Les backends qui supportent vraiment le
     * streaming (Claude SSE) overrident.
     */
    suspend fun askStreaming(
        history: List<ChatMessage>,
        onDelta: suspend (String) -> Unit
    ): LlmResult {
        val r = ask(history)
        if (r is LlmResult.Ok) onDelta(r.text)
        return r
    }

    /** Prêt à répondre ? Pour Gemma local, false tant que le modèle n'est pas téléchargé. */
    fun isReady(): Boolean

    /** Nom court à afficher dans la notif / UI. */
    val displayName: String
}
