package com.carwash.carpayment.data.machine

/**
 * 洗车机状态枚举
 */
enum class WashMachineStatus {
    IDLE,           // 空闲
    OCCUPIED,       // 占用
    IN_PROGRESS,    // 进行中
    FAULT           // 故障
}
