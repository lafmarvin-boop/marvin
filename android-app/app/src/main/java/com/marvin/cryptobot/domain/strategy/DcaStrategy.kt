package com.marvin.cryptobot.domain.strategy

import com.marvin.cryptobot.data.AppContainer
import com.marvin.cryptobot.data.db.TradeEntity
import com.marvin.cryptobot.data.remote.BinanceClient
import com.marvin.cryptobot.domain.model.TradingMode
import com.marvin.cryptobot.domain.model.Wallet

/**
 * Stratégie Dollar Cost Averaging.
 *
 * À chaque exécution: achète un montant fixe en quote-currency (EUR), peu importe le prix.
 *
 * Garde-fous:
 * - Si le solde quote du wallet est insuffisant, l'achat est ignoré.
 * - Si le plafond cumulé d'investissement est atteint, l'achat est ignoré.
 */
class DcaStrategy(private val container: AppContainer) : Strategy {

    override suspend fun runOnce(wallet: Wallet): StrategyOutcome {
        if (!wallet.enabled) return StrategyOutcome.Skipped("Wallet désactivé")
        if (wallet.dcaAmount <= 0) return StrategyOutcome.Skipped("Montant DCA = 0")

        // Plafond cumulé
        if (wallet.maxTotalSpend > 0.0 &&
            wallet.totalInvested + wallet.dcaAmount > wallet.maxTotalSpend
        ) {
            return StrategyOutcome.Skipped("Plafond ${wallet.maxTotalSpend} atteint")
        }

        // Solde insuffisant
        if (wallet.balanceQuote < wallet.dcaAmount) {
            return StrategyOutcome.Skipped(
                "Solde insuffisant (${"%.2f".format(wallet.balanceQuote)} EUR)"
            )
        }

        val client = container.newBinanceClient()

        return runCatching {
            val price = client.lastPrice(wallet.symbol)
            val (qty, quoteSpent, orderId) = when (wallet.mode) {
                TradingMode.PAPER -> Triple(wallet.dcaAmount / price, wallet.dcaAmount, null)
                TradingMode.LIVE -> {
                    val res = client.marketBuyQuote(wallet.symbol, wallet.dcaAmount)
                    Triple(res.executedQty, res.quoteSpent, res.orderId)
                }
            }

            val trade = TradeEntity(
                walletId = wallet.id,
                timestamp = System.currentTimeMillis(),
                mode = wallet.mode.name,
                symbol = wallet.symbol,
                side = "BUY",
                price = if (qty > 0) quoteSpent / qty else price,
                quantity = qty,
                quoteAmount = quoteSpent,
                orderId = orderId,
                status = if (qty > 0) "OK" else "ERROR",
                message = if (qty > 0) null else "Quantité exécutée nulle",
            )
            val id = container.tradeDao.insert(trade)

            val updated = wallet.copy(
                balanceQuote = wallet.balanceQuote - quoteSpent,
                holdingsBase = wallet.holdingsBase + qty,
                totalInvested = wallet.totalInvested + quoteSpent,
            )
            StrategyOutcome.Executed(updated, listOf(trade.copy(id = id)))
        }.getOrElse { e ->
            persistError(wallet, e)
            StrategyOutcome.Failure(e.message ?: "erreur inconnue", e)
        }
    }

    private suspend fun persistError(wallet: Wallet, e: Throwable) {
        container.tradeDao.insert(
            TradeEntity(
                walletId = wallet.id,
                timestamp = System.currentTimeMillis(),
                mode = wallet.mode.name,
                symbol = wallet.symbol,
                side = "BUY",
                price = 0.0,
                quantity = 0.0,
                quoteAmount = 0.0,
                orderId = null,
                status = "ERROR",
                message = e.message?.take(500),
            )
        )
    }
}
