package com.marvin.sport.ui.screens.running

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.marvin.sport.data.AlertMode
import com.marvin.sport.data.TimerConfig
import com.marvin.sport.data.TimerStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class TimerPhase { Idle, Prepare, Work, Rest, SetRest, Done }

@Composable
fun TimerScreen() {
    val context = LocalContext.current
    val store = remember { TimerStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val savedConfig by store.configFlow().collectAsState(initial = TimerConfig())

    var workingConfig by remember(savedConfig) { mutableStateOf(savedConfig) }
    var running by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(TimerPhase.Idle) }
    var phaseSecondsLeft by remember { mutableStateOf(0) }
    var currentRound by remember { mutableStateOf(1) }
    var currentSet by remember { mutableStateOf(1) }

    LaunchedEffect(running, paused, phase) {
        if (!running || paused) return@LaunchedEffect
        while (running && !paused && phaseSecondsLeft > 0) {
            delay(1000)
            phaseSecondsLeft -= 1
        }
        if (running && !paused && phaseSecondsLeft <= 0) {
            advancePhase(
                config = workingConfig,
                currentPhase = phase,
                currentRound = currentRound,
                currentSet = currentSet,
            ) { next ->
                phase = next.phase
                phaseSecondsLeft = next.seconds
                currentRound = next.round
                currentSet = next.set
                alert(context, mode = workingConfig.alertMode, strong = next.phase == TimerPhase.Work || next.phase == TimerPhase.Done)
                if (next.phase == TimerPhase.Done) running = false
            }
        }
    }

    if (running) {
        TimerRunningView(
            phase = phase,
            secondsLeft = phaseSecondsLeft,
            totalForPhase = totalSecondsFor(phase, workingConfig),
            currentRound = currentRound,
            currentSet = currentSet,
            config = workingConfig,
            paused = paused,
            onPauseResume = { paused = !paused },
            onStop = {
                running = false
                paused = false
                phase = TimerPhase.Idle
            },
        )
    } else {
        TimerConfigView(
            config = workingConfig,
            onChange = { c ->
                workingConfig = c
                scope.launch { store.save(c) }
            },
            onStart = {
                if (workingConfig.prepareSec > 0) {
                    phase = TimerPhase.Prepare
                    phaseSecondsLeft = workingConfig.prepareSec
                } else {
                    phase = TimerPhase.Work
                    phaseSecondsLeft = workingConfig.workSec
                }
                currentRound = 1
                currentSet = 1
                running = true
                paused = false
                alert(context, mode = workingConfig.alertMode, strong = true)
            },
        )
    }
}

private data class PhaseTransition(
    val phase: TimerPhase,
    val seconds: Int,
    val round: Int,
    val set: Int,
)

private fun advancePhase(
    config: TimerConfig,
    currentPhase: TimerPhase,
    currentRound: Int,
    currentSet: Int,
    apply: (PhaseTransition) -> Unit,
) {
    when (currentPhase) {
        TimerPhase.Prepare -> apply(
            PhaseTransition(TimerPhase.Work, config.workSec, currentRound, currentSet)
        )

        TimerPhase.Work -> {
            val lastRound = currentRound >= config.rounds
            if (!lastRound) {
                apply(PhaseTransition(TimerPhase.Rest, config.restSec, currentRound, currentSet))
            } else {
                val lastSet = currentSet >= config.sets
                if (!lastSet && config.setRestSec > 0) {
                    apply(PhaseTransition(TimerPhase.SetRest, config.setRestSec, currentRound, currentSet))
                } else if (!lastSet) {
                    apply(PhaseTransition(TimerPhase.Work, config.workSec, 1, currentSet + 1))
                } else {
                    apply(PhaseTransition(TimerPhase.Done, 0, currentRound, currentSet))
                }
            }
        }

        TimerPhase.Rest -> apply(
            PhaseTransition(TimerPhase.Work, config.workSec, currentRound + 1, currentSet)
        )

        TimerPhase.SetRest -> apply(
            PhaseTransition(TimerPhase.Work, config.workSec, 1, currentSet + 1)
        )

        else -> Unit
    }
}

private fun totalSecondsFor(phase: TimerPhase, c: TimerConfig): Int = when (phase) {
    TimerPhase.Prepare -> c.prepareSec
    TimerPhase.Work -> c.workSec
    TimerPhase.Rest -> c.restSec
    TimerPhase.SetRest -> c.setRestSec
    else -> 1
}

@Composable
private fun TimerConfigView(
    config: TimerConfig,
    onChange: (TimerConfig) -> Unit,
    onStart: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Timer fractionné",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Configure tes intervalles : préparation, travail, repos, nombre de rounds. Tu peux aussi enchaîner plusieurs sets avec un repos long entre.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item { Stepper("Préparation (s)", config.prepareSec, 0, 60, 5) { onChange(config.copy(prepareSec = it)) } }
        item { Stepper("Travail (s)", config.workSec, 5, 600, 5) { onChange(config.copy(workSec = it)) } }
        item { Stepper("Repos (s)", config.restSec, 0, 600, 5) { onChange(config.copy(restSec = it)) } }
        item { Stepper("Rounds par set", config.rounds, 1, 50, 1) { onChange(config.copy(rounds = it)) } }
        item { Stepper("Nombre de sets", config.sets, 1, 20, 1) { onChange(config.copy(sets = it)) } }
        item {
            if (config.sets > 1) {
                Stepper("Repos entre sets (s)", config.setRestSec, 0, 600, 10) {
                    onChange(config.copy(setRestSec = it))
                }
            }
        }
        item {
            AlertModeSelector(
                mode = config.alertMode,
                onChange = { onChange(config.copy(alertMode = it)) },
            )
        }
        item { TotalSummary(config) }
        item {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Démarrer")
            }
        }
    }
}

@Composable
private fun Stepper(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    onChange: (Int) -> Unit,
) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = { onChange((value - step).coerceAtLeast(min)) },
                enabled = value > min,
            ) { Text("−") }
            Box(
                modifier = Modifier.width(64.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    value.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            OutlinedButton(
                onClick = { onChange((value + step).coerceAtMost(max)) },
                enabled = value < max,
            ) { Text("+") }
        }
    }
}

@Composable
private fun TotalSummary(c: TimerConfig) {
    val perSet = c.prepareSec.takeIf { it > 0 }?.let { it } ?: 0
    val workRest = c.rounds * c.workSec + (c.rounds - 1).coerceAtLeast(0) * c.restSec
    val setRest = (c.sets - 1).coerceAtLeast(0) * c.setRestSec
    val total = perSet + c.sets * workRest + setRest
    val minutes = total / 60
    val seconds = total % 60
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Durée totale estimée", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "%d min %02d s · %d sets × %d rounds".format(minutes, seconds, c.sets, c.rounds),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun TimerRunningView(
    phase: TimerPhase,
    secondsLeft: Int,
    totalForPhase: Int,
    currentRound: Int,
    currentSet: Int,
    config: TimerConfig,
    paused: Boolean,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
) {
    val baseColor = when (phase) {
        TimerPhase.Prepare -> Color(0xFF1E88E5)
        TimerPhase.Work -> Color(0xFF16A34A)
        TimerPhase.Rest -> Color(0xFFF59E0B)
        TimerPhase.SetRest -> Color(0xFF8B5CF6)
        TimerPhase.Done -> Color(0xFF334155)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val animatedColor by animateColorAsState(targetValue = baseColor, label = "phaseColor")
    val progress = if (totalForPhase > 0) {
        (secondsLeft.toFloat() / totalForPhase.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 600),
        label = "progress",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(animatedColor, animatedColor.copy(alpha = 0.85f)),
                )
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.18f))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(
                    phaseLabel(phase),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Set $currentSet/${config.sets}  ·  Round $currentRound/${config.rounds}",
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .size(300.dp)
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = 18f
                    val diameter = size.minDimension - stroke
                    val topLeft = Offset(
                        x = (size.width - diameter) / 2f,
                        y = (size.height - diameter) / 2f,
                    )
                    val arcSize = Size(diameter, diameter)
                    // Track
                    drawArc(
                        color = Color.White.copy(alpha = 0.22f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    // Progress
                    drawArc(
                        color = Color.White,
                        startAngle = -90f,
                        sweepAngle = 360f * animatedProgress,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatTime(secondsLeft),
                        color = Color.White,
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "sur $totalForPhase s",
                        color = Color.White.copy(alpha = 0.78f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onPauseResume,
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.2f),
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(
                        if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (paused) "Reprendre" else "Pause", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onStop,
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFB71C1C),
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Arrêter", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun phaseLabel(p: TimerPhase): String = when (p) {
    TimerPhase.Prepare -> "PRÉPARATION"
    TimerPhase.Work -> "TRAVAIL"
    TimerPhase.Rest -> "REPOS"
    TimerPhase.SetRest -> "REPOS LONG (fin de set)"
    TimerPhase.Done -> "TERMINÉ"
    else -> ""
}

private fun formatTime(s: Int): String {
    val m = s / 60
    val r = s % 60
    return if (m > 0) "%d:%02d".format(m, r) else "%02d".format(r)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlertModeSelector(mode: AlertMode, onChange: (AlertMode) -> Unit) {
    val options = listOf(
        AlertMode.Vibration to "Vibration",
        AlertMode.Beep to "Bip",
        AlertMode.Both to "Les deux",
    )
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Alerte de transition", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = mode == value,
                        onClick = { onChange(value) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    ) { Text(label) }
                }
            }
        }
    }
}

private fun alert(context: Context, mode: AlertMode, strong: Boolean) {
    if (mode == AlertMode.Vibration || mode == AlertMode.Both) {
        vibrate(context, strong)
    }
    if (mode == AlertMode.Beep || mode == AlertMode.Both) {
        beep(strong)
    }
}

@Suppress("DEPRECATION")
private fun vibrate(context: Context, strong: Boolean) {
    val source: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        mgr?.defaultVibrator
    } else {
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    val vibrator = source ?: return
    val durations = if (strong) longArrayOf(0, 250, 100, 250) else longArrayOf(0, 150)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createWaveform(durations, -1))
    } else {
        vibrator.vibrate(durations, -1)
    }
}

/** Bip sur le canal Musique (audible avec écouteurs Bluetooth pendant la course). */
private fun beep(strong: Boolean) {
    val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull() ?: return
    val toneType = if (strong) ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD else ToneGenerator.TONE_PROP_BEEP
    val duration = if (strong) 400 else 200
    tone.startTone(toneType, duration)
    Handler(Looper.getMainLooper()).postDelayed({ runCatching { tone.release() } }, (duration + 150).toLong())
}
