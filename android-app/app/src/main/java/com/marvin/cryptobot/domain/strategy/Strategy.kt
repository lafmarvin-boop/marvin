package com.marvin.cryptobot.domain.strategy

import com.marvin.cryptobot.data.db.TradeEntity
import com.marvin.cryptobot.domain.model.Wallet

/**
 * Résultat d'une exécution de stratégie sur un wallet.
 *
 * Une stratégie peut produire 0, 1 ou plusieurs trades par tick (le grid peut
 * traverser plusieurs paliers entre deux ticks par exemple).
 */
sealed interface StrategyOutcome {
    data class Executed(val updatedWallet: Wallet, val trades: List<TradeEntity>) : StrategyOutcome
    data class Skipped(val reason: String) : StrategyOutcome
    data class Failure(val reason: String, val cause: Throwable? = null) : StrategyOutcome
}

interface Strategy {
    suspend fun runOnce(wallet: Wallet): StrategyOutcome
}
