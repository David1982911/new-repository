package com.carwash.carpayment.data.payment

/**
 * 支付流程状态枚举
 */
enum class PaymentFlowStatus {
    NOT_STARTED,        // 未开始
    SELECTING_METHOD,   // 选择方式
    PAYING,            // 支付中
    SUCCESS,           // 成功
    FAILED,            // 失败
    CANCELLED,         // 已取消
    CANCELLED_REFUNDED, // ⚠️ 关键修复：已取消且退款完成（remaining=0）
    STARTING_WASH,      // 启动洗车
    WAITING            // 等待（洗车机不空闲）
}
