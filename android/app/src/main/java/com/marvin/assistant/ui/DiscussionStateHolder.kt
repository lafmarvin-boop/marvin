package com.marvin.assistant.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * État partagé entre [com.marvin.assistant.service.AssistantService]
 * (qui pousse les changements) et [DiscussionActivity] (qui les affiche).
 */
sealed class DiscussionPhase {
    /** Avant l'entrée en discussion ou après la sortie. */
    data object Idle : DiscussionPhase()

    /** Marvin écoute le micro (utilisateur en train de parler). */
    data object Listening : DiscussionPhase()

    /** Requête envoyée à Claude/Gemma, on attend la réponse. */
    data object Thinking : DiscussionPhase()

    /** Marvin parle. [text] = phrase en cours de synthèse vocale. */
    data class Speaking(val text: String) : DiscussionPhase()
}

object DiscussionStateHolder {
    private val _phase = MutableStateFlow<DiscussionPhase>(DiscussionPhase.Idle)
    val phase = _phase.asStateFlow()

    private val _lastUserText = MutableStateFlow("")
    val lastUserText = _lastUserText.asStateFlow()

    fun setPhase(p: DiscussionPhase) { _phase.value = p }
    fun setLastUserText(s: String) { _lastUserText.value = s }
    fun reset() {
        _phase.value = DiscussionPhase.Idle
        _lastUserText.value = ""
    }
}
