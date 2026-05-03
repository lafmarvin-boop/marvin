package com.marvin.cryptobot.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TradeDao {

    @Insert
    suspend fun insert(trade: TradeEntity): Long

    @Query("SELECT * FROM trades ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<TradeEntity>>

    @Query("SELECT COALESCE(SUM(quoteSpent), 0) FROM trades WHERE mode = :mode AND status = 'OK'")
    suspend fun totalSpent(mode: String): Double

    @Query("SELECT COALESCE(SUM(quantity), 0) FROM trades WHERE symbol = :symbol AND mode = :mode AND side = 'BUY' AND status = 'OK'")
    suspend fun totalBought(symbol: String, mode: String): Double

    @Query("SELECT MAX(timestamp) FROM trades WHERE status = 'OK'")
    suspend fun lastSuccessfulTimestamp(): Long?
}
