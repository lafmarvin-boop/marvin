package com.marvin.sport.data

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.oneRmDataStore by preferencesDataStore(name = "marvin_one_rm")

/**
 * Valeurs 1RM par défaut. L'utilisateur peut les surcharger via l'écran
 * "Charges" et tous les programmes recalculent leur charge à la volée.
 */
object DefaultOneRm {

    val groups: List<OneRmGroup> = listOf(
        OneRmGroup(
            label = "Force barre",
            entries = listOf(
                OneRmEntry("Back Squat", 100.0),
                OneRmEntry("Front Squat", 80.0),
                OneRmEntry("Deadlift", 140.0),
                OneRmEntry("DVP couché", 70.0),
                OneRmEntry("DVP Incliné", 70.0),
                OneRmEntry("Barbell Row", 60.0),
                OneRmEntry("Power clean", 70.0),
                OneRmEntry("Push press", 55.0),
            ),
        ),
        OneRmGroup(
            label = "Poulies / machines",
            entries = listOf(
                OneRmEntry("Tirage poulie haute", 70.0),
                OneRmEntry("Tirage poulie basse", 60.0),
                OneRmEntry("Écarté poulie", 18.0),
            ),
        ),
        OneRmGroup(
            label = "Jambes / unilatéral",
            entries = listOf(
                OneRmEntry("Fente", 50.0),
            ),
        ),
        OneRmGroup(
            label = "Accessoires",
            entries = listOf(
                OneRmEntry("Curl marteau", 14.0),
                OneRmEntry("Triceps corde", 25.0),
                OneRmEntry("Élévation latérale", 8.0),
                OneRmEntry("Russian twist lesté", 8.0),
                OneRmEntry("Wall sit lesté", 15.0),
            ),
        ),
        OneRmGroup(
            label = "Implements",
            entries = listOf(
                OneRmEntry("Médecine-ball", 6.0),
                OneRmEntry("Kettlebell", 20.0),
                OneRmEntry("Traction lestée", 10.0),
                OneRmEntry("Dip lesté", 10.0),
                OneRmEntry("Sac à dos lesté", 8.0),
            ),
        ),
    )

    val map: Map<String, Double> = groups.flatMap { it.entries }.associate { it.key to it.defaultKg }

    fun keys(): List<String> = map.keys.toList()
}

data class OneRmGroup(val label: String, val entries: List<OneRmEntry>)
data class OneRmEntry(val key: String, val defaultKg: Double)

class OneRepMaxStore(private val context: Context) {

    private fun keyFor(name: String) = doublePreferencesKey("rm_$name")

    fun valueFlow(name: String): Flow<Double> =
        context.oneRmDataStore.data.map { it[keyFor(name)] ?: DefaultOneRm.map[name] ?: 0.0 }

    fun allValuesFlow(): Flow<Map<String, Double>> =
        context.oneRmDataStore.data.map { prefs ->
            DefaultOneRm.map.mapValues { (k, default) -> prefs[keyFor(k)] ?: default }
        }

    suspend fun set(name: String, kg: Double) {
        context.oneRmDataStore.edit { it[keyFor(name)] = kg }
    }

    suspend fun resetAll() {
        context.oneRmDataStore.edit { it.clear() }
    }

    /** Récupère la charge ciblée pour cet exercice (lecture one-shot). */
    suspend fun loadFor(exercise: Exercise): Double? {
        val key = exercise.oneRmKey ?: return null
        val rm = valueFlow(key).first()
        return rm * exercise.percentage
    }
}
