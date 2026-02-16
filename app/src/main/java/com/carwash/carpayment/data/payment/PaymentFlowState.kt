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
    
    /**
     * 根据 V3.3 规范：判断当前状态下是否允许用户执行返回操作
     * 
     * 规则：
     * - 订单生成后（进入 PAYING），禁止返回（订单已创建，ORDER_PAYMENT_INIT）
     * - 在 NOT_STARTED / SELECTING_METHOD 阶段允许返回（订单尚未生成，或仍可更换支付方式）
     * - 支付成功后进入 SUCCESS / STARTING_WASH / WAITING 及后续流程，禁止返回（订单已创建但未结束）
     * - 订单终态（CANCELLED_REFUNDED, PAYMENT_SUCCESS_CHANGE_FAILED）禁止返回
     * 
     * 映射关系（V3.3）：
     * - ORDER_COMPLETED = 正常服务完成（SUCCESS 且洗车完成）
     * - ORDER_REFUNDED = 退款成功（CANCELLED_REFUNDED）
     * - ORDER_MANUAL = 需人工介入（PAYMENT_SUCCESS_CHANGE_FAILED）
     * - 订单一旦进入上述终态，不再允许任何操作
     * - 订单创建以后，在订单未结束以前，不允许返回首页等操作
     */
    val isBackEnabled: Boolean
        get() = when (status) {
            // 允许返回的状态：订单尚未生成
            PaymentFlowStatus.NOT_STARTED -> true          // 首页可返回退出应用
            PaymentFlowStatus.SELECTING_METHOD -> true      // 选择支付方式时可返回（订单尚未生成）
            
            // 禁止返回的状态：订单已生成或处于终态
            PaymentFlowStatus.PAYING -> false               // 支付中，订单已创建（ORDER_PAYMENT_INIT），禁用返回
            PaymentFlowStatus.SUCCESS -> false              // 支付成功，订单已创建（ORDER_PAID），禁用返回
            PaymentFlowStatus.STARTING_WASH -> false       // 启动洗车，订单服务中（ORDER_SERVICE），禁用返回
            PaymentFlowStatus.WAITING -> false              // 等待，订单服务中，禁用返回
            
            // 终态：禁止返回（V3.3 规范：订单一旦进入上述终态，不再允许任何操作）
            PaymentFlowStatus.CANCELLED_REFUNDED -> false   // ORDER_REFUNDED，终态
            PaymentFlowStatus.PAYMENT_SUCCESS_CHANGE_FAILED -> false // ORDER_MANUAL，终态
            
            // 其他状态：根据业务逻辑决定
            PaymentFlowStatus.FAILED -> true               // 支付失败，允许返回重试
            PaymentFlowStatus.CANCELLED -> true             // 已取消，允许返回
            PaymentFlowStatus.SHOW_CANCEL_CONFIRM -> false  // 显示取消确认对话框，禁用返回（避免误操作）
            PaymentFlowStatus.CANCELLED_REFUNDING -> false  // 退款中，禁用返回
        }
}
