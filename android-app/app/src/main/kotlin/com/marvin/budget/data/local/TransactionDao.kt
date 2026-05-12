package com.marvin.budget.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.marvin.budget.data.model.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY date DESC, id DESC")
    fun observeAll(): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE accountId = :accountId ORDER BY date DESC, id DESC")
    fun observeByAccount(accountId: String): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE date >= :from AND date <= :to ORDER BY date DESC")
    fun observeBetween(from: String, to: String): Flow<List<Transaction>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(transactions: List<Transaction>)

    @Query("DELETE FROM transactions")
    suspend fun clear()
}
