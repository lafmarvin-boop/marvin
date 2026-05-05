package com.marvin.assistant.audio

/**
 * Vérifie qu'un échantillon audio appartient au locuteur enrôlé.
 * Implémentations:
 *  - [com.marvin.assistant.audio.SherpaSpeakerVerifier] — vrai backend
 *    via sherpa-onnx + modèle d'embedding (WeSpeaker / 3D-Speaker)
 *  - [NoOpSpeakerVerifier] — accepte tout, utilisé quand le modèle n'est
 *    pas installé (l'app reste fonctionnelle mais sans vérif)
 */
interface SpeakerVerifier {

    /** Modèle chargé et prêt à extraire des embeddings. */
    fun isReady(): Boolean

    /** Une référence (mean embedding sur 3-5 samples) est enregistrée. */
    fun isEnrolled(): Boolean

    /** Ajoute un échantillon à l'enrôlement en cours. */
    fun enrollSample(samples: ShortArray, sampleRate: Int)

    /** Le nombre d'échantillons accumulés depuis [resetEnrollment]. */
    fun pendingEnrollmentSamples(): Int

    /** Calcule le mean embedding et le persiste. Reset les samples accumulés. */
    fun finalizeEnrollment()

    /** Annule l'enrôlement en cours sans toucher à la référence existante. */
    fun resetEnrollment()

    /** Efface la référence enrôlée. */
    fun clearEnrollment()

    /**
     * Renvoie la cosine similarity (0-1) entre l'échantillon et la
     * référence enrôlée. Renvoie 0 si pas enrôlé ou pas prêt.
     */
    fun verify(samples: ShortArray, sampleRate: Int): Float

    fun release()
}

/** Implémentation no-op : accepte toujours, similarity 1.0. */
class NoOpSpeakerVerifier : SpeakerVerifier {
    override fun isReady() = false
    override fun isEnrolled() = false
    override fun enrollSample(samples: ShortArray, sampleRate: Int) {}
    override fun pendingEnrollmentSamples() = 0
    override fun finalizeEnrollment() {}
    override fun resetEnrollment() {}
    override fun clearEnrollment() {}
    override fun verify(samples: ShortArray, sampleRate: Int) = 1f
    override fun release() {}
}
