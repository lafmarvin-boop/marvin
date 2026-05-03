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
import com.marvin.cryptobot.domain.model.BotConfig
import com.marvin.cryptobot.domain.strategy.DcaStrategy
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class DcaWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as CryptoBotApp
        val config = app.container.configStore.config.first()
        if (!config.enabled) return Result.success()

        val strategy = DcaStrategy(app.container)
        return when (val outcome = strategy.runOnce(config)) {
            is DcaStrategy.Outcome.Success -> {
                notify(
                    title = "Achat ${config.mode.name.lowercase()}",
                    text = "${formatAmount(outcome.trade.quantity)} ${baseOf(config.symbol)} " +
                        "@ ${"%.2f".format(outcome.trade.price)}",
                )
                Result.success()
            }
            is DcaStrategy.Outcome.Skipped -> Result.success()
            is DcaStrategy.Outcome.Failure -> {
                notify(title = "Échec DCA", text = outcome.reason)
                // Retry géré par WorkManager (max 3 fois avec backoff)
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        }
    }

    private fun notify(title: String, text: String) {
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(applicationContext, CryptoBotApp.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(NOTIF_ID, n)
        }
    }

    private fun formatAmount(qty: Double): String =
        if (qty < 0.01) "%.8f".format(qty) else "%.4f".format(qty)

    private fun baseOf(symbol: String): String {
        val quotes = listOf("EUR", "USDT", "BUSD", "USDC", "GBP", "TRY", "BTC")
        val q = quotes.firstOrNull { symbol.endsWith(it) } ?: return symbol
        return symbol.removeSuffix(q)
    }

    companion object {
        const val WORK_NAME = "dca_periodic"
        private const val NOTIF_ID = 42

        /**
         * (Ré)enregistre le worker périodique. Min 15min imposé par WorkManager,
         * mais on accepte n'importe quelle valeur >= 15min en pratique.
         */
        fun schedule(context: Context, config: BotConfig) {
            val intervalMin = (config.intervalHours * 60).coerceAtLeast(15)
            val request = PeriodicWorkRequestBuilder<DcaWorker>(
                intervalMin.toLong(), TimeUnit.MINUTES
            ).build()
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
