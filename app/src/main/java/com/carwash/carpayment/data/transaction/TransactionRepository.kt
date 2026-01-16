package com.carwash.carpayment.data.transaction

import android.app.Application
import android.util.Log
import com.carwash.carpayment.data.PaymentMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 交易记录仓库
 */
class TransactionRepository(application: Application) {
    
    companion object {
        private const val TAG = "TransactionRepository"
    }
    
    private val transactionDao = AppDatabase.getDatabase(application).transactionDao()
    
    /**
     * 插入交易记录
     */
    suspend fun insertTransaction(
        programId: String,
        programName: String,
        paymentMethod: PaymentMethod,
        amount: Double,
        result: TransactionResult
    ): Long {
        val transaction = Transaction(
            timestamp = System.currentTimeMillis(),
            programId = programId,
            programName = programName,
            paymentMethod = paymentMethod,
            amount = amount,
            result = result
        )
        val id = transactionDao.insert(transaction)
        Log.d(TAG, "交易记录已插入，ID: $id")
        return id
    }
    
    /**
     * 获取所有交易记录
     */
    fun getAllTransactions(): Flow<List<Transaction>> {
        return transactionDao.getAllTransactions()
    }
    
    /**
     * 根据ID获取交易记录
     */
    suspend fun getTransactionById(id: Long): Transaction? {
        return transactionDao.getTransactionById(id)
    }
    
    /**
     * 根据时间范围获取交易记录
     */
    fun getTransactionsByTimeRange(startTime: Long, endTime: Long): Flow<List<Transaction>> {
        return transactionDao.getTransactionsByTimeRange(startTime, endTime)
    }
    
    /**
     * 根据日期获取交易记录（当天）
     */
    fun getTransactionsByDate(startOfDay: Long, endOfDay: Long): Flow<List<Transaction>> {
        return transactionDao.getTransactionsByDate(startOfDay, endOfDay)
    }
    
    /**
     * 根据支付方式获取交易记录
     */
    fun getTransactionsByPaymentMethod(method: PaymentMethod): Flow<List<Transaction>> {
        return transactionDao.getTransactionsByPaymentMethod(method.name)
    }
    
    /**
     * 根据结果获取交易记录
     */
    fun getTransactionsByResult(result: TransactionResult): Flow<List<Transaction>> {
        return transactionDao.getTransactionsByResult(result.name)
    }
    
    /**
     * 获取交易总数
     */
    fun getTransactionCount(): Flow<Int> {
        return transactionDao.getTransactionCount()
    }
    
    /**
     * 获取总金额
     */
    fun getTotalAmount(): Flow<Double?> {
        return transactionDao.getTotalAmount()
    }
    
    /**
     * 删除交易记录
     */
    suspend fun deleteTransaction(transaction: Transaction) {
        transactionDao.delete(transaction)
        Log.d(TAG, "交易记录已删除，ID: ${transaction.id}")
    }
    
    /**
     * 删除所有交易记录
     */
    suspend fun deleteAllTransactions() {
        transactionDao.deleteAll()
        Log.d(TAG, "所有交易记录已删除")
    }
}
