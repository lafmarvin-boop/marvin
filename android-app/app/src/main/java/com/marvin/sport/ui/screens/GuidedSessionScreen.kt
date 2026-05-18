package com.marvin.sport.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marvin.sport.data.AlertSound
import com.marvin.sport.data.CustomLoadStore
import com.marvin.sport.data.Exercise
import com.marvin.sport.data.ExerciseInfoBank
import com.marvin.sport.data.OneRepMaxStore
import com.marvin.sport.data.Phase
import com.marvin.sport.data.ProgressionStore
import com.marvin.sport.data.RepsParser
import com.marvin.sport.data.RestParser
import com.marvin.sport.data.Session
import com.marvin.sport.data.TrainingProgram
import com.marvin.sport.data.Week
import com.marvin.sport.ui.theme.ProgramAccent
import com.marvin.sport.ui.theme.SuccessGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** Phase courante du mode guidé. */
private enum class Phase2 { Active, Resting, Done }

private data class GuidedState(
    val exerciseIdx: Int,
    val setIdx: Int,
    val phase: Phase2,
    val restLeftSec: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuidedSessionScreen(
    program: TrainingProgram,
    phase: Phase,
    week: Week,
    session: Session,
    store: ProgressionStore,
    oneRm: OneRepMaxStore,
    customLoads: CustomLoadStore,
    onBack: () -> Unit,
) {
    val accent = ProgramAccent.forProgramId(session.programId)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exercises = session.exercises

    var state by remember {
        mutableStateOf(GuidedState(exerciseIdx = 0, setIdx = 1, phase = Phase2.Active, restLeftSec = 0))
    }

    val currentExercise = exercises.getOrNull(state.exerciseIdx)
    val sets = currentExercise?.let { RestParser.toSeriesCount(it.sets) } ?: 1

    // Tick du repos
    LaunchedEffect(state.phase, state.exerciseIdx, state.setIdx) {
        if (state.phase != Phase2.Resting) return@LaunchedEffect
        while (state.restLeftSec > 0 && state.phase == Phase2.Resting) {
            delay(1000)
            state = state.copy(restLeftSec = (state.restLeftSec - 1).coerceAtLeast(0))
        }
        if (state.phase == Phase2.Resting && state.restLeftSec <= 0) {
            AlertSound.vibrate(context, strong = false)
            AlertSound.beep(220)
            state = state.copy(phase = Phase2.Active)
        }
    }

    fun advanceAfterValidate() {
        val exo = exercises.getOrNull(state.exerciseIdx) ?: return
        val totalSets = RestParser.toSeriesCount(exo.sets)
        val restSec = RestParser.toSeconds(exo.rest)
        if (state.setIdx < totalSets) {
            // Plus de séries pour cet exo → repos puis même exo, série suivante
            state = state.copy(
                phase = Phase2.Resting,
                setIdx = state.setIdx + 1,
                restLeftSec = restSec,
            )
        } else {
            // Dernière série de cet exo → exo suivant
            val nextIdx = state.exerciseIdx + 1
            if (nextIdx >= exercises.size) {
                state = state.copy(phase = Phase2.Done)
                scope.launch {
                    store.markSessionDone(session)
                    if (store.isProgramFullyComplete(program)) {
                        store.resetProgram(program)
                    }
                }
            } else {
                state = state.copy(
                    phase = Phase2.Resting,
                    exerciseIdx = nextIdx,
                    setIdx = 1,
                    restLeftSec = restSec,
                )
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Mode guidé", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "${phase.title.substringAfter("— ", phase.title)} · ${week.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quitter")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (currentExercise == null || state.phase == Phase2.Done) {
            DoneView(modifier = Modifier.padding(padding), onBack = onBack)
            return@Scaffold
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 3/4 — état courant (actif ou repos)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(3f)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (state.phase == Phase2.Resting) {
                    RestView(
                        restLeftSec = state.restLeftSec,
                        accent = accent,
                        onSkip = {
                            state = state.copy(phase = Phase2.Active, restLeftSec = 0)
                        },
                    )
                } else {
                    ActiveExerciseView(
                        exercise = currentExercise,
                        currentSet = state.setIdx,
                        totalSets = sets,
                        accent = accent,
                        oneRm = oneRm,
                        customLoads = customLoads,
                        onValidate = { advanceAfterValidate() },
                    )
                }
            }

            // 1/4 — preview du suivant
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                NextPreview(
                    exercises = exercises,
                    state = state,
                    accent = accent,
                )
            }
        }
    }
}

@Composable
private fun ActiveExerciseView(
    exercise: Exercise,
    currentSet: Int,
    totalSets: Int,
    accent: Color,
    oneRm: OneRepMaxStore,
    customLoads: CustomLoadStore,
    onValidate: () -> Unit,
) {
    val rmKey = exercise.oneRmKey
    val rmKg by (rmKey?.let { oneRm.valueFlow(it) } ?: flowOf(0.0))
        .collectAsState(initial = 0.0)
    val customLoad by customLoads.valueFlow(exercise.name).collectAsState(initial = null)
    val effectiveLoad: Double? = customLoad ?: rmKey?.let { rmKg * exercise.percentage }
    val timedDurationSec = RepsParser.toSeconds(exercise.reps)

    Card(
        modifier = Modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "EXERCICE",
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        exercise.name,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
                YouTubeButton(exercise = exercise, accent = accent)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BigStat("Série", "$currentSet / $totalSets", accent)
                BigStat("Reps", exercise.reps, MaterialTheme.colorScheme.onSurface)
                BigStat(
                    "Charge",
                    effectiveLoad?.let { formatKg(it) } ?: "PdC",
                    if (effectiveLoad != null) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (exercise.annotation.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f))
                        .padding(12.dp),
                ) {
                    Text(
                        exercise.annotation,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Soit un timer pour exos en temps, soit un bouton de validation classique
            if (timedDurationSec != null) {
                androidx.compose.runtime.key(exercise.name, currentSet) {
                    TimedExerciseControls(
                        durationSec = timedDurationSec,
                        accent = accent,
                        onComplete = onValidate,
                    )
                }
            } else {
                Button(
                    onClick = onValidate,
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text("VALIDER LA SÉRIE", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

@Composable
private fun TimedExerciseControls(
    durationSec: Int,
    accent: Color,
    onComplete: () -> Unit,
) {
    val context = LocalContext.current
    var running by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableStateOf(durationSec) }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (secondsLeft > 0 && running) {
            delay(1000)
            secondsLeft -= 1
        }
        if (running && secondsLeft <= 0) {
            running = false
            AlertSound.beepStrong(450)
            AlertSound.vibrate(context, strong = true)
            onComplete()
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val mm = secondsLeft / 60
        val ss = secondsLeft % 60
        Text(
            text = "%02d:%02d".format(mm, ss),
            fontSize = 72.sp,
            fontWeight = FontWeight.Black,
            color = if (running) accent else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        if (!running) {
            Button(
                onClick = {
                    secondsLeft = durationSec
                    running = true
                },
                modifier = Modifier.fillMaxWidth().height(72.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("START ($durationSec s)", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
            }
        } else {
            OutlinedButton(
                onClick = {
                    running = false
                    onComplete()
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(20.dp),
            ) {
                Text("Passer", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun YouTubeButton(exercise: Exercise, accent: Color) {
    val context = LocalContext.current
    IconButton(
        onClick = {
            val info = ExerciseInfoBank.lookup(exercise.name)
            val query = java.net.URLEncoder.encode(info.searchQuery, "UTF-8")
            val uri = android.net.Uri.parse("https://www.youtube.com/results?search_query=$query")
            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
        },
        modifier = Modifier
            .size(44.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(accent.copy(alpha = 0.14f)),
    ) {
        Icon(
            Icons.Outlined.PlayCircle,
            contentDescription = "Vidéo YouTube",
            tint = accent,
        )
    }
}

@Composable
private fun RestView(restLeftSec: Int, accent: Color, onSkip: () -> Unit) {
    val mm = restLeftSec / 60
    val ss = restLeftSec % 60
    Card(
        modifier = Modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.10f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "REPOS",
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = "%02d:%02d".format(mm, ss),
                fontSize = 110.sp,
                fontWeight = FontWeight.Black,
                color = accent,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = CircleShape,
            ) {
                Icon(Icons.Filled.FastForward, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Passer le repos", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun NextPreview(exercises: List<Exercise>, state: GuidedState, accent: Color) {
    val (label, body) = when (state.phase) {
        Phase2.Resting -> {
            val exo = exercises.getOrNull(state.exerciseIdx) ?: return
            val totalSets = RestParser.toSeriesCount(exo.sets)
            if (state.setIdx <= totalSets) {
                "Prochaine série" to "${exo.name}  ·  Série ${state.setIdx} / $totalSets  ·  ${exo.reps}"
            } else {
                "Prochain exercice" to "${exo.name}  ·  ${exo.sets} séries  ·  ${exo.reps}"
            }
        }
        Phase2.Active -> {
            val exo = exercises.getOrNull(state.exerciseIdx)
            val totalSets = exo?.let { RestParser.toSeriesCount(it.sets) } ?: 1
            if (exo != null && state.setIdx < totalSets) {
                "Après cette série" to "Repos puis ${exo.name}  ·  Série ${state.setIdx + 1} / $totalSets"
            } else {
                val next = exercises.getOrNull(state.exerciseIdx + 1)
                if (next != null) "Exercice suivant" to "${next.name}  ·  ${next.sets} séries  ·  ${next.reps}"
                else "Bientôt fini" to "Dernière série de la séance"
            }
        }
        Phase2.Done -> "—" to "—"
    }
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.SkipNext, contentDescription = null, tint = accent)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DoneView(modifier: Modifier, onBack: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "🎯",
            style = MaterialTheme.typography.displayLarge,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Séance terminée",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "La séance a été validée automatiquement.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen, contentColor = Color.White),
        ) {
            Text("Retour", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BigStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = color,
        )
    }
}

private fun formatKg(value: Double): String {
    val rounded = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)
    return "$rounded kg"
}
