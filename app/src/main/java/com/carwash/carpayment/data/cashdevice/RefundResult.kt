package com.carwash.carpayment.data.cashdevice

/**
 * 退款/找零结果
 * ⚠️ 关键修复：统一返回结果对象，确保状态机收敛正确
 */
data class RefundResult(
    /**
     * 是否成功（remaining == 0）
     */
    val success: Boolean,
    
    /**
     * 剩余未退款金额（分）
     */
    val remaining: Int,
    
    /**
     * 退款明细（面额 -> 数量）
     */
    val breakdown: List<DispenseBreakdown> = emptyList(),
    
    /**
     * 错误列表（中途失败但最终成功的，作为 warnings）
     */
    val errors: List<String> = emptyList(),
    
    /**
     * 纸币器退款金额（分）
     */
    val billDispensed: Int = 0,
    
    /**
     * 硬币器退款金额（分）
     */
    val coinDispensed: Int = 0,
    
    /**
     * 总退款金额（分）
     */
    val totalDispensed: Int = 0
) {
    /**
     * 退款明细项
     */
    data class DispenseBreakdown(
        val deviceID: String,
        val deviceName: String,
        val denomination: Int,  // 面额（分）
        val count: Int,  // 数量
        val amount: Int  // 金额（分）= denomination * count
    )
}
