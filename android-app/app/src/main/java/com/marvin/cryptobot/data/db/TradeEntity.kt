package com.marvin.cryptobot.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "trades",
    indices = [Index("walletId"), Index("timestamp")],
)
data class TradeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val walletId: String,
    val timestamp: Long,
    val mode: String,         // PAPER ou LIVE
    val symbol: String,
    val side: String,         // BUY ou SELL
    val price: Double,
    val quantity: Double,     // quantité de base (ex: BTC)
    val quoteAmount: Double,  // EUR dépensés (BUY) ou reçus (SELL)
    val orderId: Long?,       // null en paper
    val status: String,       // OK / ERROR
    val message: String?,     // détail erreur éventuel
)
