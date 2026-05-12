package com.marvin.sport.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.timerDataStore by preferencesDataStore(name = "marvin_timer")

enum class AlertMode { Vibration, Beep, Both }

data class TimerConfig(
    val prepareSec: Int = 10,
    val workSec: Int = 30,
    val restSec: Int = 15,
    val rounds: Int = 8,
    val sets: Int = 1,
    val setRestSec: Int = 60,
    val alertMode: AlertMode = AlertMode.Vibration,
)

class TimerStore(private val context: Context) {
    private val K_PREP = intPreferencesKey("timer_prep")
    private val K_WORK = intPreferencesKey("timer_work")
    private val K_REST = intPreferencesKey("timer_rest")
    private val K_ROUNDS = intPreferencesKey("timer_rounds")
    private val K_SETS = intPreferencesKey("timer_sets")
    private val K_SET_REST = intPreferencesKey("timer_set_rest")
    private val K_ALERT = stringPreferencesKey("timer_alert_mode")

    fun configFlow(): Flow<TimerConfig> = context.timerDataStore.data.map { p ->
        TimerConfig(
            prepareSec = p[K_PREP] ?: 10,
            workSec = p[K_WORK] ?: 30,
            restSec = p[K_REST] ?: 15,
            rounds = p[K_ROUNDS] ?: 8,
            sets = p[K_SETS] ?: 1,
            setRestSec = p[K_SET_REST] ?: 60,
            alertMode = parseAlert(p[K_ALERT]),
        )
    }

    suspend fun save(c: TimerConfig) {
        context.timerDataStore.edit { p ->
            p[K_PREP] = c.prepareSec
            p[K_WORK] = c.workSec
            p[K_REST] = c.restSec
            p[K_ROUNDS] = c.rounds
            p[K_SETS] = c.sets
            p[K_SET_REST] = c.setRestSec
            p[K_ALERT] = c.alertMode.name
        }
    }

    private fun parseAlert(raw: String?): AlertMode =
        AlertMode.values().firstOrNull { it.name == raw } ?: AlertMode.Vibration
}
