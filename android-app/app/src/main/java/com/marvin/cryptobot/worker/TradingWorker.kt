package com.marvin.cryptobot.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.marvin.cryptobot.CryptoBotApp
import com.marvin.cryptobot.MainActivity
import com.marvin.cryptobot.data.AppContainer
import com.marvin.cryptobot.domain.model.StrategyType
import com.marvin.cryptobot.domain.model.Wallet
import com.marvin.cryptobot.domain.strategy.DcaStrategy
import com.marvin.cryptobot.domain.strategy.GridStrategy
import com.marvin.cryptobot.domain.strategy.Strategy
import com.marvin.cryptobot.domain.strategy.StrategyOutcome
import com.marvin.cryptobot.domain.strategy.TakeProfit
import java.util.concurrent.TimeUnit

/**
 * Worker périodique qui itère sur tous les wallets activés et exécute leur stratégie.
 *
 * Tourne toutes les 15 min (minimum imposé par WorkManager). Chaque stratégie décide
 * elle-même si c'est le bon moment d'agir (DCA respecte son intervalHours, Grid agit
 * dès qu'un palier est franchi).
 */
class TradingWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as CryptoBotApp
        val container = app.container
        val wallets = container.walletStore.wallets.value

        var success = true
        for (wallet in wallets) {
            if (!wallet.enabled) continue

            // 1) Stratégie principale (DCA respecte son intervalle, Grid agit à chaque palier)
            val shouldRunStrategy = wallet.type != StrategyType.DCA || shouldRunDca(container, wallet)
            if (shouldRunStrategy) {
                val strategy = strategyFor(wallet, container)
                when (val outcome = strategy.runOnce(wallet)) {
                    is StrategyOutcome.Executed -> {
                        container.walletStore.update(wallet.id) { outcome.updatedWallet }
                        outcome.trades.forEach { notify(wallet, it) }
                    }
                    is StrategyOutcome.Skipped -> Unit
                    is StrategyOutcome.Failure -> success = false
                }
            }

            // 2) Take-profit (toujours évalué, même si la stratégie principale a sauté ce tour)
            val currentWallet = container.walletStore.byId(wallet.id) ?: continue
            if (currentWallet.takeProfitEnabled && currentWallet.holdingsBase > 0) {
                val price = runCatching {
                    container.newBinanceClient().lastPrice(currentWallet.symbol)
                }.getOrNull()
                if (price != null) {
                    val cashBefore = currentWallet.balanceQuote
                    val updated = TakeProfit.maybeApply(currentWallet, price, container)
                    if (updated.holdingsBase < currentWallet.holdingsBase) {
                        container.walletStore.update(wallet.id) { updated }
                        notifyTakeProfit(wallet, updated.balanceQuote - cashBefore)
                    }
                }
            }
        }

        return if (success) Result.success() else if (runAttemptCount < 3) Result.retry() else Result.failure()
    }

    private fun notifyTakeProfit(wallet: Wallet, cashGained: Double) {
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(applicationContext, CryptoBotApp.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("💰 Take-profit ${wallet.name}")
            .setContentText("+%.2f € sécurisés en cash".format(cashGained))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(applicationContext)
                .notify((wallet.id.hashCode() and 0x7FFFFFFF), n)
        }
    }

    private suspend fun shouldRunDca(container: AppContainer, wallet: Wallet): Boolean {
        val last = container.tradeDao.lastSuccessfulTimestamp(wallet.id) ?: return true
        val elapsedMs = System.currentTimeMillis() - last
        val requiredMs = wallet.dcaIntervalHours * 3_600_000L
        return elapsedMs >= requiredMs
    }

    private fun strategyFor(wallet: Wallet, container: AppContainer): Strategy =
        when (wallet.type) {
            StrategyType.DCA -> DcaStrategy(container)
            StrategyType.GRID -> GridStrategy(container)
        }

    private fun notify(wallet: Wallet, trade: com.marvin.cryptobot.data.db.TradeEntity) {
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = "${wallet.name}: ${trade.side}"
        val text = "%.6f %s @ %.2f (%.2f €)".format(
            trade.quantity, baseOf(trade.symbol), trade.price, trade.quoteAmount,
        )
        val n = NotificationCompat.Builder(applicationContext, CryptoBotApp.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(trade.id.toInt(), n)
        }
    }

    private fun baseOf(symbol: String): String {
        val quotes = listOf("EUR", "USDT", "BUSD", "USDC", "GBP", "TRY", "BTC")
        val q = quotes.firstOrNull { symbol.endsWith(it) } ?: return symbol
        return symbol.removeSuffix(q)
    }

    companion object {
        const val WORK_NAME = "trading_periodic"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TradingWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
