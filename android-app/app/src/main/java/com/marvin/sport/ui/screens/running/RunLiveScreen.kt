package com.marvin.sport.ui.screens.running

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.marvin.sport.data.RunRepository
import com.marvin.sport.data.RunStats
import com.marvin.sport.service.RunTrackingService
import com.marvin.sport.ui.components.OsmMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunLiveScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val live by RunRepository.currentRun.collectAsState()

    // Tick pour rafraîchir la durée affichée chaque seconde.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(live != null) {
        while (live != null) {
            nowMs = System.currentTimeMillis()
            delay(500)
        }
    }

    var permissionsAsked by remember { mutableStateOf(false) }
    var hasFineLocation by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasNotification by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasFineLocation = (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) ||
            hasFineLocation
        hasNotification = (result[Manifest.permission.POST_NOTIFICATIONS] != false) &&
            hasNotification
        permissionsAsked = true
        if (hasFineLocation && live == null) {
            startTrackingService(context, RunRepository.consumeNextTarget())
        }
    }

    LaunchedEffect(Unit) {
        if (live == null) {
            if (!hasFineLocation || !hasNotification) {
                val perms = buildList {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                permissionLauncher.launch(perms.toTypedArray())
            } else {
                startTrackingService(context, RunRepository.consumeNextTarget())
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Course en cours") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!hasFineLocation && permissionsAsked) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Permission GPS requise",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            "L'app a besoin de l'accès à la localisation précise pour enregistrer ton parcours. Accorde la permission dans les paramètres système.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                return@Column
            }

            val l = live
            val durationMs = if (l != null) (nowMs - l.startedAt).coerceAtLeast(0L) else 0L
            val distance = l?.distanceM ?: 0.0

            StatsBlock(
                distanceM = distance,
                durationMs = durationMs,
                targetM = l?.targetM,
                milestones = l?.milestonesReached ?: emptySet(),
            )

            Card {
                Box(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                    OsmMap(
                        points = l?.points.orEmpty(),
                        modifier = Modifier.fillMaxSize(),
                        centerOnLast = true,
                    )
                }
            }

            if (l != null && l.points.isEmpty()) {
                Text(
                    "Recherche du signal GPS…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Spacer(Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            stopTrackingService(context)
                            RunRepository.stopAndSave(keep = false)
                            onBack()
                        }
                    },
                    modifier = Modifier.weight(1f).height(58.dp),
                    shape = androidx.compose.foundation.shape.CircleShape,
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Abandonner", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {
                        scope.launch {
                            stopTrackingService(context)
                            RunRepository.stopAndSave(keep = true)
                            onBack()
                        }
                    },
                    modifier = Modifier.weight(1f).height(58.dp),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    enabled = l != null && l.points.size >= 2,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = com.marvin.sport.ui.theme.ProgramAccent.Running,
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Enregistrer", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun StatsBlock(distanceM: Double, durationMs: Long, targetM: Double?, milestones: Set<Int>) {
    val accent = com.marvin.sport.ui.theme.ProgramAccent.Running
    Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Distance".uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (targetM != null) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                            .background(accent.copy(alpha = 0.14f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "Objectif ${"%.1f".format(targetM / 1000.0)} km",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                RunStats.formatDistance(distanceM),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.ExtraBold,
                color = accent,
            )
            if (targetM != null && targetM > 0) {
                Spacer(Modifier.height(10.dp))
                ProgressBar(distanceM = distanceM, targetM = targetM, milestones = milestones, accent = accent)
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Durée", RunStats.formatDuration(durationMs))
                Stat("Allure", RunStats.formatPace(durationMs, distanceM))
                Stat("Vitesse", RunStats.formatSpeed(durationMs, distanceM))
            }
        }
    }
}

@Composable
private fun ProgressBar(distanceM: Double, targetM: Double, milestones: Set<Int>, accent: Color) {
    val progress = (distanceM / targetM).coerceIn(0.0, 1.0).toFloat()
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp),
    ) {
        val h = size.height
        val w = size.width
        val radius = h / 2f
        drawRoundRect(
            color = Color(0xFFE5E7EB),
            cornerRadius = CornerRadius(radius, radius),
        )
        drawRoundRect(
            color = accent,
            size = Size(w * progress, h),
            cornerRadius = CornerRadius(radius, radius),
        )
        listOf(25, 50, 75).forEach { pct ->
            val x = w * pct / 100f
            val reached = pct in milestones
            drawCircle(
                color = if (reached) Color.White else Color(0xFF94A3B8),
                radius = h * 0.28f,
                center = Offset(x, h / 2f),
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text("25 %", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text("50 %", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text("75 %", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text(
            "${"%.1f".format(targetM / 1000.0)} km",
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.Bold,
        )
    }
}


@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

private fun startTrackingService(context: Context, targetM: Double?) {
    val intent = Intent(context, RunTrackingService::class.java).apply {
        action = RunTrackingService.ACTION_START
        if (targetM != null) putExtra(RunTrackingService.EXTRA_TARGET_M, targetM)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

private fun stopTrackingService(context: Context) {
    val intent = Intent(context, RunTrackingService::class.java).apply {
        action = RunTrackingService.ACTION_STOP
    }
    context.startService(intent)
}
