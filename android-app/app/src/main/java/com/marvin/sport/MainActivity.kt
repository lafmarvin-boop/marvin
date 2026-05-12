package com.marvin.sport

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
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
import com.marvin.sport.ui.theme.MarvinSportTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ProgressionStore(applicationContext)
        setContent {
            MarvinSportTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val nav = rememberNavController()
                    NavHost(navController = nav, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                program = Program.program,
                                store = store,
                                onPhaseClick = { p -> nav.navigate("phase/$p") },
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
                    }
                }
            }
        }
    }
}
