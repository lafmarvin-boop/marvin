package com.marvin.budget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.marvin.budget.ui.navigation.BudgetNavHost
import com.marvin.budget.ui.theme.MarvinBudgetTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as BudgetApp).container
        setContent {
            MarvinBudgetTheme {
                BudgetNavHost(container = container)
            }
        }
    }
}
