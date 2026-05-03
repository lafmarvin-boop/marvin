package com.marvin.cryptobot.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.marvin.cryptobot.Routes

@Composable
fun BottomBar(currentRoute: String, onNavigate: (String) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = currentRoute == Routes.DASHBOARD,
            onClick = { onNavigate(Routes.DASHBOARD) },
            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
            label = { Text("Tableau") },
        )
        NavigationBarItem(
            selected = currentRoute == Routes.HISTORY,
            onClick = { onNavigate(Routes.HISTORY) },
            icon = { Icon(Icons.Filled.History, contentDescription = null) },
            label = { Text("Historique") },
        )
        NavigationBarItem(
            selected = currentRoute == Routes.SETTINGS,
            onClick = { onNavigate(Routes.SETTINGS) },
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            label = { Text("Réglages") },
        )
    }
}
