package com.marvin.budget.ui.overview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marvin.budget.data.repository.CategoryTotal
import com.marvin.budget.data.repository.MonthlyTotals
import com.marvin.budget.ui.format.Format
import com.marvin.budget.ui.theme.ExpenseRed
import com.marvin.budget.ui.theme.IncomeGreen
import kotlin.math.max

@Composable
fun OverviewScreen(viewModel: OverviewViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { HeaderCard(state) }
        item {
            SectionTitle("Entrées et dépenses (6 derniers mois)")
            IncomeExpenseChart(state.monthly)
        }
        item { SectionTitle("Dépenses du mois par catégorie") }
        items(state.categories) { cat ->
            CategoryRow(cat, state.categories.firstOrNull()?.amount ?: 1.0)
        }
        if (state.categories.isEmpty()) {
            item {
                Text(
                    "Aucune dépense ce mois-ci.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HeaderCard(state: OverviewUiState) {
    val thisMonth = state.monthly.lastOrNull()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Patrimoine consolidé", style = MaterialTheme.typography.labelMedium)
            Text(
                Format.money(state.combinedBalance),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "${state.accountsCount} comptes regroupés",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatCell("Entrées ce mois", thisMonth?.income ?: 0.0, IncomeGreen)
                StatCell("Dépenses ce mois", thisMonth?.expense ?: 0.0, ExpenseRed)
                StatCell(
                    "Solde net",
                    thisMonth?.net ?: 0.0,
                    if ((thisMonth?.net ?: 0.0) >= 0) IncomeGreen else ExpenseRed
                )
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: Double, color: Color) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            Format.money(value),
            style = MaterialTheme.typography.titleLarge,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun IncomeExpenseChart(monthly: List<MonthlyTotals>) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val incomeColor = IncomeGreen
    val expenseColor = ExpenseRed

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            ) {
                if (monthly.isEmpty()) return@Canvas

                val maxValue = max(
                    monthly.maxOf { it.income },
                    monthly.maxOf { it.expense }
                ).let { if (it <= 0) 1.0 else it }
                val niceMax = niceCeiling(maxValue)

                val leftPad = 56f
                val rightPad = 12f
                val topPad = 12f
                val bottomPad = 36f

                val plotW = size.width - leftPad - rightPad
                val plotH = size.height - topPad - bottomPad
                val groupW = plotW / monthly.size
                val barW = groupW * 0.32f
                val gap = groupW * 0.06f

                // Grid + Y labels
                val steps = 4
                val paint = android.graphics.Paint().apply {
                    color = axisColor.toArgb()
                    textSize = 28f
                    isAntiAlias = true
                }
                for (i in 0..steps) {
                    val y = topPad + plotH * (1f - i / steps.toFloat())
                    drawLine(
                        color = gridColor,
                        start = Offset(leftPad, y),
                        end = Offset(size.width - rightPad, y),
                        strokeWidth = 1f
                    )
                    val labelVal = (niceMax * i / steps).toInt()
                    drawContext.canvas.nativeCanvas.drawText(
                        formatThousands(labelVal),
                        4f,
                        y + 10f,
                        paint
                    )
                }

                // Bars
                monthly.forEachIndexed { idx, m ->
                    val groupX = leftPad + groupW * idx
                    val incomeH = (m.income / niceMax * plotH).toFloat()
                    val expenseH = (m.expense / niceMax * plotH).toFloat()

                    val incomeX = groupX + groupW / 2 - barW - gap / 2
                    val expenseX = groupX + groupW / 2 + gap / 2

                    drawRoundRect(
                        color = incomeColor,
                        topLeft = Offset(incomeX, topPad + plotH - incomeH),
                        size = Size(barW, incomeH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
                    )
                    drawRoundRect(
                        color = expenseColor,
                        topLeft = Offset(expenseX, topPad + plotH - expenseH),
                        size = Size(barW, expenseH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
                    )

                    // X label (month)
                    val label = Format.shortMonth(m.month)
                    val textW = paint.measureText(label)
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        groupX + groupW / 2 - textW / 2,
                        size.height - 8f,
                        paint
                    )
                }

                // Axis lines
                drawLine(
                    color = axisColor,
                    start = Offset(leftPad, topPad),
                    end = Offset(leftPad, topPad + plotH),
                    strokeWidth = 1.5f
                )
                drawLine(
                    color = axisColor,
                    start = Offset(leftPad, topPad + plotH),
                    end = Offset(size.width - rightPad, topPad + plotH),
                    strokeWidth = 1.5f
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendDot(IncomeGreen)
                Text("  Entrées   ", style = MaterialTheme.typography.bodyMedium)
                LegendDot(ExpenseRed)
                Text("  Dépenses", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color) {
    Box(Modifier.size(12.dp).background(color, CircleShape))
}

@Composable
private fun CategoryRow(item: CategoryTotal, max: Double) {
    val pct = (item.amount / max).coerceIn(0.0, 1.0).toFloat()
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(item.category.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                Format.money(item.amount),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().height(6.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(3.dp)
                )
        ) {
            Box(
                Modifier.fillMaxWidth(pct).height(6.dp)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(3.dp)
                    )
            )
        }
    }
    HorizontalDivider()
}

private fun niceCeiling(value: Double): Double {
    if (value <= 0) return 1.0
    val exp = Math.pow(10.0, kotlin.math.floor(Math.log10(value)))
    val f = value / exp
    val nice = when {
        f <= 1 -> 1.0
        f <= 2 -> 2.0
        f <= 2.5 -> 2.5
        f <= 5 -> 5.0
        else -> 10.0
    }
    return nice * exp
}

private fun formatThousands(value: Int): String {
    if (value >= 1_000) return "${value / 1_000}k"
    return value.toString()
}
