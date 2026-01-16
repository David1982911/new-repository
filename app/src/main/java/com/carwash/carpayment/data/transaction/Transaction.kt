package com.carwash.carpayment.data.transaction

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.carwash.carpayment.data.PaymentMethod

/**
 * 交易记录实体
 */
@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val timestamp: Long,              // 交易时间（时间戳）
    val programId: String,            // 程序ID
    val programName: String,          // 程序名称
    val paymentMethod: PaymentMethod, // 支付方式
    val amount: Double,               // 金额（欧元）
    val result: TransactionResult     // 交易结果
)

/**
 * 交易结果枚举
 */
enum class TransactionResult {
    SUCCESS,    // 成功
    FAILED,     // 失败
    CANCELLED   // 已取消
}
