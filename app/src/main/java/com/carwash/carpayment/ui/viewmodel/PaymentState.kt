package com.carwash.carpayment.ui.viewmodel

import com.carwash.carpayment.data.PaymentMethod
import com.carwash.carpayment.data.WashProgram

/**
 * 支付流程状态（兼容旧接口）
 */
data class PaymentState(
    val selectedProgram: WashProgram? = null,
    val selectedPaymentMethod: PaymentMethod? = null,
    val paymentConfirmed: Boolean = false,
    val isPaymentProcessing: Boolean = false,
    val paymentSuccess: Boolean = false,
    val collectedAmount: Double = 0.0,      // 已收款金额（欧元）
    val targetAmount: Double = 0.0,           // 目标金额（欧元）
    val isPaymentComplete: Boolean = false    // 是否已收齐
)
