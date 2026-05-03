package com.marvin.assistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.marvin.assistant.service.AssistantService

class MainActivity : ComponentActivity() {

    private val requiredPermissions: Array<String>
        get() {
            val perms = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms += Manifest.permission.POST_NOTIFICATIONS
            }
            return perms.toTypedArray()
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result handled in UI by re-checking */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        onRequestPermissions = { permissionLauncher.launch(requiredPermissions) },
                        onOpenAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onStartService = { AssistantService.start(this) },
                        onStopService = { AssistantService.stop(this) },
                        hasPermissions = { hasAllPermissions() }
                    )
                }
            }
        }
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
private fun HomeScreen(
    onRequestPermissions: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    hasPermissions: () -> Boolean
) {
    var permsOk by remember { mutableStateOf(hasPermissions()) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Marvin", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text("Dis « yo poto » pour me parler.")
        Spacer(Modifier.height(24.dp))

        Button(onClick = {
            onRequestPermissions()
            permsOk = hasPermissions()
        }) {
            Text(if (permsOk) "Permissions OK" else "Accorder les permissions")
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onOpenAccessibilitySettings) {
            Text("Activer le service d'accessibilité")
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onStartService) { Text("Démarrer Marvin") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onStopService) { Text("Arrêter Marvin") }
    }
}
