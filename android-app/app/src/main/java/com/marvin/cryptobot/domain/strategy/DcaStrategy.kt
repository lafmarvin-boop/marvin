package com.marvin.cryptobot.domain.strategy

import com.marvin.cryptobot.data.AppContainer
import com.marvin.cryptobot.data.db.TradeEntity
import com.marvin.cryptobot.data.remote.BinanceClient
import com.marvin.cryptobot.domain.model.BotConfig
import com.marvin.cryptobot.domain.model.TradingMode

/**
 * Stratégie Dollar Cost Averaging.
 *
 * À chaque exécution: achète un montant fixe en quote-currency (EUR), peu importe le prix.
 * En mode PAPER: simule l'achat avec le prix marché et persiste un trade fictif.
 * En mode LIVE: place un ordre MARKET BUY réel et persiste le résultat.
 *
 * Sécurités:
 * - Si maxTotalSpend est défini, refuse l'achat quand le cumul est atteint.
 * - Toute exception API est attrapée et loggée comme trade en erreur (pas de retry agressif).
 */
class DcaStrategy(private val container: AppContainer) {

    sealed interface Outcome {
        data class Success(val trade: TradeEntity) : Outcome
        data class Skipped(val reason: String) : Outcome
        data class Failure(val reason: String, val cause: Throwable? = null) : Outcome
    }

    suspend fun runOnce(config: BotConfig): Outcome {
        if (!config.enabled) return Outcome.Skipped("Bot désactivé")

        // Garde-fou: plafond de dépense
        if (config.maxTotalSpend > 0.0) {
            val spent = container.tradeDao.totalSpent(config.mode.name)
            if (spent + config.quoteAmount > config.maxTotalSpend) {
                return Outcome.Skipped("Plafond ${config.maxTotalSpend} ${quoteOf(config.symbol)} atteint")
            }
        }

        val client = container.newBinanceClient()

        return runCatching {
            when (config.mode) {
                TradingMode.PAPER -> simulateBuy(client, config)
                TradingMode.LIVE -> liveBuy(client, config)
            }
        }.getOrElse { e ->
            val err = TradeEntity(
                timestamp = System.currentTimeMillis(),
                mode = config.mode.name,
                symbol = config.symbol,
                side = "BUY",
                price = 0.0,
                quantity = 0.0,
                quoteSpent = 0.0,
                orderId = null,
                status = "ERROR",
                message = e.message?.take(500),
            )
            container.tradeDao.insert(err)
            Outcome.Failure(e.message ?: "erreur inconnue", e)
        }
    }

    private suspend fun simulateBuy(client: BinanceClient, config: BotConfig): Outcome {
        val price = client.lastPrice(config.symbol)
        val qty = config.quoteAmount / price
        val trade = TradeEntity(
            timestamp = System.currentTimeMillis(),
            mode = TradingMode.PAPER.name,
            symbol = config.symbol,
            side = "BUY",
            price = price,
            quantity = qty,
            quoteSpent = config.quoteAmount,
            orderId = null,
            status = "OK",
            message = null,
        )
        val id = container.tradeDao.insert(trade)
        return Outcome.Success(trade.copy(id = id))
    }

    private suspend fun liveBuy(client: BinanceClient, config: BotConfig): Outcome {
        if (!container.secureKeyStore.hasCredentials()) {
            return Outcome.Failure("Clés API manquantes pour le mode LIVE")
        }
        val result = client.marketBuyQuote(config.symbol, config.quoteAmount)
        val trade = TradeEntity(
            timestamp = System.currentTimeMillis(),
            mode = TradingMode.LIVE.name,
            symbol = config.symbol,
            side = "BUY",
            price = result.avgPrice,
            quantity = result.executedQty,
            quoteSpent = result.quoteSpent,
            orderId = result.orderId,
            status = if (result.executedQty > 0) "OK" else "ERROR",
            message = if (result.executedQty > 0) null else "Quantité exécutée nulle",
        )
        val id = container.tradeDao.insert(trade)
        return Outcome.Success(trade.copy(id = id))
    }

    /** Extrait la quote-currency d'un symbole type BTCEUR -> EUR. */
    private fun quoteOf(symbol: String): String {
        val quotes = listOf("EUR", "USDT", "BUSD", "USDC", "GBP", "TRY", "BTC")
        return quotes.firstOrNull { symbol.endsWith(it) } ?: ""
    }
}
