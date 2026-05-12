package com.marvin.budget.ui.format

import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

object Format {
    private val locale: Locale = Locale.FRANCE

    fun money(amount: Double, currency: String = "EUR"): String {
        val nf = NumberFormat.getCurrencyInstance(locale)
        nf.currency = Currency.getInstance(currency)
        return nf.format(amount)
    }

    fun signedMoney(amount: Double, currency: String = "EUR"): String {
        val sign = if (amount > 0) "+" else ""
        return sign + money(amount, currency)
    }

    private val dayFormatter = DateTimeFormatter.ofPattern("dd MMM", locale)
    fun shortDate(date: LocalDate): String = date.format(dayFormatter)

    private val monthFormatter = DateTimeFormatter.ofPattern("MMM yy", locale)
    fun shortMonth(ym: YearMonth): String = ym.format(monthFormatter)
}
