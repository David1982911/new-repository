package com.carwash.carpayment.data.cashdevice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 现金设备启动器
 * 在 App 启动时预初始化现金设备（认证 + 连接，但不启用收款）
 */
class CashDeviceBootstrapper(
    private val context: Context,
    private val cashDeviceRepository: CashDeviceRepository
) {
    
    companion object {
        private const val TAG = "CashDeviceBootstrapper"
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * 设备就绪状态
     */
    sealed class CashDeviceReadyState {
        object Loading : CashDeviceReadyState()
        data class Ready(val devices: Map<String, String>) : CashDeviceReadyState()
        data class Error(val message: String) : CashDeviceReadyState()
    }
    
    private val _readyState = MutableStateFlow<CashDeviceReadyState>(CashDeviceReadyState.Loading)
    val readyState: StateFlow<CashDeviceReadyState> = _readyState.asStateFlow()
    
    /**
     * 预初始化设备（在后台线程执行）
     * 只完成认证和连接，不启用接收器
     */
    fun prewarm() {
        scope.launch {
            try {
                Log.d(TAG, "开始预初始化现金设备...")
                _readyState.value = CashDeviceReadyState.Loading
                
                val devices = cashDeviceRepository.prewarmSession()
                
                if (devices.isEmpty()) {
                    Log.w(TAG, "预初始化完成，但未找到可用设备")
                    _readyState.value = CashDeviceReadyState.Error("未找到可用设备")
                    return@launch
                }
                
                Log.d(TAG, "预初始化成功: 已连接设备数量=${devices.size}")
                devices.forEach { (name, id) ->
                    Log.d(TAG, "  - $name -> $id")
                }
                
                _readyState.value = CashDeviceReadyState.Ready(devices)
                
            } catch (e: Exception) {
                Log.e(TAG, "预初始化失败", e)
                _readyState.value = CashDeviceReadyState.Error("预初始化失败: ${e.message}")
            }
        }
    }
    
    /**
     * 获取当前设备映射（如果已就绪）
     */
    fun getDevices(): Map<String, String>? {
        return when (val state = _readyState.value) {
            is CashDeviceReadyState.Ready -> state.devices
            else -> null
        }
    }
}
