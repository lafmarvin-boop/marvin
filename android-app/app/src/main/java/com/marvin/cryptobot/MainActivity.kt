package com.marvin.cryptobot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.marvin.cryptobot.ui.component.BottomBar
import com.marvin.cryptobot.ui.screens.DashboardScreen
import com.marvin.cryptobot.ui.screens.HistoryScreen
import com.marvin.cryptobot.ui.screens.SettingsScreen
import com.marvin.cryptobot.ui.theme.CryptoBotTheme
import com.marvin.cryptobot.viewmodel.MainViewModel
import com.marvin.cryptobot.viewmodel.MainViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CryptoBotTheme {
                AppRoot()
            }
        }
    }
}

@Composable
fun AppRoot() {
    val app = CryptoBotApp.instance
    val vm: MainViewModel = viewModel(factory = MainViewModelFactory(app.container))
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: Routes.DASHBOARD

    Scaffold(
        bottomBar = { BottomBar(currentRoute) { navController.navigateTopLevel(it) } }
    ) { inner ->
        NavHost(
            navController = navController,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier.padding(inner)
        ) {
            composable(Routes.DASHBOARD) { DashboardScreen(vm) }
            composable(Routes.HISTORY) { HistoryScreen(vm) }
            composable(Routes.SETTINGS) { SettingsScreen(vm) }
        }
    }
}

object Routes {
    const val DASHBOARD = "dashboard"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
}

private fun NavHostController.navigateTopLevel(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
