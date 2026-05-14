package com.marvin.sport.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.runDataStore by preferencesDataStore(name = "marvin_runs")
private val SAVED_RUNS_KEY = stringPreferencesKey("saved_runs_json")

private val MILESTONES = listOf(25, 50, 75, 100)

object RunRepository {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Context
    @Volatile private var initialized = false

    private val _currentRun = MutableStateFlow<LiveRun?>(null)
    val currentRun: StateFlow<LiveRun?> = _currentRun.asStateFlow()

    private val _savedRuns = MutableStateFlow<List<Run>>(emptyList())
    val savedRuns: StateFlow<List<Run>> = _savedRuns.asStateFlow()

    private val _milestoneEvents = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    /** Émet 25 / 50 / 75 / 100 dès qu'un palier de la course en cours est franchi. */
    val milestoneEvents: SharedFlow<Int> = _milestoneEvents.asSharedFlow()

    /** Cible passée par la home / programme avant la navigation vers l'écran live. */
    @Volatile private var nextTargetM: Double? = null

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

    fun setNextTarget(targetM: Double?) {
        nextTargetM = targetM
    }

    fun consumeNextTarget(): Double? {
        val t = nextTargetM
        nextTargetM = null
        return t
    }

    fun startRun(targetM: Double? = null) {
        val now = System.currentTimeMillis()
        _currentRun.value = LiveRun(startedAt = now, lastUpdateMs = now, targetM = targetM)
    }

    /** Ajoute un point GPS et déclenche les paliers de distance s'il y a un objectif. */
    fun addPoint(lat: Double, lng: Double, altitude: Double, accuracyM: Float) {
        val live = _currentRun.value ?: return
        if (accuracyM > 30f) return

        val now = System.currentTimeMillis()
        val point = RunPoint(lat = lat, lng = lng, altitude = altitude, timestampMs = now)

        val previous = live.points.lastOrNull()
        val delta = if (previous != null) {
            val d = RunStats.haversineM(previous.lat, previous.lng, lat, lng)
            val dtMs = now - previous.timestampMs
            if (d > 80 && dtMs < 5_000) 0.0 else d
        } else 0.0

        val newDistance = live.distanceM + delta

        // Détection des paliers : on ne déclenche un palier qu'une seule fois.
        val newlyReached = mutableListOf<Int>()
        val updatedMilestones = live.milestonesReached.toMutableSet()
        if (live.targetM != null && live.targetM > 0) {
            val pct = (newDistance / live.targetM * 100).toInt()
            MILESTONES.forEach { milestone ->
                if (pct >= milestone && milestone !in updatedMilestones) {
                    updatedMilestones += milestone
                    newlyReached += milestone
                }
            }
        }

        _currentRun.value = live.copy(
            points = live.points + point,
            distanceM = newDistance,
            lastUpdateMs = now,
            milestonesReached = updatedMilestones,
        )

        newlyReached.forEach { milestone -> _milestoneEvents.tryEmit(milestone) }
    }

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
            targetM = live.targetM,
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
