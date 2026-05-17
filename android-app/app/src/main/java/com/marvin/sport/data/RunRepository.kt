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

    /** 25 / 50 / 75 / 100 — paliers franchis (free run target ou bloc programme). */
    private val _milestoneEvents = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val milestoneEvents: SharedFlow<Int> = _milestoneEvents.asSharedFlow()

    /** Émis à chaque transition de bloc d'une séance programme (index du bloc qui se termine). */
    private val _blockEndEvents = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val blockEndEvents: SharedFlow<Int> = _blockEndEvents.asSharedFlow()

    @Volatile private var nextTargetM: Double? = null
    @Volatile private var nextProgramBlocks: List<RunBlock>? = null

    // --- État interne du filtre GPS ---
    @Volatile private var warmupCount: Int = 0
    @Volatile private var lastAcceptedAccuracy: Float? = null

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

    fun setNextTarget(targetM: Double?) { nextTargetM = targetM }
    fun consumeNextTarget(): Double? { val t = nextTargetM; nextTargetM = null; return t }

    fun setNextProgram(blocks: List<RunBlock>?) { nextProgramBlocks = blocks }
    fun consumeNextProgram(): List<RunBlock>? { val b = nextProgramBlocks; nextProgramBlocks = null; return b }

    fun startRun(targetM: Double? = null, programBlocks: List<RunBlock>? = null) {
        val now = System.currentTimeMillis()
        warmupCount = 0
        lastAcceptedAccuracy = null
        _currentRun.value = LiveRun(
            startedAt = now,
            lastUpdateMs = now,
            targetM = targetM,
            programBlocks = programBlocks?.takeIf { it.isNotEmpty() },
            currentBlockStartMs = now,
            currentBlockStartDistanceM = 0.0,
        )
    }

    /**
     * Pipeline GPS multi-couche :
     *   1) Warm-up : on ignore les 2 premiers fixes (souvent imprécis à froid)
     *   2) Seuil de précision adaptatif (18 m strict / 30 m après 10 s sans fix)
     *   3) Lissage EMA pondéré par la précision
     *   4) Filtres vitesse (> 8 m/s) et anti-jitter (< 1.5 m sous 4 s)
     */
    fun addPoint(lat: Double, lng: Double, altitude: Double, accuracyM: Float) {
        val live = _currentRun.value ?: return
        val now = System.currentTimeMillis()

        // Rafraîchir la précision affichée même si on rejette ce point
        _currentRun.value = live.copy(currentAccuracyM = accuracyM, lastFixAtMs = now)

        // 1) Warm-up : on ignore les premiers fixes pour éviter le bruit de démarrage.
        if (warmupCount < 2) {
            warmupCount += 1
            return
        }

        // 2) Seuil adaptatif : strict par défaut, on s'élargit s'il y a un trou de signal.
        val sinceLast = live.lastFixAtMs?.let { now - it } ?: Long.MAX_VALUE
        val maxAcceptable: Float = if (sinceLast > 10_000) 30f else 18f
        if (accuracyM > maxAcceptable) return

        // 3) Lissage EMA pondéré par la précision relative.
        val previous = live.points.lastOrNull()
        val (smoothedLat, smoothedLng, smoothedAlt) = if (previous != null) {
            val alpha = (10f / accuracyM).coerceIn(0.3f, 0.85f).toDouble()
            Triple(
                alpha * lat + (1 - alpha) * previous.lat,
                alpha * lng + (1 - alpha) * previous.lng,
                alpha * altitude + (1 - alpha) * previous.altitude,
            )
        } else {
            Triple(lat, lng, altitude)
        }

        // 4) Distance + filtres temporels
        val delta = if (previous != null) {
            val d = RunStats.haversineM(previous.lat, previous.lng, smoothedLat, smoothedLng)
            val dtMs = (now - previous.timestampMs).coerceAtLeast(1L)
            val speedMs = d / (dtMs / 1000.0)
            when {
                speedMs > 8.0 -> 0.0                 // anti-saut (>28 km/h)
                d < 1.5 && dtMs < 4_000 -> 0.0       // anti-jitter sur intervalle court
                else -> d
            }
        } else 0.0

        lastAcceptedAccuracy = accuracyM
        val point = RunPoint(lat = smoothedLat, lng = smoothedLng, altitude = smoothedAlt, timestampMs = now)
        val newDistance = live.distanceM + delta

        // -------- Mode "course libre avec objectif" --------
        val updatedMilestones = live.milestonesReached.toMutableSet()
        if (live.programBlocks == null && live.targetM != null && live.targetM > 0) {
            val pct = (newDistance / live.targetM * 100).toInt()
            MILESTONES.forEach { milestone ->
                if (pct >= milestone && milestone !in updatedMilestones) {
                    updatedMilestones += milestone
                    _milestoneEvents.tryEmit(milestone)
                }
            }
        }

        // -------- Mode "séance programme" : suivi bloc courant --------
        var updatedBlockIndex = live.currentBlockIndex
        var updatedBlockStartMs = live.currentBlockStartMs
        var updatedBlockStartDist = live.currentBlockStartDistanceM
        var updatedBlockMilestones = live.currentBlockMilestonesReached
        val blocks = live.programBlocks
        if (blocks != null && updatedBlockIndex < blocks.size) {
            val block = blocks[updatedBlockIndex]
            if (block.isDistanceBased && block.trackingDistanceM > 0) {
                val elapsed = newDistance - updatedBlockStartDist
                val target = block.trackingDistanceM.toDouble()
                val (newSet, newIdx, newStartMs, newStartDist, transitionEnded) = advanceBlock(
                    elapsed = elapsed, target = target,
                    reached = updatedBlockMilestones,
                    currentIndex = updatedBlockIndex, totalBlocks = blocks.size,
                    nowMs = now, currentDistance = newDistance,
                )
                updatedBlockMilestones = newSet
                if (newIdx != updatedBlockIndex) {
                    _blockEndEvents.tryEmit(updatedBlockIndex)
                    updatedBlockIndex = newIdx
                    updatedBlockStartMs = newStartMs
                    updatedBlockStartDist = newStartDist
                    updatedBlockMilestones = emptySet()
                }
            }
        }

        _currentRun.value = live.copy(
            points = live.points + point,
            distanceM = newDistance,
            lastUpdateMs = now,
            milestonesReached = updatedMilestones,
            currentBlockIndex = updatedBlockIndex,
            currentBlockStartMs = updatedBlockStartMs,
            currentBlockStartDistanceM = updatedBlockStartDist,
            currentBlockMilestonesReached = updatedBlockMilestones,
            currentAccuracyM = accuracyM,
            lastFixAtMs = now,
        )
    }

    /** Avance temps-based des blocs : appelé périodiquement depuis le service. */
    fun tick(nowMs: Long) {
        val live = _currentRun.value ?: return
        val blocks = live.programBlocks ?: return
        if (live.currentBlockIndex >= blocks.size) return
        val block = blocks[live.currentBlockIndex]
        if (block.isDistanceBased) return  // géré par addPoint

        val durationSec = block.trackingDurationSec
        if (durationSec <= 0) return
        val elapsedSec = (nowMs - live.currentBlockStartMs) / 1000.0

        var updatedBlockMilestones = live.currentBlockMilestonesReached
        var updatedIndex = live.currentBlockIndex
        var updatedStartMs = live.currentBlockStartMs
        var updatedStartDist = live.currentBlockStartDistanceM

        val (newSet, newIdx, newStartMs, newStartDist, _) = advanceBlock(
            elapsed = elapsedSec, target = durationSec.toDouble(),
            reached = updatedBlockMilestones,
            currentIndex = updatedIndex, totalBlocks = blocks.size,
            nowMs = nowMs, currentDistance = live.distanceM,
        )
        updatedBlockMilestones = newSet
        if (newIdx != updatedIndex) {
            _blockEndEvents.tryEmit(updatedIndex)
            updatedIndex = newIdx
            updatedStartMs = newStartMs
            updatedStartDist = newStartDist
            updatedBlockMilestones = emptySet()
        }

        _currentRun.value = live.copy(
            lastUpdateMs = nowMs,
            currentBlockIndex = updatedIndex,
            currentBlockStartMs = updatedStartMs,
            currentBlockStartDistanceM = updatedStartDist,
            currentBlockMilestonesReached = updatedBlockMilestones,
        )
    }

    /**
     * Calcule les paliers franchis dans le bloc en cours et déclenche la transition
     * si 100 % atteint. Renvoie un tuple (paliersMisAJour, nextIndex, nextStartMs,
     * nextStartDistance, ended).
     */
    private fun advanceBlock(
        elapsed: Double,
        target: Double,
        reached: Set<Int>,
        currentIndex: Int,
        totalBlocks: Int,
        nowMs: Long,
        currentDistance: Double,
    ): MileResult {
        val pct = (elapsed / target * 100).toInt()
        val newReached = reached.toMutableSet()
        // Paliers internes 25/50/75
        listOf(25, 50, 75).forEach { m ->
            if (pct >= m && m !in newReached) {
                newReached += m
                _milestoneEvents.tryEmit(m)
            }
        }
        return if (pct >= 100) {
            // Bloc terminé : on avance
            val nextIdx = (currentIndex + 1).coerceAtMost(totalBlocks)
            MileResult(emptySet(), nextIdx, nowMs, currentDistance, ended = true)
        } else {
            MileResult(newReached, currentIndex, 0L, 0.0, ended = false)
        }
    }

    private data class MileResult(
        val milestones: Set<Int>,
        val nextIndex: Int,
        val nextStartMs: Long,
        val nextStartDist: Double,
        val ended: Boolean,
    )

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
