package com.marvin.sport.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "marvin_progression")

/** Pas de progression : +1.5 kg toutes les N séances similaires (N=3 par défaut). */
private const val PROGRESSION_STEP_KG = 1.5
private const val SESSIONS_PER_STEP = 3

/**
 * Stocke :
 *   - Le nombre de séances complétées pour chaque exercice (clé : nom de l'exo)
 *   - Un statut "complétée" par séance (clé : identifiant de séance)
 *   - Les notes annotées par séance
 */
class ProgressionStore(private val context: Context) {

    private fun completedKey(exerciseName: String) =
        intPreferencesKey("completed_$exerciseName")

    private fun sessionDoneKey(sessionId: String) =
        intPreferencesKey("session_done_$sessionId")

    private fun noteKey(sessionId: String) =
        stringPreferencesKey("note_$sessionId")

    fun completedCountFlow(exerciseName: String): Flow<Int> =
        context.dataStore.data.map { it[completedKey(exerciseName)] ?: 0 }

    fun isSessionDoneFlow(sessionId: String): Flow<Boolean> =
        context.dataStore.data.map { (it[sessionDoneKey(sessionId)] ?: 0) > 0 }

    fun noteFlow(sessionId: String): Flow<String> =
        context.dataStore.data.map { it[noteKey(sessionId)].orEmpty() }

    fun allPrefsFlow(): Flow<Preferences> = context.dataStore.data

    suspend fun markSessionDone(session: Session) {
        context.dataStore.edit { prefs ->
            val alreadyDone = (prefs[sessionDoneKey(session.id)] ?: 0) > 0
            if (alreadyDone) return@edit
            prefs[sessionDoneKey(session.id)] = 1
            session.exercises
                .filter { it.baseLoadKg != null }
                .forEach { exo ->
                    val current = prefs[completedKey(exo.name)] ?: 0
                    prefs[completedKey(exo.name)] = current + 1
                }
        }
    }

    suspend fun unmarkSessionDone(session: Session) {
        context.dataStore.edit { prefs ->
            val alreadyDone = (prefs[sessionDoneKey(session.id)] ?: 0) > 0
            if (!alreadyDone) return@edit
            prefs[sessionDoneKey(session.id)] = 0
            session.exercises
                .filter { it.baseLoadKg != null }
                .forEach { exo ->
                    val current = prefs[completedKey(exo.name)] ?: 0
                    if (current > 0) prefs[completedKey(exo.name)] = current - 1
                }
        }
    }

    suspend fun saveNote(sessionId: String, note: String) {
        context.dataStore.edit { it[noteKey(sessionId)] = note }
    }

    suspend fun resetAll() {
        context.dataStore.edit { it.clear() }
    }

    companion object {
        /** Charge ajustée en fonction du nombre de séances complétées sur cet exo. */
        fun progressedLoad(baseLoadKg: Double?, completedCount: Int): Double? {
            if (baseLoadKg == null) return null
            val steps = completedCount / SESSIONS_PER_STEP
            return baseLoadKg + steps * PROGRESSION_STEP_KG
        }

        fun stepInfo(completedCount: Int): Pair<Int, Int> {
            val toNext = SESSIONS_PER_STEP - (completedCount % SESSIONS_PER_STEP)
            val nextStepNumber = (completedCount / SESSIONS_PER_STEP) + 1
            return toNext to nextStepNumber
        }
    }
}
