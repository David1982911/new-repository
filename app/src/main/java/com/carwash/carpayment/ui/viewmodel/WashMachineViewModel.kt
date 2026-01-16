package com.carwash.carpayment.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carwash.carpayment.data.machine.WashMachineState
import com.carwash.carpayment.data.machine.WashMachineStateMachine
import com.carwash.carpayment.data.machine.WashMachineStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * WashMachineViewModel - 管理洗车机状态
 */
class WashMachineViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "WashMachineViewModel"
    }
    
    private val stateMachine = WashMachineStateMachine()
    
    // 当前洗车机状态
    private val _state = MutableStateFlow<WashMachineState>(
        WashMachineState(status = WashMachineStatus.IDLE)
    )
    val state: StateFlow<WashMachineState> = _state.asStateFlow()
    
    /**
     * 启动洗车（从空闲转为占用）
     */
    fun startWash(programId: String): Boolean {
        val currentState = _state.value
        val newState = stateMachine.startWash(currentState, programId)
        return if (newState != null) {
            _state.value = newState
            Log.d(TAG, "洗车已启动，程序ID: $programId")
            true
        } else {
            Log.w(TAG, "无法启动洗车，当前状态: ${currentState.status}")
            false
        }
    }
    
    /**
     * 开始执行洗车程序（从占用转为进行中）
     */
    fun beginWashing(): Boolean {
        val currentState = _state.value
        val newState = stateMachine.beginWashing(currentState)
        return if (newState != null) {
            _state.value = newState
            Log.d(TAG, "洗车程序已开始执行")
            true
        } else {
            Log.w(TAG, "无法开始洗车，当前状态: ${currentState.status}")
            false
        }
    }
    
    /**
     * 完成洗车（从进行中转为空闲）
     */
    fun completeWash(): Boolean {
        val currentState = _state.value
        val newState = stateMachine.completeWash(currentState)
        return if (newState != null) {
            _state.value = newState
            Log.d(TAG, "洗车已完成")
            true
        } else {
            Log.w(TAG, "无法完成洗车，当前状态: ${currentState.status}")
            false
        }
    }
    
    /**
     * 报告故障
     */
    fun reportFault(faultMessage: String) {
        val currentState = _state.value
        val newState = stateMachine.reportFault(currentState, faultMessage)
        _state.value = newState
        Log.d(TAG, "故障已报告: $faultMessage")
    }
    
    /**
     * 修复故障（从故障转为空闲）
     */
    fun repairFault(): Boolean {
        val currentState = _state.value
        val newState = stateMachine.repairFault(currentState)
        return if (newState != null) {
            _state.value = newState
            Log.d(TAG, "故障已修复")
            true
        } else {
            Log.w(TAG, "无法修复故障，当前状态: ${currentState.status}")
            false
        }
    }
    
    /**
     * 取消洗车（从占用转为空闲）
     */
    fun cancelWash(): Boolean {
        val currentState = _state.value
        val newState = stateMachine.cancelWash(currentState)
        return if (newState != null) {
            _state.value = newState
            Log.d(TAG, "洗车已取消")
            true
        } else {
            Log.w(TAG, "无法取消洗车，当前状态: ${currentState.status}")
            false
        }
    }
    
    /**
     * 获取当前状态（只读）
     */
    fun getCurrentState(): WashMachineState = _state.value
    
    /**
     * 检查是否可用
     */
    fun isAvailable(): Boolean = _state.value.isAvailable()
    
    /**
     * 检查是否正在使用
     */
    fun isInUse(): Boolean = _state.value.isInUse()
    
    /**
     * 检查是否有故障
     */
    fun hasFault(): Boolean = _state.value.hasFault()
}
