package com.marvin.cryptobot.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trades")
data class TradeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val mode: String,         // PAPER ou LIVE
    val symbol: String,
    val side: String,         // BUY/SELL
    val price: Double,
    val quantity: Double,
    val quoteSpent: Double,
    val orderId: Long?,       // null si paper
    val status: String,       // OK / ERROR
    val message: String?,     // détail erreur éventuel
)
