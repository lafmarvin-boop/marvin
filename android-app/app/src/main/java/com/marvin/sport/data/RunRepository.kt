package com.marvin.sport.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.runDataStore by preferencesDataStore(name = "marvin_runs")
private val SAVED_RUNS_KEY = stringPreferencesKey("saved_runs_json")

/**
 * Dépôt unique de courses :
 *   - état de la course en cours (`currentRun`) alimenté par le service GPS
 *   - liste des courses sauvegardées (`savedRuns`) persistée en DataStore
 *
 * Initialisé une seule fois depuis l'Application class.
 */
object RunRepository {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Context
    @Volatile private var initialized = false

    private val _currentRun = MutableStateFlow<LiveRun?>(null)
    val currentRun: StateFlow<LiveRun?> = _currentRun.asStateFlow()

    private val _savedRuns = MutableStateFlow<List<Run>>(emptyList())
    val savedRuns: StateFlow<List<Run>> = _savedRuns.asStateFlow()

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        initialized = true
        scope.launch { loadSaved() }
    }

    private suspend fun loadSaved() {
        val raw = appContext.runDataStore.data.first()[SAVED_RUNS_KEY].orEmpty()
        if (raw.isBlank()) {
            _savedRuns.value = emptyList()
            return
        }
        runCatching { json.decodeFromString(ListSerializer(Run.serializer()), raw) }
            .onSuccess { _savedRuns.value = it.sortedByDescending { r -> r.startedAt } }
            .onFailure { _savedRuns.value = emptyList() }
    }

    private suspend fun persist() {
        val serialized = json.encodeToString(ListSerializer(Run.serializer()), _savedRuns.value)
        appContext.runDataStore.edit { it[SAVED_RUNS_KEY] = serialized }
    }

    fun startRun() {
        val now = System.currentTimeMillis()
        _currentRun.value = LiveRun(startedAt = now, lastUpdateMs = now)
    }

    /** Ajoute un point GPS, calcule le delta de distance par rapport au dernier point fiable. */
    fun addPoint(lat: Double, lng: Double, altitude: Double, accuracyM: Float) {
        val live = _currentRun.value ?: return
        // Filtrage qualité GPS : on rejette les points trop imprécis.
        if (accuracyM > 30f) return

        val now = System.currentTimeMillis()
        val point = RunPoint(lat = lat, lng = lng, altitude = altitude, timestampMs = now)

        val previous = live.points.lastOrNull()
        val delta = if (previous != null) {
            val d = RunStats.haversineM(previous.lat, previous.lng, lat, lng)
            // Ignore les sauts > 80 m en moins de 5 s (anomalie GPS).
            val dtMs = now - previous.timestampMs
            if (d > 80 && dtMs < 5_000) 0.0 else d
        } else 0.0

        _currentRun.value = live.copy(
            points = live.points + point,
            distanceM = live.distanceM + delta,
            lastUpdateMs = now,
        )
    }

    /** Stoppe la course en cours. Si garder=true, l'enregistre dans la liste. */
    suspend fun stopAndSave(keep: Boolean): Run? {
        val live = _currentRun.value ?: return null
        _currentRun.value = null
        if (!keep || live.points.size < 2) return null
        val run = Run(
            id = UUID.randomUUID().toString(),
            startedAt = live.startedAt,
            endedAt = live.lastUpdateMs,
            durationMs = live.lastUpdateMs - live.startedAt,
            distanceM = live.distanceM,
            points = live.points,
        )
        _savedRuns.value = listOf(run) + _savedRuns.value
        persist()
        return run
    }

    suspend fun deleteRun(id: String) {
        _savedRuns.value = _savedRuns.value.filterNot { it.id == id }
        persist()
    }

    fun runById(id: String): Run? = _savedRuns.value.firstOrNull { it.id == id }
}
