package com.carwash.carpayment.data.machine

import java.util.Date

/**
 * 洗车机状态数据模型
 */
data class WashMachineState(
    val status: WashMachineStatus,           // 当前状态
    val lastUpdated: Long = System.currentTimeMillis(),  // 最后更新时间
    val currentProgramId: String? = null,    // 当前执行的程序ID（如果有）
    val faultMessage: String? = null         // 故障信息（如果状态为故障）
) {
    /**
     * 是否可用（空闲状态）
     */
    fun isAvailable(): Boolean = status == WashMachineStatus.IDLE
    
    /**
     * 是否正在使用（占用或进行中）
     */
    fun isInUse(): Boolean = status == WashMachineStatus.OCCUPIED || status == WashMachineStatus.IN_PROGRESS
    
    /**
     * 是否有故障
     */
    fun hasFault(): Boolean = status == WashMachineStatus.FAULT
}
