package com.marvin.sport

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.marvin.sport.data.Program
import com.marvin.sport.data.ProgressionStore
import com.marvin.sport.ui.screens.HomeScreen
import com.marvin.sport.ui.screens.PhaseScreen
import com.marvin.sport.ui.screens.SessionScreen
import com.marvin.sport.ui.screens.WeekScreen
import com.marvin.sport.ui.screens.running.RunDetailScreen
import com.marvin.sport.ui.screens.running.RunHomeScreen
import com.marvin.sport.ui.screens.running.RunLiveScreen
import com.marvin.sport.ui.theme.MarvinSportTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ProgressionStore(applicationContext)
        setContent {
            MarvinSportTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation(store = store)
                }
            }
        }
    }
}

private enum class MainTab { Strength, Running }

@Composable
private fun AppNavigation(store: ProgressionStore) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "main") {
        composable("main") {
            MainScaffold(
                store = store,
                onPhaseClick = { p -> nav.navigate("phase/$p") },
                onStartRun = { nav.navigate("run/live") },
                onRunClick = { id -> nav.navigate("run/detail/$id") },
            )
        }
        composable("phase/{p}") { entry ->
            val p = entry.arguments?.getString("p")?.toInt() ?: 0
            PhaseScreen(
                phase = Program.program.phases[p],
                onBack = { nav.popBackStack() },
                onWeekClick = { w -> nav.navigate("week/$p/$w") },
            )
        }
        composable("week/{p}/{w}") { entry ->
            val p = entry.arguments?.getString("p")?.toInt() ?: 0
            val w = entry.arguments?.getString("w")?.toInt() ?: 0
            WeekScreen(
                phase = Program.program.phases[p],
                week = Program.program.phases[p].weeks[w],
                store = store,
                onBack = { nav.popBackStack() },
                onSessionClick = { s -> nav.navigate("session/$p/$w/$s") },
            )
        }
        composable("session/{p}/{w}/{s}") { entry ->
            val p = entry.arguments?.getString("p")?.toInt() ?: 0
            val w = entry.arguments?.getString("w")?.toInt() ?: 0
            val s = entry.arguments?.getString("s")?.toInt() ?: 0
            SessionScreen(
                phase = Program.program.phases[p],
                week = Program.program.phases[p].weeks[w],
                session = Program.program.phases[p].weeks[w].sessions[s],
                store = store,
                onBack = { nav.popBackStack() },
            )
        }
        composable("run/live") {
            RunLiveScreen(onBack = { nav.popBackStack() })
        }
        composable("run/detail/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            RunDetailScreen(runId = id, onBack = { nav.popBackStack() })
        }
    }
}

@Composable
private fun MainScaffold(
    store: ProgressionStore,
    onPhaseClick: (Int) -> Unit,
    onStartRun: () -> Unit,
    onRunClick: (String) -> Unit,
) {
    var tab by remember { mutableStateOf(MainTab.Strength) }
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = tab == MainTab.Strength,
                    onClick = { tab = MainTab.Strength },
                    icon = { Icon(Icons.Filled.FitnessCenter, contentDescription = null) },
                    label = { Text("Musculation") },
                )
                NavigationBarItem(
                    selected = tab == MainTab.Running,
                    onClick = { tab = MainTab.Running },
                    icon = { Icon(Icons.Filled.DirectionsRun, contentDescription = null) },
                    label = { Text("Course") },
                )
            }
        },
    ) { padding ->
        when (tab) {
            MainTab.Strength -> HomeScreen(
                program = Program.program,
                store = store,
                onPhaseClick = onPhaseClick,
                contentPadding = padding,
            )
            MainTab.Running -> Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
                RunHomeScreen(onStartRun = onStartRun, onRunClick = onRunClick)
            }
        }
    }
}
