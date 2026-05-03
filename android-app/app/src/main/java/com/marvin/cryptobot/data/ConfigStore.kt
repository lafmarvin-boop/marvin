package com.marvin.cryptobot.data

import android.content.Context
import com.marvin.cryptobot.domain.model.BotConfig
import com.marvin.cryptobot.domain.model.TradingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Configuration non-secrète du bot (mode, symbole, montant…).
 * Persiste dans SharedPreferences classique.
 */
class ConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences("cryptobot_config", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<BotConfig> = _config.asStateFlow()

    fun update(transform: (BotConfig) -> BotConfig) {
        val next = transform(_config.value)
        save(next)
        _config.value = next
    }

    private fun load(): BotConfig = BotConfig(
        enabled = prefs.getBoolean(K_ENABLED, false),
        mode = TradingMode.valueOf(prefs.getString(K_MODE, TradingMode.PAPER.name)!!),
        symbol = prefs.getString(K_SYMBOL, "BTCEUR")!!,
        quoteAmount = prefs.getFloat(K_AMOUNT, 10f).toDouble(),
        intervalHours = prefs.getInt(K_INTERVAL, 24),
        maxTotalSpend = prefs.getFloat(K_MAX_SPEND, 0f).toDouble(),
    )

    private fun save(c: BotConfig) {
        prefs.edit()
            .putBoolean(K_ENABLED, c.enabled)
            .putString(K_MODE, c.mode.name)
            .putString(K_SYMBOL, c.symbol)
            .putFloat(K_AMOUNT, c.quoteAmount.toFloat())
            .putInt(K_INTERVAL, c.intervalHours)
            .putFloat(K_MAX_SPEND, c.maxTotalSpend.toFloat())
            .apply()
    }

    private companion object {
        const val K_ENABLED = "enabled"
        const val K_MODE = "mode"
        const val K_SYMBOL = "symbol"
        const val K_AMOUNT = "amount"
        const val K_INTERVAL = "interval"
        const val K_MAX_SPEND = "max_spend"
    }
}
