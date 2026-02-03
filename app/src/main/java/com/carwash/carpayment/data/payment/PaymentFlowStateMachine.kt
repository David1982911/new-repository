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
        // 支付中 -> 成功/失败/取消/已取消且退款完成
        PaymentFlowStatus.PAYING to setOf(
            PaymentFlowStatus.SUCCESS,
            PaymentFlowStatus.FAILED,
            PaymentFlowStatus.CANCELLED,
            PaymentFlowStatus.CANCELLED_REFUNDED  // ⚠️ 关键修复：已取消且退款完成
        ),
        // 成功 -> 启动洗车/等待/选择方式（重试）
        PaymentFlowStatus.SUCCESS to setOf(
            PaymentFlowStatus.STARTING_WASH,
            PaymentFlowStatus.WAITING,
            PaymentFlowStatus.SELECTING_METHOD  // 允许从成功状态回到选择方式（用于重试或取消）
        ),
        // 失败 -> 选择方式（重试）/未开始（取消）
        PaymentFlowStatus.FAILED to setOf(
            PaymentFlowStatus.SELECTING_METHOD,  // 允许从失败状态回到选择方式（重试）
            PaymentFlowStatus.NOT_STARTED
        ),
        // 取消 -> 选择方式/未开始
        PaymentFlowStatus.CANCELLED to setOf(
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
            paidAmountCents = currentState.paidAmountCents,
            targetAmountCents = currentState.targetAmountCents,
            lastUpdated = System.currentTimeMillis()
        )
    }
    
    /**
     * 开始支付流程（从未开始转为选择方式）
     */
    fun startPaymentFlow(program: WashProgram, currentState: PaymentFlowState? = null): PaymentFlowState? {
        // ⚠️ SUCCESS 状态下忽略开始支付操作，避免误报
        if (currentState?.status == PaymentFlowStatus.SUCCESS) {
            Log.d(TAG, "SUCCESS 状态下忽略开始支付操作（已完成支付）")
            return null
        }
        return PaymentFlowState(
            status = PaymentFlowStatus.SELECTING_METHOD,
            selectedProgram = program,
            paidAmountCents = 0,
            targetAmountCents = 0,
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
        // ⚠️ SUCCESS 状态下忽略所有支付操作，避免误报
        if (currentState.status == PaymentFlowStatus.SUCCESS) {
            Log.d(TAG, "SUCCESS 状态下忽略确认支付操作（已完成支付）")
            return null
        }
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
        // ⚠️ SUCCESS 状态下忽略支付失败操作，避免误报
        if (currentState.status == PaymentFlowStatus.SUCCESS) {
            Log.d(TAG, "SUCCESS 状态下忽略支付失败操作（已完成支付）")
            return null
        }
        if (currentState.status != PaymentFlowStatus.PAYING) {
            Log.w(TAG, "无法标记支付失败，当前状态: ${currentState.status}")
            return null
        }
        return transition(currentState, PaymentFlowStatus.FAILED, errorMessage)
    }
    
    /**
     * 支付取消（从支付中转为取消）
     */
    fun paymentCancelled(currentState: PaymentFlowState, reason: String? = null): PaymentFlowState? {
        if (currentState.status != PaymentFlowStatus.PAYING) {
            Log.w(TAG, "无法标记支付取消，当前状态: ${currentState.status}")
            return null
        }
        return transition(currentState, PaymentFlowStatus.CANCELLED, reason)
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
     * 重置到选择支付方式（从失败或其他状态回到选择方式）
     * 允许用户在支付失败后重新选择支付方式
     */
    fun resetToSelecting(currentState: PaymentFlowState): PaymentFlowState? {
        // 允许从 FAILED、SUCCESS、WAITING 等状态回到 SELECTING_METHOD
        if (currentState.status == PaymentFlowStatus.SELECTING_METHOD) {
            // 已经在选择方式状态，不需要转换
            return currentState
        }
        
        // 尝试转换到 SELECTING_METHOD
        if (canTransition(currentState.status, PaymentFlowStatus.SELECTING_METHOD)) {
            return transition(currentState, PaymentFlowStatus.SELECTING_METHOD)
        }
        
        // 如果无法直接转换，先转到 NOT_STARTED，再转到 SELECTING_METHOD
        if (canTransition(currentState.status, PaymentFlowStatus.NOT_STARTED)) {
            val intermediateState = transition(currentState, PaymentFlowStatus.NOT_STARTED)
            if (canTransition(PaymentFlowStatus.NOT_STARTED, PaymentFlowStatus.SELECTING_METHOD)) {
                return transition(intermediateState, PaymentFlowStatus.SELECTING_METHOD)
            }
        }
        
        Log.w(TAG, "无法重置到选择支付方式，当前状态: ${currentState.status}")
        return null
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
    
    /**
     * 强制重置到初始状态（NOT_STARTED）
     * 用于在返回主界面时清空所有状态，允许开始新的支付流程
     * @param currentState 当前状态
     * @return 重置后的状态（NOT_STARTED）
     */
    /**
     * 强制重置到未开始状态
     * ⚠️ 关键修复：不清空 selectedProgram 和 selectedPaymentMethod，避免白屏
     * @param currentState 当前状态
     * @return 重置后的状态（保留 selectedProgram 和 selectedPaymentMethod）
     */
    fun forceResetToNotStarted(currentState: PaymentFlowState): PaymentFlowState {
        Log.d(TAG, "强制重置到 NOT_STARTED: ${currentState.status} -> NOT_STARTED")
        // ⚠️ 关键修复：不清空 selectedProgram 和 selectedPaymentMethod，避免白屏
        return PaymentFlowState(
            status = PaymentFlowStatus.NOT_STARTED,
            selectedProgram = currentState.selectedProgram,  // ⚠️ 保留选择，避免白屏
            selectedPaymentMethod = currentState.selectedPaymentMethod,  // ⚠️ 保留支付方式，避免白屏
            paymentConfirmed = false,
            errorMessage = null,
            paidAmountCents = 0,
            targetAmountCents = 0,
            lastUpdated = System.currentTimeMillis()
        )
    }
}
