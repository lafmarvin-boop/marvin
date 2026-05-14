package com.marvin.sport

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.marvin.sport.data.OneRepMaxStore
import com.marvin.sport.data.ProgressionStore
import com.marvin.sport.data.Programs
import com.marvin.sport.ui.screens.OneRepMaxScreen
import com.marvin.sport.ui.screens.PhaseScreen
import com.marvin.sport.ui.screens.ProgrammesScreen
import com.marvin.sport.ui.screens.SessionScreen
import com.marvin.sport.ui.screens.WeekScreen
import com.marvin.sport.ui.screens.running.CourseTabScreen
import com.marvin.sport.ui.screens.running.RunDetailScreen
import com.marvin.sport.ui.screens.running.RunLiveScreen
import com.marvin.sport.ui.theme.MarvinSportTheme
import com.marvin.sport.ui.theme.ProgramAccent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ProgressionStore(applicationContext)
        val oneRm = OneRepMaxStore(applicationContext)
        setContent {
            MarvinSportTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation(store = store, oneRm = oneRm)
                }
            }
        }
    }
}

private enum class MainTab(val label: String, val icon: ImageVector, val accent: Color) {
    Programmes("Programmes", Icons.Filled.FitnessCenter, ProgramAccent.Strength),
    Course("Course", Icons.Filled.DirectionsRun, ProgramAccent.Running),
    Charges("Charges", Icons.Filled.MonitorWeight, ProgramAccent.Strength),
}

@Composable
private fun AppNavigation(store: ProgressionStore, oneRm: OneRepMaxStore) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "main") {
        composable("main") {
            MainScaffold(
                store = store,
                oneRm = oneRm,
                onPhaseClick = { programId, p -> nav.navigate("phase/$programId/$p") },
                onStartRun = { nav.navigate("run/live") },
                onRunClick = { id -> nav.navigate("run/detail/$id") },
            )
        }
        composable("phase/{prog}/{p}") { entry ->
            val prog = entry.arguments?.getString("prog").orEmpty()
            val p = entry.arguments?.getString("p")?.toInt() ?: 0
            val program = Programs.byId(prog)
            PhaseScreen(
                program = program,
                phase = program.phases[p],
                onBack = { nav.popBackStack() },
                onWeekClick = { w -> nav.navigate("week/$prog/$p/$w") },
            )
        }
        composable("week/{prog}/{p}/{w}") { entry ->
            val prog = entry.arguments?.getString("prog").orEmpty()
            val p = entry.arguments?.getString("p")?.toInt() ?: 0
            val w = entry.arguments?.getString("w")?.toInt() ?: 0
            val program = Programs.byId(prog)
            WeekScreen(
                phase = program.phases[p],
                week = program.phases[p].weeks[w],
                store = store,
                onBack = { nav.popBackStack() },
                onSessionClick = { s -> nav.navigate("session/$prog/$p/$w/$s") },
            )
        }
        composable("session/{prog}/{p}/{w}/{s}") { entry ->
            val prog = entry.arguments?.getString("prog").orEmpty()
            val p = entry.arguments?.getString("p")?.toInt() ?: 0
            val w = entry.arguments?.getString("w")?.toInt() ?: 0
            val s = entry.arguments?.getString("s")?.toInt() ?: 0
            val program = Programs.byId(prog)
            SessionScreen(
                program = program,
                phase = program.phases[p],
                week = program.phases[p].weeks[w],
                session = program.phases[p].weeks[w].sessions[s],
                store = store,
                oneRm = oneRm,
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
    oneRm: OneRepMaxStore,
    onPhaseClick: (programId: String, phaseIndex: Int) -> Unit,
    onStartRun: () -> Unit,
    onRunClick: (String) -> Unit,
) {
    var tab by remember { mutableStateOf(MainTab.Programmes) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
            ) {
                MainTab.values().forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = item.accent,
                            indicatorColor = item.accent,
                            unselectedIconColor = MaterialTheme.colorScheme.outline,
                            unselectedTextColor = MaterialTheme.colorScheme.outline,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            MainTab.Programmes -> ProgrammesScreen(
                store = store,
                onPhaseClick = onPhaseClick,
                contentPadding = padding,
            )
            MainTab.Course -> Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
                CourseTabScreen(onStartRun = onStartRun, onRunClick = onRunClick)
            }
            MainTab.Charges -> Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
                OneRepMaxScreen(store = oneRm, contentPadding = padding)
            }
        }
    }
}
