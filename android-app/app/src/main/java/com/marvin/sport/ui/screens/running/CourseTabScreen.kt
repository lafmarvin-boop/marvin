package com.marvin.sport.ui.screens.running

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@Composable
fun CourseTabScreen(
    onStartRun: () -> Unit,
    onRunClick: (String) -> Unit,
) {
    var selected by remember { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = selected,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Tab(
                selected = selected == 0,
                onClick = { selected = 0 },
                text = { Text("GPS") },
                icon = { Icon(Icons.Filled.DirectionsRun, contentDescription = null) },
            )
            Tab(
                selected = selected == 1,
                onClick = { selected = 1 },
                text = { Text("Timer fractionné") },
                icon = { Icon(Icons.Filled.Timer, contentDescription = null) },
            )
        }
        Box(modifier = Modifier.fillMaxSize()) {
            when (selected) {
                0 -> RunHomeScreen(onStartRun = onStartRun, onRunClick = onRunClick)
                1 -> TimerScreen()
            }
        }
    }
}
