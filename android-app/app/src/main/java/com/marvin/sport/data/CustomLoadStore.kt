package com.marvin.sport.data

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.customLoadsDataStore by preferencesDataStore(name = "marvin_custom_loads")

/**
 * Charge personnalisée par exercice : override permanent de la valeur calculée
 * depuis le 1RM (ou ajout d'une charge à un exercice au poids du corps).
 * Si la valeur n'est pas définie ici, la charge affichée vient du 1RM × percentage,
 * ou reste vide pour les exercices sans charge.
 */
class CustomLoadStore(private val context: Context) {

    private fun key(name: String) = doublePreferencesKey("load_$name")

    fun valueFlow(name: String): Flow<Double?> =
        context.customLoadsDataStore.data.map { it[key(name)] }

    suspend fun set(name: String, kg: Double) {
        context.customLoadsDataStore.edit { it[key(name)] = kg }
    }

    suspend fun clear(name: String) {
        context.customLoadsDataStore.edit { it.remove(key(name)) }
    }
}
