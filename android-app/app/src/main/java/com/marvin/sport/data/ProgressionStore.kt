package com.marvin.sport.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "marvin_progression")

/**
 * Progression : +1,5 kg toutes les 4 séances similaires effectuées
 * sur le même exercice. Le compteur est partagé entre les différents
 * programmes (clé = nom de l'exercice).
 *
 * Stocke par ailleurs :
 *   - le drapeau "séance complétée" (clé = sessionId)
 *   - les annotations libres par séance
 *   - le programme sélectionné par défaut (id de programme)
 */
class ProgressionStore(private val context: Context) {

    private fun completedKey(exerciseName: String) =
        intPreferencesKey("completed_$exerciseName")

    private fun sessionDoneKey(sessionId: String) =
        intPreferencesKey("session_done_$sessionId")

    private fun noteKey(sessionId: String) =
        stringPreferencesKey("note_$sessionId")

    private val selectedProgramKey = stringPreferencesKey("selected_program")

    fun completedCountFlow(exerciseName: String): Flow<Int> =
        context.dataStore.data.map { it[completedKey(exerciseName)] ?: 0 }

    fun isSessionDoneFlow(sessionId: String): Flow<Boolean> =
        context.dataStore.data.map { (it[sessionDoneKey(sessionId)] ?: 0) > 0 }

    fun noteFlow(sessionId: String): Flow<String> =
        context.dataStore.data.map { it[noteKey(sessionId)].orEmpty() }

    fun selectedProgramFlow(default: String): Flow<String> =
        context.dataStore.data.map { it[selectedProgramKey] ?: default }

    suspend fun saveSelectedProgram(programId: String) {
        context.dataStore.edit { it[selectedProgramKey] = programId }
    }

    suspend fun markSessionDone(session: Session) {
        context.dataStore.edit { prefs ->
            val already = (prefs[sessionDoneKey(session.id)] ?: 0) > 0
            if (already) return@edit
            prefs[sessionDoneKey(session.id)] = 1
            session.exercises
                .filter { it.oneRmKey != null }
                .forEach { exo ->
                    val cur = prefs[completedKey(exo.name)] ?: 0
                    prefs[completedKey(exo.name)] = cur + 1
                }
        }
    }

    suspend fun unmarkSessionDone(session: Session) {
        context.dataStore.edit { prefs ->
            val already = (prefs[sessionDoneKey(session.id)] ?: 0) > 0
            if (!already) return@edit
            prefs[sessionDoneKey(session.id)] = 0
            session.exercises
                .filter { it.oneRmKey != null }
                .forEach { exo ->
                    val cur = prefs[completedKey(exo.name)] ?: 0
                    if (cur > 0) prefs[completedKey(exo.name)] = cur - 1
                }
        }
    }

    /**
     * Réinitialise toutes les séances d'un programme (utilisé quand l'utilisateur
     * a complété toutes les séances de toutes les phases).
     */
    suspend fun resetProgram(program: TrainingProgram) {
        context.dataStore.edit { prefs ->
            program.phases.forEach { ph ->
                ph.weeks.forEach { w ->
                    w.sessions.forEach { s -> prefs[sessionDoneKey(s.id)] = 0 }
                }
            }
        }
    }

    /** Vrai si toutes les séances de toutes les phases du programme sont cochées. */
    suspend fun isProgramFullyComplete(program: TrainingProgram): Boolean {
        val data = context.dataStore.data.first()
        return program.phases.all { ph ->
            ph.weeks.all { w ->
                w.sessions.all { s -> (data[sessionDoneKey(s.id)] ?: 0) > 0 }
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
        const val SESSIONS_PER_STEP = 4
        const val STEP_KG = 1.5

        /** Charge ajustée selon le nombre de séances effectuées sur cet exercice. */
        fun progressedLoad(baseLoadKg: Double?, completedCount: Int): Double? {
            if (baseLoadKg == null) return null
            val steps = completedCount / SESSIONS_PER_STEP
            return baseLoadKg + STEP_KG * steps
        }

        /** Nombre de séances restantes avant le prochain palier +1,5 kg. */
        fun sessionsBeforeNextStep(completedCount: Int): Int =
            SESSIONS_PER_STEP - (completedCount % SESSIONS_PER_STEP)
    }
}
