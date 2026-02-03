package com.carwash.carpayment.data.cashdevice

/**
 * 退款策略配置
 * ⚠️ Step 5: "退款策略可配置"落地
 */
data class RefundConfig(
    /**
     * 退款优先级：BILL_FIRST（优先纸币）或 COIN_FIRST（优先硬币）
     */
    val refundPriority: RefundPriority = RefundPriority.BILL_FIRST,
    
    /**
     * BUSY 重试次数（最多 6 次）
     */
    val refundBusyRetryCount: Int = 6,
    
    /**
     * BUSY 重试基础延迟（毫秒）
     */
    val refundBusyRetryBaseDelayMs: Long = 500L,
    
    /**
     * 找零不足时的动作：SHOW_MESSAGE_ONLY（仅提示）或 LOCK_MACHINE_AND_ALERT（锁单并报警）
     */
    val refundInsufficientAction: RefundInsufficientAction = RefundInsufficientAction.LOCK_MACHINE_AND_ALERT
)

enum class RefundPriority {
    BILL_FIRST,  // 优先纸币找零
    COIN_FIRST   // 优先硬币找零
}

enum class RefundInsufficientAction {
    SHOW_MESSAGE_ONLY,        // 仅提示用户
    LOCK_MACHINE_AND_ALERT   // 锁单并报警（默认，更安全）
}
