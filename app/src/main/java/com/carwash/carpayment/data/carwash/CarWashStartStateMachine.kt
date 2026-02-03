package com.carwash.carpayment.data.carwash

/**
 * 洗车机启动状态机状态
 * 阶段 8 + 9：支付后洗车机门禁检查 + 启动流程
 */
sealed class CarWashStartState {
    /**
     * S8.1 等待 752==0（前车离开）
     */
    data class WaitingPreviousCarLeave(
        val elapsedMs: Long = 0,
        val consecutiveConfirmations: Int = 0
    ) : CarWashStartState()
    
    /**
     * S8.2 等待 102==1（车辆到位）
     */
    data class WaitingCarInPosition(
        val elapsedMs: Long = 0,
        val consecutiveConfirmations: Int = 0
    ) : CarWashStartState()
    
    /**
     * S8.3 等待 240==1（设备就绪）
     */
    data class WaitingDeviceReady(
        val elapsedMs: Long = 0,
        val consecutiveConfirmations: Int = 0
    ) : CarWashStartState()
    
    /**
     * S9.1 发送 Mode 指令
     */
    data class SendingMode(
        val mode: Int,
        val retryCount: Int = 0,
        val maxRetries: Int = 3
    ) : CarWashStartState()
    
    /**
     * S9.2 启动确认（等待 214=自动状态）
     */
    data class ConfirmingStart(
        val elapsedMs: Long = 0,
        val consecutiveConfirmations: Int = 0,
        val modeRetryRound: Int = 1,
        val maxModeRetryRounds: Int = 3
    ) : CarWashStartState()
    
    /**
     * S8.9 退款（失败）
     */
    data class Refunding(
        val reason: CarWashStartFailureReason
    ) : CarWashStartState()
    
    /**
     * 启动成功
     */
    object Success : CarWashStartState()
}

/**
 * 洗车机启动失败原因
 */
enum class CarWashStartFailureReason {
    PREVIOUS_CAR_NOT_LEFT,      // 前车未离开（752超时）
    CAR_NOT_IN_POSITION,        // 车辆未到位（102超时）
    DEVICE_NOT_READY,           // 设备未就绪（240超时）
    SEND_MODE_FAILED,           // 发送命令失败（S9.1失败）
    NOT_ENTERED_AUTO_STATUS     // 未进入自动状态（214超时）
}
