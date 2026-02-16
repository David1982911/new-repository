package com.carwash.carpayment.data.cashdevice

/**
 * ⚠️ V3.2 新增：现金支付超时异常
 * 当轮询连续失败次数达到上限时抛出
 */
class CashPaymentTimeoutException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
