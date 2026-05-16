package com.marvin.cryptobot.domain.strategy

import com.marvin.cryptobot.data.AppContainer
import com.marvin.cryptobot.data.db.TradeEntity
import com.marvin.cryptobot.domain.model.TradingMode
import com.marvin.cryptobot.domain.model.Wallet

/**
 * Take-profit automatique : si la P&L latente d'un wallet (valeur récupérable -
 * capital injecté) dépasse `takeProfitThresholdEur`, vend `takeProfitSellPercent`%
 * de ses holdings pour matérialiser une partie du gain en cash.
 *
 * Le cash revient dans le wallet sans toucher `cashInjected` (puisque c'est le bot
 * qui génère le gain, pas l'utilisateur qui injecte du capital).
 *
 * En mode PAPER : vente simulée au prix marché actuel.
 * En mode LIVE  : ordre MARKET SELL sur Binance.
 */
object TakeProfit {

    /** Montant minimum d'un ordre LIVE (Binance Spot accepte ~5 EUR sur les paires EUR). */
    private const val MIN_LIVE_QUOTE_EUR = 5.0

    suspend fun maybeApply(
        wallet: Wallet,
        currentPrice: Double,
        container: AppContainer,
    ): Wallet {
        if (!wallet.takeProfitEnabled) return wallet
        if (wallet.holdingsBase <= 0.0) return wallet
        if (currentPrice <= 0.0) return wallet
        if (wallet.takeProfitSellPercent <= 0.0 || wallet.takeProfitSellPercent > 100.0) return wallet

        val recoverable = wallet.balanceQuote + wallet.holdingsBase * currentPrice
        val pnl = recoverable - wallet.cashInjected
        if (pnl < wallet.takeProfitThresholdEur) return wallet

        val qtyToSell = wallet.holdingsBase * (wallet.takeProfitSellPercent / 100.0)
        val proceedsEur = qtyToSell * currentPrice
        if (qtyToSell <= 0.0) return wallet
        if (wallet.mode == TradingMode.LIVE && proceedsEur < MIN_LIVE_QUOTE_EUR) {
            return wallet // ordre trop petit pour Binance
        }

        return runCatching {
            val (executedQty, received, orderId) = when (wallet.mode) {
                TradingMode.PAPER -> Triple(qtyToSell, proceedsEur, null)
                TradingMode.LIVE -> {
                    val res = container.newBinanceClient().marketSellBase(wallet.symbol, qtyToSell)
                    Triple(res.executedQty, res.quoteSpent, res.orderId)
                }
            }

            val trade = TradeEntity(
                walletId = wallet.id,
                timestamp = System.currentTimeMillis(),
                mode = wallet.mode.name,
                symbol = wallet.symbol,
                side = "SELL",
                price = if (executedQty > 0) received / executedQty else currentPrice,
                quantity = executedQty,
                quoteAmount = received,
                orderId = orderId,
                status = if (executedQty > 0) "OK" else "ERROR",
                message = "Take-profit ${wallet.takeProfitSellPercent}% @ +%.2f €".format(pnl),
            )
            container.tradeDao.insert(trade)

            wallet.copy(
                balanceQuote = wallet.balanceQuote + received,
                holdingsBase = (wallet.holdingsBase - executedQty).coerceAtLeast(0.0),
            )
        }.getOrElse { e ->
            container.tradeDao.insert(
                TradeEntity(
                    walletId = wallet.id,
                    timestamp = System.currentTimeMillis(),
                    mode = wallet.mode.name,
                    symbol = wallet.symbol,
                    side = "SELL",
                    price = 0.0,
                    quantity = 0.0,
                    quoteAmount = 0.0,
                    orderId = null,
                    status = "ERROR",
                    message = "Take-profit échoué: ${e.message?.take(400)}",
                )
            )
            wallet
        }
    }
}
