package com.marvin.cryptobot.domain.model

enum class TradingMode {
    /** Simulation: utilise les vrais prix Binance mais ne place pas d'ordre. */
    PAPER,

    /** Réel: place de vrais ordres avec les fonds du compte Binance. */
    LIVE,
}
