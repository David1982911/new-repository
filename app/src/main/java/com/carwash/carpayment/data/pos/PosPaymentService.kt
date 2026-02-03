package com.carwash.carpayment.data.pos

/**
 * POS 支付服务抽象接口（为不同厂商适配）
 * 
 * 实现类：
 * - UsdkPosPaymentService: USDK SDK 实现
 * - 其他厂商实现可以继承此接口
 */
interface PosPaymentService {
    
    /**
     * 初始化 POS 设备
     * @return 是否初始化成功
     */
    suspend fun initialize(): Boolean
    
    /**
     * 发起支付
     * @param amountCents 支付金额（分）
     * @param onPaymentResult 支付结果回调
     */
    suspend fun initiatePayment(
        amountCents: Int,
        onPaymentResult: (PaymentResult) -> Unit
    )
    
    /**
     * 取消支付
     * @return 是否取消成功
     */
    suspend fun cancelPayment(): Boolean
    
    /**
     * 查询支付状态
     * @return 支付状态
     */
    suspend fun getPaymentStatus(): PaymentStatus
    
    /**
     * 关闭 POS 设备连接
     */
    suspend fun close()
}

/**
 * 支付结果
 */
sealed class PaymentResult {
    data class Success(val amountCents: Int) : PaymentResult()
    data class Failure(val errorMessage: String, val errorCode: Int? = null) : PaymentResult()
    data class Cancelled(val reason: String? = null) : PaymentResult()
}

/**
 * 支付状态
 */
enum class PaymentStatus {
    IDLE,           // 空闲
    PROCESSING,     // 处理中
    SUCCESS,        // 成功
    FAILED,         // 失败
    CANCELLED       // 已取消
}
