package com.marvin.sport.data

import android.content.Context
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "marvin_progression")

/**
 * Progression : +1.5 kg à la fin de chaque phase de 4 semaines.
 *   Phase 1 → charge de base
 *   Phase 2 → +1.5 kg
 *   Phase 3 → +3.0 kg
 *
 * Si l'utilisateur termine un cycle complet (les 3 phases), un compteur de
 * cycle s'incrémente et toutes les charges repartent à +4.5 kg, +6 kg, +7.5 kg.
 */
class ProgressionStore(private val context: Context) {

    private fun sessionDoneKey(sessionId: String) =
        intPreferencesKey("session_done_$sessionId")

    private fun noteKey(sessionId: String) =
        stringPreferencesKey("note_$sessionId")

    private val cycleKey = intPreferencesKey("completed_cycles")

    fun isSessionDoneFlow(sessionId: String): Flow<Boolean> =
        context.dataStore.data.map { (it[sessionDoneKey(sessionId)] ?: 0) > 0 }

    fun noteFlow(sessionId: String): Flow<String> =
        context.dataStore.data.map { it[noteKey(sessionId)].orEmpty() }

    fun completedCyclesFlow(): Flow<Int> =
        context.dataStore.data.map { it[cycleKey] ?: 0 }

    suspend fun markSessionDone(sessionId: String) {
        context.dataStore.edit { it[sessionDoneKey(sessionId)] = 1 }
    }

    suspend fun unmarkSessionDone(sessionId: String) {
        context.dataStore.edit { it[sessionDoneKey(sessionId)] = 0 }
    }

    suspend fun saveNote(sessionId: String, note: String) {
        context.dataStore.edit { it[noteKey(sessionId)] = note }
    }

    suspend fun incrementCycle() {
        context.dataStore.edit { it[cycleKey] = (it[cycleKey] ?: 0) + 1 }
    }

    suspend fun resetAll() {
        context.dataStore.edit { it.clear() }
    }

    companion object {
        private const val STEP_KG = 1.5
        private const val PHASES_PER_CYCLE = 3

        /** Charge ajustée pour une phase donnée (et un nombre de cycles complétés). */
        fun progressedLoad(
            baseLoadKg: Double?,
            phaseIndex: Int,
            completedCycles: Int = 0,
        ): Double? {
            if (baseLoadKg == null) return null
            val totalSteps = phaseIndex + PHASES_PER_CYCLE * completedCycles
            return baseLoadKg + STEP_KG * totalSteps
        }

        fun stepKgForPhase(phaseIndex: Int, completedCycles: Int = 0): Double =
            STEP_KG * (phaseIndex + PHASES_PER_CYCLE * completedCycles)
    }
}
