package com.carwash.carpayment.data.payment

import com.carwash.carpayment.data.PaymentMethod
import com.carwash.carpayment.data.WashProgram

/**
 * 支付流程状态数据模型
 */
data class PaymentFlowState(
    val status: PaymentFlowStatus,              // 当前状态
    val selectedProgram: WashProgram? = null,  // 选中的程序
    val selectedPaymentMethod: PaymentMethod? = null,  // 选中的支付方式
    val paymentConfirmed: Boolean = false,      // 支付确认
    val errorMessage: String? = null,           // 错误信息（如果失败）
    val paidAmountCents: Int = 0,              // 已收金额（分）- 用于 UI 显示
    val targetAmountCents: Int = 0,             // 目标金额（分）- 用于 UI 显示
    val lastUpdated: Long = System.currentTimeMillis()  // 最后更新时间
) {
    /**
     * 是否在支付流程中
     */
    fun isInPaymentFlow(): Boolean {
        return status == PaymentFlowStatus.SELECTING_METHOD ||
               status == PaymentFlowStatus.PAYING ||
               status == PaymentFlowStatus.STARTING_WASH
    }
    
    /**
     * 是否已完成支付
     */
    fun isPaymentCompleted(): Boolean {
        return status == PaymentFlowStatus.SUCCESS ||
               status == PaymentFlowStatus.STARTING_WASH
    }
    
    /**
     * 是否支付失败
     */
    fun isPaymentFailed(): Boolean {
        return status == PaymentFlowStatus.FAILED
    }
    
    /**
     * 是否在等待
     */
    fun isWaiting(): Boolean {
        return status == PaymentFlowStatus.WAITING
    }
}
