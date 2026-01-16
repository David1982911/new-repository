package com.carwash.carpayment.data.transaction

import androidx.room.TypeConverter
import com.carwash.carpayment.data.PaymentMethod

/**
 * Room 类型转换器
 */
class Converters {
    
    /**
     * PaymentMethod 类型转换器
     */
    @TypeConverter
    fun fromPaymentMethod(method: PaymentMethod): String {
        return method.name
    }
    
    @TypeConverter
    fun toPaymentMethod(value: String): PaymentMethod {
        return try {
            PaymentMethod.valueOf(value)
        } catch (e: IllegalArgumentException) {
            PaymentMethod.CARD  // 默认值
        }
    }
    
    /**
     * TransactionResult 类型转换器
     */
    @TypeConverter
    fun fromResult(result: TransactionResult): String {
        return result.name
    }
    
    @TypeConverter
    fun toResult(value: String): TransactionResult {
        return try {
            TransactionResult.valueOf(value)
        } catch (e: IllegalArgumentException) {
            TransactionResult.SUCCESS  // 默认值
        }
    }
}
