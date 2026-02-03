package com.carwash.carpayment.data.washflow

import com.carwash.carpayment.data.PaymentMethod
import com.carwash.carpayment.data.WashProgram

/**
 * 洗车流程大状态枚举（必须覆盖：支付、GateCheck、启动、运行、完成、退款/人工）
 */
sealed class WashFlowState {
    /**
     * 初始状态（未开始）
     */
    object Idle : WashFlowState()
    
    /**
     * 支付中
     */
    data class Paying(
        val program: WashProgram,
        val paymentMethod: PaymentMethod,
        val paidAmountCents: Int = 0,
        val targetAmountCents: Int = 0
    ) : WashFlowState()
    
    /**
     * 支付成功，等待 GateCheck
     */
    data class PaymentSuccess(
        val program: WashProgram,
        val paymentMethod: PaymentMethod,
        val paymentResult: PaymentResult
    ) : WashFlowState()
    
    /**
     * GateCheck 中
     */
    data class GateChecking(
        val program: WashProgram,
        val paymentMethod: PaymentMethod,
        val registerSnapshot: RegisterSnapshot? = null
    ) : WashFlowState()
    
    /**
     * GateCheck 失败，等待条件满足
     */
    data class WaitingForGateCheck(
        val program: WashProgram,
        val paymentMethod: PaymentMethod,
        val failureReason: GateCheckFailureReason,
        val registerSnapshot: RegisterSnapshot? = null
    ) : WashFlowState()
    
    /**
     * 启动中（写 Mode 脉冲后等待 214）
     */
    data class Starting(
        val program: WashProgram,
        val paymentMethod: PaymentMethod,
        val washMode: Int, // 1-4
        val registerSnapshot: RegisterSnapshot? = null
    ) : WashFlowState()
    
    /**
     * 运行中（214=自动状态，监控 102）
     */
    data class Running(
        val program: WashProgram,
        val paymentMethod: PaymentMethod,
        val washMode: Int,
        val registerSnapshot: RegisterSnapshot? = null
    ) : WashFlowState()
    
    /**
     * 完成
     */
    data class Completed(
        val program: WashProgram,
        val paymentMethod: PaymentMethod,
        val washMode: Int,
        val registerSnapshot: RegisterSnapshot? = null
    ) : WashFlowState()
    
    /**
     * 退款中
     */
    data class Refunding(
        val program: WashProgram?,
        val paymentMethod: PaymentMethod?,
        val reason: RefundReason,
        val registerSnapshot: RegisterSnapshot? = null
    ) : WashFlowState()
    
    /**
     * 需要人工干预
     */
    data class ManualInterventionRequired(
        val program: WashProgram?,
        val paymentMethod: PaymentMethod?,
        val reason: String,
        val registerSnapshot: RegisterSnapshot? = null
    ) : WashFlowState()
    
    /**
     * 失败（支付失败等）
     */
    data class Failed(
        val reason: String,
        val program: WashProgram? = null,
        val paymentMethod: PaymentMethod? = null
    ) : WashFlowState()
}

/**
 * 支付结果（统一接口）
 */
sealed class PaymentResult {
    data class Success(
        val amountCents: Int,
        val transactionId: String? = null
    ) : PaymentResult()
    
    data class Failure(
        val errorMessage: String,
        val errorCode: String? = null
    ) : PaymentResult()
    
    data class Cancelled(
        val reason: String? = null
    ) : PaymentResult()
}

/**
 * GateCheck 失败原因
 */
enum class GateCheckFailureReason {
    NOT_CONNECTED,          // 洗车机未连接
    COMMUNICATION_FAILED,   // 通讯失败
    DEVICE_FAULT,           // 设备故障（217=1）
    PREVIOUS_CAR_PRESENT,   // 前车还在（752=1）
    DEVICE_NOT_READY        // 设备未就绪（240=0）
}

/**
 * 退款原因
 */
enum class RefundReason {
    GATE_CHECK_TIMEOUT,     // GateCheck 超时
    START_TIMEOUT,          // 启动超时（214 未进入自动状态）
    MONITOR_TIMEOUT,        // 监控超时（102 未结束）
    COMMUNICATION_FAILED,   // 通讯失败
    DEVICE_FAULT,           // 设备故障
    USER_CANCELLED          // 用户取消
}

/**
 * 寄存器快照（用于日志和状态追踪）
 */
data class RegisterSnapshot(
    val reg217: Int? = null,  // 故障状态
    val reg752: Int? = null,  // 前车状态
    val reg240: Int? = null,  // 可再次洗车
    val reg102: Int? = null,  // 车位状态
    val reg214: Int? = null,  // 启动洗车状态
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun toString(): String {
        return "RegisterSnapshot(217=${reg217}, 752=${reg752}, 240=${reg240}, 102=${reg102}, 214=${reg214})"
    }
}
