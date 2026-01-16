package com.carwash.carpayment.data.transaction

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 交易记录 DAO
 */
@Dao
interface TransactionDao {
    
    /**
     * 插入交易记录
     */
    @Insert
    suspend fun insert(transaction: Transaction): Long
    
    /**
     * 获取所有交易记录
     */
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<Transaction>>
    
    /**
     * 根据ID获取交易记录
     */
    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getTransactionById(id: Long): Transaction?
    
    /**
     * 根据时间范围获取交易记录
     */
    @Query("SELECT * FROM transactions WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC")
    fun getTransactionsByTimeRange(startTime: Long, endTime: Long): Flow<List<Transaction>>
    
    /**
     * 根据日期获取交易记录（当天）
     */
    @Query("SELECT * FROM transactions WHERE timestamp >= :startOfDay AND timestamp < :endOfDay ORDER BY timestamp DESC")
    fun getTransactionsByDate(startOfDay: Long, endOfDay: Long): Flow<List<Transaction>>
    
    /**
     * 根据支付方式获取交易记录
     */
    @Query("SELECT * FROM transactions WHERE paymentMethod = :method ORDER BY timestamp DESC")
    fun getTransactionsByPaymentMethod(method: String): Flow<List<Transaction>>
    
    /**
     * 根据结果获取交易记录
     */
    @Query("SELECT * FROM transactions WHERE result = :result ORDER BY timestamp DESC")
    fun getTransactionsByResult(result: String): Flow<List<Transaction>>
    
    /**
     * 获取交易总数
     */
    @Query("SELECT COUNT(*) FROM transactions")
    fun getTransactionCount(): Flow<Int>
    
    /**
     * 获取总金额
     */
    @Query("SELECT SUM(amount) FROM transactions WHERE result = 'SUCCESS'")
    fun getTotalAmount(): Flow<Double?>
    
    /**
     * 删除交易记录
     */
    @Delete
    suspend fun delete(transaction: Transaction)
    
    /**
     * 删除所有交易记录
     */
    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}
