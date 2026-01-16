package com.carwash.carpayment.data.machine

import android.util.Log

/**
 * 洗车机状态机
 * 管理洗车机状态的转换逻辑
 */
class WashMachineStateMachine {
    
    companion object {
        private const val TAG = "WashMachineStateMachine"
    }
    
    /**
     * 状态转换规则
     * 定义哪些状态可以转换到哪些状态
     */
    private val allowedTransitions = mapOf(
        // 空闲 -> 占用/故障
        WashMachineStatus.IDLE to setOf(
            WashMachineStatus.OCCUPIED,
            WashMachineStatus.FAULT
        ),
        // 占用 -> 进行中/空闲/故障
        WashMachineStatus.OCCUPIED to setOf(
            WashMachineStatus.IN_PROGRESS,
            WashMachineStatus.IDLE,
            WashMachineStatus.FAULT
        ),
        // 进行中 -> 空闲/故障
        WashMachineStatus.IN_PROGRESS to setOf(
            WashMachineStatus.IDLE,
            WashMachineStatus.FAULT
        ),
        // 故障 -> 空闲（修复后）
        WashMachineStatus.FAULT to setOf(
            WashMachineStatus.IDLE
        )
    )
    
    /**
     * 检查状态转换是否允许
     */
    fun canTransition(from: WashMachineStatus, to: WashMachineStatus): Boolean {
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
        currentState: WashMachineState,
        newStatus: WashMachineStatus,
        programId: String? = null,
        faultMessage: String? = null
    ): WashMachineState {
        if (!canTransition(currentState.status, newStatus)) {
            Log.w(TAG, "状态转换失败: ${currentState.status} -> $newStatus")
            return currentState
        }
        
        Log.d(TAG, "状态转换: ${currentState.status} -> $newStatus")
        
        return WashMachineState(
            status = newStatus,
            lastUpdated = System.currentTimeMillis(),
            currentProgramId = programId ?: currentState.currentProgramId,
            faultMessage = if (newStatus == WashMachineStatus.FAULT) {
                faultMessage ?: "未知故障"
            } else {
                null
            }
        )
    }
    
    /**
     * 启动洗车（从空闲转为占用）
     */
    fun startWash(currentState: WashMachineState, programId: String): WashMachineState? {
        if (currentState.status != WashMachineStatus.IDLE) {
            Log.w(TAG, "无法启动洗车，当前状态: ${currentState.status}")
            return null
        }
        return transition(currentState, WashMachineStatus.OCCUPIED, programId)
    }
    
    /**
     * 开始执行洗车程序（从占用转为进行中）
     */
    fun beginWashing(currentState: WashMachineState): WashMachineState? {
        if (currentState.status != WashMachineStatus.OCCUPIED) {
            Log.w(TAG, "无法开始洗车，当前状态: ${currentState.status}")
            return null
        }
        return transition(currentState, WashMachineStatus.IN_PROGRESS)
    }
    
    /**
     * 完成洗车（从进行中转为空闲）
     */
    fun completeWash(currentState: WashMachineState): WashMachineState? {
        if (currentState.status != WashMachineStatus.IN_PROGRESS) {
            Log.w(TAG, "无法完成洗车，当前状态: ${currentState.status}")
            return null
        }
        return transition(currentState, WashMachineStatus.IDLE, programId = null)
    }
    
    /**
     * 报告故障
     */
    fun reportFault(currentState: WashMachineState, faultMessage: String): WashMachineState {
        return transition(currentState, WashMachineStatus.FAULT, faultMessage = faultMessage)
    }
    
    /**
     * 修复故障（从故障转为空闲）
     */
    fun repairFault(currentState: WashMachineState): WashMachineState? {
        if (currentState.status != WashMachineStatus.FAULT) {
            Log.w(TAG, "无法修复，当前状态不是故障: ${currentState.status}")
            return null
        }
        return transition(currentState, WashMachineStatus.IDLE, programId = null)
    }
    
    /**
     * 取消洗车（从占用转为空闲）
     */
    fun cancelWash(currentState: WashMachineState): WashMachineState? {
        if (currentState.status != WashMachineStatus.OCCUPIED) {
            Log.w(TAG, "无法取消洗车，当前状态: ${currentState.status}")
            return null
        }
        return transition(currentState, WashMachineStatus.IDLE, programId = null)
    }
}
