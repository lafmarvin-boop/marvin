package com.marvin.assistant.audio

/**
 * Buffer circulaire qui garde les N dernières secondes d'audio PCM 16-bit.
 *
 * Usage : on l'alimente continuellement avec les samples du micro
 * (depuis [WakeWordEngine]). Quand le wake word fire, on prend un
 * [snapshot] qui contient le segment audio qui vient de déclencher,
 * et on l'envoie au [SpeakerVerifier] pour la vérif d'identité vocale.
 *
 * Thread-safe (synchronized).
 */
class RollingAudioBuffer(
    durationSec: Int = 2,
    private val sampleRate: Int = 16_000
) {
    private val capacity: Int = durationSec * sampleRate
    private val buffer = ShortArray(capacity)
    private var writeIdx = 0
    private var totalWritten = 0L

    @Synchronized
    fun write(data: ShortArray, length: Int) {
        if (length <= 0) return
        var remaining = length
        var srcOffset = 0
        while (remaining > 0) {
            val space = capacity - writeIdx
            val toCopy = minOf(remaining, space)
            System.arraycopy(data, srcOffset, buffer, writeIdx, toCopy)
            writeIdx = (writeIdx + toCopy) % capacity
            srcOffset += toCopy
            remaining -= toCopy
        }
        totalWritten += length
    }

    /** Renvoie une copie des [capacity] derniers samples (ordre chronologique). */
    @Synchronized
    fun snapshot(): ShortArray {
        val out = ShortArray(capacity)
        if (totalWritten < capacity) {
            // Pas encore plein : on copie depuis 0 jusqu'à writeIdx, le reste est silence.
            System.arraycopy(buffer, 0, out, 0, writeIdx)
        } else {
            // Plein : reconstruire l'ordre chronologique en partant de writeIdx.
            val tail = capacity - writeIdx
            System.arraycopy(buffer, writeIdx, out, 0, tail)
            System.arraycopy(buffer, 0, out, tail, writeIdx)
        }
        return out
    }

    @Synchronized
    fun reset() {
        writeIdx = 0
        totalWritten = 0
        buffer.fill(0)
    }

    fun durationSec(): Int = capacity / sampleRate
    fun sampleRate(): Int = sampleRate
}
