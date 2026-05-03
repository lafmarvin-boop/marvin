package com.marvin.cryptobot.domain.model

data class BotConfig(
    val enabled: Boolean = false,
    val mode: TradingMode = TradingMode.PAPER,
    val symbol: String = "BTCEUR",
    /** Montant en quote-currency (EUR) à acheter à chaque exécution. */
    val quoteAmount: Double = 10.0,
    /** Périodicité en heures entre deux achats. */
    val intervalHours: Int = 24,
    /** Plafond de dépense cumulée en quote-currency, 0 = illimité. */
    val maxTotalSpend: Double = 0.0,
)
