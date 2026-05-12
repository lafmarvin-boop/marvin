package com.marvin.sport.ui.screens.running

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.marvin.sport.data.RunRepository
import com.marvin.sport.data.RunStats
import com.marvin.sport.service.RunTrackingService
import com.marvin.sport.ui.components.RunPathCanvas
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
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasFineLocation = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
            || hasFineLocation
        hasNotification = result[Manifest.permission.POST_NOTIFICATIONS] != false
            && hasNotification
        permissionsAsked = true
        if (hasFineLocation && live == null) startTrackingService(context)
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
                startTrackingService(context)
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

            StatsBlock(distanceM = distance, durationMs = durationMs)

            Card {
                Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                    RunPathCanvas(points = l?.points.orEmpty(), modifier = Modifier.fillMaxSize())
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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            stopTrackingService(context)
                            RunRepository.stopAndSave(keep = false)
                            onBack()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Abandonner")
                }
                Button(
                    onClick = {
                        scope.launch {
                            stopTrackingService(context)
                            RunRepository.stopAndSave(keep = true)
                            onBack()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = l != null && l.points.size >= 2,
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Enregistrer")
                }
            }
        }
    }
}

@Composable
private fun StatsBlock(distanceM: Double, durationMs: Long) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                RunStats.formatDistance(distanceM),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Durée", RunStats.formatDuration(durationMs))
                Stat("Allure", RunStats.formatPace(durationMs, distanceM))
                Stat("Vitesse", RunStats.formatSpeed(durationMs, distanceM))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

private fun startTrackingService(context: Context) {
    val intent = Intent(context, RunTrackingService::class.java).apply {
        action = RunTrackingService.ACTION_START
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
