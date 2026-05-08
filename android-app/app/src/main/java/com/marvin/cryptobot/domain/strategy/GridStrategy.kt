package com.marvin.cryptobot.domain.strategy

import com.marvin.cryptobot.data.AppContainer
import com.marvin.cryptobot.data.db.TradeEntity
import com.marvin.cryptobot.domain.model.TradingMode
import com.marvin.cryptobot.domain.model.Wallet

/**
 * Grid Trading "à pas relatif".
 *
 * Logique:
 * - On garde un prix de référence (`gridReferencePrice`).
 * - À chaque tick on lit le prix actuel.
 * - Si le prix a monté de >= gridStepPercent depuis la référence et qu'on a des holdings:
 *     -> VEND `gridAmountPerStep` EUR équivalent BTC.
 * - Si le prix a baissé de >= gridStepPercent et qu'on a du cash:
 *     -> ACHÈTE pour `gridAmountPerStep` EUR de BTC.
 * - Quand un trade s'exécute, on met à jour la référence au prix du trade.
 *
 * Note: en mode LIVE le grid n'est PAS encore supporté (placerait des market orders
 * comme un humain le ferait, mais sans gérer les vrais limit orders sur Binance).
 * Pour la phase de test on reste en PAPER.
 */
class GridStrategy(private val container: AppContainer) : Strategy {

    override suspend fun runOnce(wallet: Wallet): StrategyOutcome {
        if (!wallet.enabled) return StrategyOutcome.Skipped("Wallet désactivé")
        if (wallet.mode == TradingMode.LIVE) {
            return StrategyOutcome.Skipped("Grid LIVE pas encore supporté — reste en PAPER")
        }
        if (wallet.gridStepPercent <= 0 || wallet.gridAmountPerStep <= 0) {
            return StrategyOutcome.Skipped("Paramètres grid invalides")
        }

        return runCatching {
            val client = container.newBinanceClient()
            val currentPrice = client.lastPrice(wallet.symbol)

            // Initialisation au 1er run: pose juste la référence, pas de trade.
            if (wallet.gridReferencePrice <= 0.0) {
                return@runCatching StrategyOutcome.Executed(
                    updatedWallet = wallet.copy(gridReferencePrice = currentPrice),
                    trades = emptyList(),
                )
            }

            val ref = wallet.gridReferencePrice
            val deltaPct = (currentPrice - ref) / ref * 100.0

            when {
                deltaPct >= wallet.gridStepPercent -> tryExecuteSell(wallet, currentPrice)
                deltaPct <= -wallet.gridStepPercent -> tryExecuteBuy(wallet, currentPrice)
                else -> StrategyOutcome.Skipped(
                    "Variation ${"%.2f".format(deltaPct)}% < seuil ${wallet.gridStepPercent}%"
                )
            }
        }.getOrElse { e ->
            persistError(wallet, e)
            StrategyOutcome.Failure(e.message ?: "erreur inconnue", e)
        }
    }

    private suspend fun tryExecuteBuy(wallet: Wallet, price: Double): StrategyOutcome {
        val amount = wallet.gridAmountPerStep

        // Plafond cumulé
        if (wallet.maxTotalSpend > 0.0 &&
            wallet.totalInvested + amount > wallet.maxTotalSpend
        ) {
            return StrategyOutcome.Skipped("Plafond cumulé atteint")
        }
        if (wallet.balanceQuote < amount) {
            return StrategyOutcome.Skipped("Solde insuffisant pour BUY")
        }

        val qty = amount / price
        val trade = TradeEntity(
            walletId = wallet.id,
            timestamp = System.currentTimeMillis(),
            mode = wallet.mode.name,
            symbol = wallet.symbol,
            side = "BUY",
            price = price,
            quantity = qty,
            quoteAmount = amount,
            orderId = null,
            status = "OK",
            message = null,
        )
        val id = container.tradeDao.insert(trade)
        val updated = wallet.copy(
            balanceQuote = wallet.balanceQuote - amount,
            holdingsBase = wallet.holdingsBase + qty,
            totalInvested = wallet.totalInvested + amount,
            gridReferencePrice = price,
        )
        return StrategyOutcome.Executed(updated, listOf(trade.copy(id = id)))
    }

    private suspend fun tryExecuteSell(wallet: Wallet, price: Double): StrategyOutcome {
        val targetQuote = wallet.gridAmountPerStep
        val qtyToSell = (targetQuote / price).coerceAtMost(wallet.holdingsBase)

        if (qtyToSell <= 0.0) {
            return StrategyOutcome.Skipped("Holdings insuffisants pour SELL")
        }
        val quoteReceived = qtyToSell * price

        val trade = TradeEntity(
            walletId = wallet.id,
            timestamp = System.currentTimeMillis(),
            mode = wallet.mode.name,
            symbol = wallet.symbol,
            side = "SELL",
            price = price,
            quantity = qtyToSell,
            quoteAmount = quoteReceived,
            orderId = null,
            status = "OK",
            message = null,
        )
        val id = container.tradeDao.insert(trade)
        val updated = wallet.copy(
            balanceQuote = wallet.balanceQuote + quoteReceived,
            holdingsBase = wallet.holdingsBase - qtyToSell,
            // totalInvested ne diminue pas: on garde la trace du capital réellement engagé
            gridReferencePrice = price,
        )
        return StrategyOutcome.Executed(updated, listOf(trade.copy(id = id)))
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
