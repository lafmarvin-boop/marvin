package com.marvin.cryptobot.domain.model

enum class StrategyType {
    /** Dollar Cost Averaging: achat régulier d'un montant fixe. */
    DCA,

    /** Grid Trading: achète quand le prix baisse d'un palier %, vend quand il monte. */
    GRID,
}
