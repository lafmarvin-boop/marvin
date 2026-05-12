package com.marvin.budget.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.marvin.budget.di.AppContainer
import com.marvin.budget.ui.accounts.AccountsScreen
import com.marvin.budget.ui.accounts.AccountsViewModel
import com.marvin.budget.ui.overview.OverviewScreen
import com.marvin.budget.ui.overview.OverviewViewModel
import com.marvin.budget.ui.transactions.TransactionsScreen
import com.marvin.budget.ui.transactions.TransactionsViewModel

sealed class Tab(val route: String, val label: String, val icon: ImageVector) {
    data object Overview : Tab("overview", "Vue d'ensemble", Icons.Filled.Dashboard)
    data object Accounts : Tab("accounts", "Comptes", Icons.Filled.AccountBalanceWallet)
    data object Transactions : Tab("transactions", "Transactions", Icons.Filled.Receipt)
}

private val tabs = listOf(Tab.Overview, Tab.Accounts, Tab.Transactions)

@Composable
fun BudgetNavHost(container: AppContainer) {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination?.route
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = backStackEntry?.destination?.hierarchy
                            ?.any { it.route == tab.route } == true,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Tab.Overview.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Tab.Overview.route) {
                val vm: OverviewViewModel = viewModel(
                    factory = OverviewViewModel.factory(container.repository)
                )
                OverviewScreen(viewModel = vm)
            }
            composable(Tab.Accounts.route) {
                val vm: AccountsViewModel = viewModel(
                    factory = AccountsViewModel.factory(container.repository)
                )
                AccountsScreen(viewModel = vm, onAccountClick = { id ->
                    navController.navigate("transactions?accountId=$id")
                })
            }
            composable(Tab.Transactions.route) {
                val vm: TransactionsViewModel = viewModel(
                    factory = TransactionsViewModel.factory(container.repository, accountId = null)
                )
                TransactionsScreen(viewModel = vm)
            }
            composable("transactions?accountId={accountId}") { entry ->
                val accountId = entry.arguments?.getString("accountId")
                val vm: TransactionsViewModel = viewModel(
                    key = "tx-$accountId",
                    factory = TransactionsViewModel.factory(container.repository, accountId)
                )
                TransactionsScreen(viewModel = vm)
            }
        }
    }
}
