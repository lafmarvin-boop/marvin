package com.marvin.assistant.audio

/**
 * Abstraction pour la synthèse vocale. Deux implémentations possibles :
 *  - [TextToSpeechEngine] : TTS Android natif (toujours dispo)
 *  - [PiperTtsEngine] : Piper TTS via sherpa-onnx (qualité supérieure,
 *    nécessite l'AAR sherpa-onnx + un modèle vocal `.onnx` à pousser
 *    dans `filesDir/piper/`)
 *
 * [TtsEngineFactory] choisit Piper si dispo et configuré, sinon Android.
 */
interface TtsEngine {
    /** Joue le texte et suspend jusqu'à la fin (ou abandonne en cas d'erreur). */
    suspend fun speak(text: String)

    /** Disponible et prêt à être appelé. */
    fun isReady(): Boolean = true

    /** Nettoyage. */
    fun release()
}
