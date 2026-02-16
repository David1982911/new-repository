package com.carwash.carpayment.data.cashdevice.device

import android.util.Log
import com.carwash.carpayment.data.cashdevice.CashPaymentConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
/**
 * ⚠️ V3.2 新增：独立设备状态监测服务
 * 职责：独立运行，定期检查设备在线状态，不受支付流程影响
 * 在 Application.onCreate() 中启动，全局单例
 */
class CashDeviceStatusMonitor(
    private val billAcceptor: CashDevice,
    private val coinAcceptor: CashDevice
) {
    
    companion object {
        private const val TAG = "CashDeviceStatusMonitor"
    }
    
    private val _billOnline = MutableStateFlow(false)
    val billOnline: StateFlow<Boolean> = _billOnline.asStateFlow()
    
    private val _coinOnline = MutableStateFlow(false)
    val coinOnline: StateFlow<Boolean> = _coinOnline.asStateFlow()
    
    /**
     * 至少一种支付设备在线（现金或POS）
     * 用于首页"开始按钮"启用条件
     */
    val hasAnyPaymentDeviceOnline: Boolean
        get() = _billOnline.value || _coinOnline.value
    
    private var monitorJob: Job? = null
    
    /**
     * 启动状态监测
     * @param scope 协程作用域（通常使用 ApplicationScope）
     */
    fun startMonitoring(scope: CoroutineScope) {
        if (monitorJob?.isActive == true) {
            Log.w(TAG, "状态监测已在运行，跳过重复启动")
            return
        }
        
        Log.d(TAG, "启动设备状态监测")
        monitorJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    updateStatus()
                } catch (e: Exception) {
                    Log.e(TAG, "状态监测异常", e)
                }
                delay(CashPaymentConfig.DEVICE_STATUS_POLL_INTERVAL)
            }
        }
    }
    
    /**
     * 停止状态监测
     */
    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        Log.d(TAG, "设备状态监测已停止")
    }
    
    /**
     * 更新设备状态
     * ⚠️ V3.2 规范：使用 GetDeviceStatus 定期轮询设备健康状态
     * ⚠️ 使用 runCatching 捕获异常，避免监测服务崩溃
     * ⚠️ 设备层已缓存最后一次 GetDeviceStatus 结果（通过 isOnline 属性）
     */
    private suspend fun updateStatus() {
        // 更新纸币器状态（设备层已缓存最后一次结果）
        _billOnline.value = runCatching {
            // ⚠️ V3.2：使用 GetDeviceStatus 进行健康监测
            val status = billAcceptor.getDeviceStatus()
            status.online
        }.getOrDefault(false)
        
        // 更新硬币器状态（设备层已缓存最后一次结果）
        _coinOnline.value = runCatching {
            // ⚠️ V3.2：使用 GetDeviceStatus 进行健康监测
            val status = coinAcceptor.getDeviceStatus()
            status.online
        }.getOrDefault(false)
        
        // 仅在状态变化时记录日志（避免日志过多）
        // if (_billOnline.value || _coinOnline.value) {
        //     Log.d(TAG, "设备状态: 纸币器=${_billOnline.value}, 硬币器=${_coinOnline.value}")
        // }
    }
}
