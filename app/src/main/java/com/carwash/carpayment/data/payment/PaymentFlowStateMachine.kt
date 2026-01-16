package com.carwash.carpayment.data.payment

import android.util.Log
import com.carwash.carpayment.data.PaymentMethod
import com.carwash.carpayment.data.WashProgram

/**
 * 支付流程状态机
 * 管理支付流程的状态转换逻辑
 */
class PaymentFlowStateMachine {
    
    companion object {
        private const val TAG = "PaymentFlowStateMachine"
    }
    
    /**
     * 状态转换规则
     * 定义哪些状态可以转换到哪些状态
     */
    private val allowedTransitions = mapOf(
        // 未开始 -> 选择方式
        PaymentFlowStatus.NOT_STARTED to setOf(
            PaymentFlowStatus.SELECTING_METHOD
        ),
        // 选择方式 -> 支付中/未开始（取消）
        PaymentFlowStatus.SELECTING_METHOD to setOf(
            PaymentFlowStatus.PAYING,
            PaymentFlowStatus.NOT_STARTED
        ),
        // 支付中 -> 成功/失败
        PaymentFlowStatus.PAYING to setOf(
            PaymentFlowStatus.SUCCESS,
            PaymentFlowStatus.FAILED
        ),
        // 成功 -> 启动洗车/等待/选择方式（重试）
        PaymentFlowStatus.SUCCESS to setOf(
            PaymentFlowStatus.STARTING_WASH,
            PaymentFlowStatus.WAITING,
            PaymentFlowStatus.SELECTING_METHOD  // 允许从成功状态回到选择方式（用于重试或取消）
        ),
        // 失败 -> 选择方式（重试）/未开始（取消）
        PaymentFlowStatus.FAILED to setOf(
            PaymentFlowStatus.SELECTING_METHOD,
            PaymentFlowStatus.NOT_STARTED
        ),
        // 启动洗车 -> 未开始（完成）
        PaymentFlowStatus.STARTING_WASH to setOf(
            PaymentFlowStatus.NOT_STARTED
        ),
        // 等待 -> 启动洗车/未开始（取消）
        PaymentFlowStatus.WAITING to setOf(
            PaymentFlowStatus.STARTING_WASH,
            PaymentFlowStatus.NOT_STARTED
        )
    )
    
    /**
     * 检查状态转换是否允许
     */
    fun canTransition(from: PaymentFlowStatus, to: PaymentFlowStatus): Boolean {
        val allowed = allowedTransitions[from] ?: return false
        val result = allowed.contains(to)
        if (!result) {
            Log.w(TAG, "不允许的状态转换: $from -> $to")
        }
        return result
    }
    
    /**
     * 执行状态转换
     * @return 新的状态，如果转换不允许则返回原状态
     */
    fun transition(
        currentState: PaymentFlowState,
        newStatus: PaymentFlowStatus,
        errorMessage: String? = null
    ): PaymentFlowState {
        if (!canTransition(currentState.status, newStatus)) {
            Log.w(TAG, "状态转换失败: ${currentState.status} -> $newStatus")
            return currentState
        }
        
        Log.d(TAG, "状态转换: ${currentState.status} -> $newStatus")
        
        return PaymentFlowState(
            status = newStatus,
            selectedProgram = currentState.selectedProgram,
            selectedPaymentMethod = currentState.selectedPaymentMethod,
            paymentConfirmed = currentState.paymentConfirmed,
            errorMessage = errorMessage,
            lastUpdated = System.currentTimeMillis()
        )
    }
    
    /**
     * 开始支付流程（从未开始转为选择方式）
     */
    fun startPaymentFlow(program: WashProgram): PaymentFlowState {
        return PaymentFlowState(
            status = PaymentFlowStatus.SELECTING_METHOD,
            selectedProgram = program,
            lastUpdated = System.currentTimeMillis()
        )
    }
    
    /**
     * 选择支付方式（保持在选择方式状态，更新支付方式）
     */
    fun selectPaymentMethod(
        currentState: PaymentFlowState,
        method: PaymentMethod
    ): PaymentFlowState {
        if (currentState.status != PaymentFlowStatus.SELECTING_METHOD) {
            Log.w(TAG, "无法选择支付方式，当前状态: ${currentState.status}")
            return currentState
        }
        return currentState.copy(
            selectedPaymentMethod = method,
            lastUpdated = System.currentTimeMillis()
        )
    }
    
    /**
     * 确认支付（从选择方式转为支付中）
     */
    fun confirmPayment(currentState: PaymentFlowState): PaymentFlowState? {
        if (currentState.status != PaymentFlowStatus.SELECTING_METHOD) {
            Log.w(TAG, "无法确认支付，当前状态: ${currentState.status}")
            return null
        }
        if (currentState.selectedPaymentMethod == null || !currentState.paymentConfirmed) {
            Log.w(TAG, "支付条件不满足：支付方式=${currentState.selectedPaymentMethod}, 确认=${currentState.paymentConfirmed}")
            return null
        }
        return transition(currentState, PaymentFlowStatus.PAYING)
    }
    
    /**
     * 支付成功（从支付中转为成功）
     */
    fun paymentSuccess(currentState: PaymentFlowState): PaymentFlowState? {
        if (currentState.status != PaymentFlowStatus.PAYING) {
            Log.w(TAG, "无法标记支付成功，当前状态: ${currentState.status}")
            return null
        }
        return transition(currentState, PaymentFlowStatus.SUCCESS)
    }
    
    /**
     * 支付失败（从支付中转为失败）
     */
    fun paymentFailed(currentState: PaymentFlowState, errorMessage: String): PaymentFlowState? {
        if (currentState.status != PaymentFlowStatus.PAYING) {
            Log.w(TAG, "无法标记支付失败，当前状态: ${currentState.status}")
            return null
        }
        return transition(currentState, PaymentFlowStatus.FAILED, errorMessage)
    }
    
    /**
     * 启动洗车（从成功转为启动洗车）
     */
    fun startWashing(currentState: PaymentFlowState): PaymentFlowState? {
        if (currentState.status != PaymentFlowStatus.SUCCESS) {
            Log.w(TAG, "无法启动洗车，当前状态: ${currentState.status}")
            return null
        }
        return transition(currentState, PaymentFlowStatus.STARTING_WASH)
    }
    
    /**
     * 等待（从成功转为等待，洗车机不空闲）
     */
    fun waitForMachine(currentState: PaymentFlowState): PaymentFlowState? {
        if (currentState.status != PaymentFlowStatus.SUCCESS) {
            Log.w(TAG, "无法进入等待状态，当前状态: ${currentState.status}")
            return null
        }
        return transition(currentState, PaymentFlowStatus.WAITING)
    }
    
    /**
     * 从等待转为启动洗车（洗车机已空闲）
     */
    fun proceedFromWaiting(currentState: PaymentFlowState): PaymentFlowState? {
        if (currentState.status != PaymentFlowStatus.WAITING) {
            Log.w(TAG, "无法从等待继续，当前状态: ${currentState.status}")
            return null
        }
        return transition(currentState, PaymentFlowStatus.STARTING_WASH)
    }
    
    /**
     * 完成流程（从启动洗车转为未开始）
     */
    fun completeFlow(currentState: PaymentFlowState): PaymentFlowState? {
        if (currentState.status != PaymentFlowStatus.STARTING_WASH) {
            Log.w(TAG, "无法完成流程，当前状态: ${currentState.status}")
            return null
        }
        return transition(currentState, PaymentFlowStatus.NOT_STARTED)
    }
    
    /**
     * 取消支付（从选择方式、失败或成功转为未开始）
     */
    fun cancelPayment(currentState: PaymentFlowState): PaymentFlowState? {
        if (currentState.status != PaymentFlowStatus.SELECTING_METHOD &&
            currentState.status != PaymentFlowStatus.FAILED &&
            currentState.status != PaymentFlowStatus.WAITING &&
            currentState.status != PaymentFlowStatus.SUCCESS) {
            Log.w(TAG, "无法取消支付，当前状态: ${currentState.status}")
            return null
        }
        return transition(currentState, PaymentFlowStatus.NOT_STARTED)
    }
    
    /**
     * 重试支付（从失败转为选择方式）
     */
    fun retryPayment(currentState: PaymentFlowState): PaymentFlowState? {
        if (currentState.status != PaymentFlowStatus.FAILED) {
            Log.w(TAG, "无法重试支付，当前状态: ${currentState.status}")
            return null
        }
        return transition(currentState, PaymentFlowStatus.SELECTING_METHOD)
    }
}
