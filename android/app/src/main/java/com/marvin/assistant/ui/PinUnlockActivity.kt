package com.marvin.assistant.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marvin.assistant.util.Settings
import kotlin.math.max

/**
 * Écran d'authentification par PIN. Lancé en pré-requis de
 * [SettingsActivity] quand un PIN est configuré.
 *
 * Compteur d'échecs en mémoire (reset au reboot de l'activité).
 * 5 échecs → lockout 30 secondes.
 */
class PinUnlockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        if (!settings.isPinSet()) {
            // Pas de PIN: passe direct
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PinScreen(
                        check = { settings.checkPin(it) },
                        onSuccess = {
                            startActivity(Intent(this@PinUnlockActivity, SettingsActivity::class.java))
                            finish()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PinScreen(check: (String) -> Boolean, onSuccess: () -> Unit) {
    var entered by remember { mutableStateOf("") }
    var failures by remember { mutableStateOf(0) }
    var lockedUntilMs by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }

    val now = System.currentTimeMillis()
    val locked = now < lockedUntilMs
    val remainSec = if (locked) ((lockedUntilMs - now) / 1000) + 1 else 0

    fun submit() {
        if (locked) return
        if (check(entered)) {
            onSuccess()
        } else {
            failures += 1
            entered = ""
            error = "PIN incorrect."
            if (failures >= 5) {
                lockedUntilMs = System.currentTimeMillis() + 30_000
                error = "Trop d'essais. Verrouillé 30 s."
                failures = 0
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Marvin — Réglages", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("Entre ton PIN", color = Color(0xFF607D8B))
        Spacer(Modifier.height(24.dp))

        // Dots indicator
        Row(horizontalArrangement = Arrangement.Center) {
            for (i in 0 until max(entered.length, 4)) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(
                            if (i < entered.length) MaterialTheme.colorScheme.primary
                            else Color(0xFFCFD8DC)
                        )
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        if (error != null) {
            Text(error!!, color = Color(0xFFB71C1C))
            Spacer(Modifier.height(8.dp))
        }
        if (locked) {
            Text("Réessaie dans ${remainSec} s", color = Color(0xFFB71C1C))
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(20.dp))
        Numpad(
            enabled = !locked,
            onDigit = { d ->
                if (entered.length < 6) {
                    entered += d
                    error = null
                    if (entered.length >= 4) {
                        // Auto-submit si 4 chiffres et match — ou 6 chiffres
                        if (check(entered)) onSuccess()
                    }
                }
            },
            onBackspace = { if (entered.isNotEmpty()) entered = entered.dropLast(1) },
            onValidate = { submit() }
        )
    }
}

@Composable
private fun Numpad(
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onValidate: () -> Unit
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("⌫", "0", "✓")
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        for (row in rows) {
            Row {
                for (cell in row) {
                    Button(
                        onClick = {
                            when (cell) {
                                "⌫" -> onBackspace()
                                "✓" -> onValidate()
                                else -> onDigit(cell[0])
                            }
                        },
                        enabled = enabled,
                        modifier = Modifier
                            .padding(6.dp)
                            .size(72.dp)
                    ) {
                        Text(cell, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
