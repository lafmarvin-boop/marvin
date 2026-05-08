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

    @Query("SELECT * FROM trades WHERE walletId = :walletId ORDER BY timestamp DESC")
    fun observeByWallet(walletId: String): Flow<List<TradeEntity>>

    @Query("""
        SELECT COALESCE(SUM(CASE WHEN side = 'BUY' THEN quoteAmount ELSE 0 END), 0)
        FROM trades WHERE walletId = :walletId AND status = 'OK'
    """)
    suspend fun totalSpent(walletId: String): Double

    @Query("SELECT MAX(timestamp) FROM trades WHERE walletId = :walletId AND status = 'OK'")
    suspend fun lastSuccessfulTimestamp(walletId: String): Long?
}
