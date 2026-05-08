package com.marvin.cryptobot.data

import android.content.Context
import com.marvin.cryptobot.data.db.AppDatabase
import com.marvin.cryptobot.data.db.TradeDao
import com.marvin.cryptobot.data.remote.BinanceClient

/**
 * Conteneur de dépendances simple (pas besoin de Hilt pour ce scope).
 */
class AppContainer(private val context: Context) {

    val secureKeyStore: SecureKeyStore by lazy { SecureKeyStore(context) }
    val walletStore: WalletStore by lazy { WalletStore(context) }
    val tradeDao: TradeDao by lazy { AppDatabase.get(context).tradeDao() }

    /** Construit un client avec les clés stockées (ou un client public si absentes). */
    fun newBinanceClient(): BinanceClient = BinanceClient(
        apiKey = secureKeyStore.apiKey,
        apiSecret = secureKeyStore.apiSecret,
    )
}
