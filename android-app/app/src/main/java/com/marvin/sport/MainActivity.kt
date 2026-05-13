package com.marvin.sport

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.SportsKabaddi
import androidx.compose.material.icons.filled.SportsMma
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.marvin.sport.data.ProgressionStore
import com.marvin.sport.data.Programs
import com.marvin.sport.ui.theme.ProgramAccent
import com.marvin.sport.ui.screens.HomeScreen
import com.marvin.sport.ui.screens.PhaseScreen
import com.marvin.sport.ui.screens.SessionScreen
import com.marvin.sport.ui.screens.WeekScreen
import com.marvin.sport.ui.screens.running.CourseTabScreen
import com.marvin.sport.ui.screens.running.RunDetailScreen
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

private enum class MainTab(val label: String, val icon: ImageVector, val programId: String?) {
    Strength("Muscu", Icons.Filled.FitnessCenter, "strength"),
    Striking("Striking", Icons.Filled.SportsMma, "striking"),
    Grappling("Grappling", Icons.Filled.SportsKabaddi, "grappling"),
    Course("Course", Icons.Filled.DirectionsRun, null),
}

@Composable
private fun AppNavigation(store: ProgressionStore) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "main") {
        composable("main") {
            MainScaffold(
                store = store,
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
                phase = program.phases[p],
                week = program.phases[p].weeks[w],
                session = program.phases[p].weeks[w].sessions[s],
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
    onPhaseClick: (programId: String, phaseIndex: Int) -> Unit,
    onStartRun: () -> Unit,
    onRunClick: (String) -> Unit,
) {
    var tab by remember { mutableStateOf(MainTab.Strength) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
            ) {
                MainTab.values().forEach { item ->
                    val accent = item.programId?.let { ProgramAccent.forProgramId(it) }
                        ?: ProgramAccent.Running
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = androidx.compose.ui.graphics.Color.White,
                            selectedTextColor = accent,
                            indicatorColor = accent,
                            unselectedIconColor = MaterialTheme.colorScheme.outline,
                            unselectedTextColor = MaterialTheme.colorScheme.outline,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        val program = tab.programId?.let { Programs.byId(it) }
        if (program != null) {
            HomeScreen(
                program = program,
                onPhaseClick = onPhaseClick,
                contentPadding = padding,
            )
        } else {
            Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
                CourseTabScreen(onStartRun = onStartRun, onRunClick = onRunClick)
            }
        }
    }
}
