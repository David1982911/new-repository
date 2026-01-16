package com.carwash.carpayment.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carwash.carpayment.data.cashdevice.CashDeviceClient
import com.carwash.carpayment.data.cashdevice.CashDeviceRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * 现金设备测试 ViewModel
 * 专门用于测试设备的连通性
 */
class CashDeviceTestViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "CashDeviceTestViewModel"
        private const val POLL_INTERVAL_MS = 500L // 轮询间隔 500ms
    }
    
    private val api = CashDeviceClient.create(context = getApplication())
    private val probeApi = CashDeviceClient.createWithTimeout(context = getApplication(), timeoutSeconds = 12L)
    private val repository = CashDeviceRepository(api)
    
    // 纸币器状态
    data class DeviceState(
        val deviceID: String? = null,
        val isConnected: Boolean = false,
        val isEnabled: Boolean = false,
        val eventCount: Int = 0,
        val lastEvent: String? = null,
        val lastStatus: String? = null
    )
    
    private val _billAcceptorState = MutableStateFlow(DeviceState())
    val billAcceptorState: StateFlow<DeviceState> = _billAcceptorState.asStateFlow()
    
    private val _coinAcceptorState = MutableStateFlow(DeviceState())
    val coinAcceptorState: StateFlow<DeviceState> = _coinAcceptorState.asStateFlow()
    
    // 测试日志
    private val _testLogs = MutableStateFlow<List<String>>(emptyList())
    val testLogs: StateFlow<List<String>> = _testLogs.asStateFlow()
    
    // 轮询任务
    private var billPollingJob: Job? = null
    private var coinPollingJob: Job? = null
    
    init {
        // 监听 deviceID 变化
        viewModelScope.launch {
            repository.billAcceptorDeviceID.collect { deviceID ->
                val currentState = _billAcceptorState.value
                _billAcceptorState.value = currentState.copy(
                    deviceID = deviceID,
                    isConnected = deviceID != null
                )
                if (deviceID != null) {
                    addLog("纸币器已连接: deviceID=$deviceID")
                    startBillPolling(deviceID)
                } else {
                    addLog("纸币器已断开")
                    stopBillPolling()
                }
            }
        }
        
        viewModelScope.launch {
            repository.coinAcceptorDeviceID.collect { deviceID ->
                val currentState = _coinAcceptorState.value
                _coinAcceptorState.value = currentState.copy(
                    deviceID = deviceID,
                    isConnected = deviceID != null
                )
                if (deviceID != null) {
                    addLog("硬币器已连接: deviceID=$deviceID")
                    startCoinPolling(deviceID)
                } else {
                    addLog("硬币器已断开")
                    stopCoinPolling()
                }
            }
        }
    }
    
    /**
     * 连接纸币器
     */
    fun connectBillAcceptor() {
        viewModelScope.launch {
            try {
                addLog("开始连接纸币器 (SSP=0)...")
                val success = repository.initializeBillAcceptor(probeApi)
                if (success) {
                    addLog("纸币器连接成功")
                } else {
                    addLog("纸币器连接失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "连接纸币器异常", e)
                addLog("连接纸币器异常: ${e.message}")
            }
        }
    }
    
    /**
     * 断开纸币器
     */
    fun disconnectBillAcceptor() {
        viewModelScope.launch {
            val deviceID = _billAcceptorState.value.deviceID
            if (deviceID != null) {
                try {
                    addLog("断开纸币器连接...")
                    repository.disconnectDevice(deviceID)
                    addLog("纸币器已断开")
                } catch (e: Exception) {
                    Log.e(TAG, "断开纸币器异常", e)
                    addLog("断开纸币器异常: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 启用纸币器
     */
    fun enableBillAcceptor() {
        viewModelScope.launch {
            val deviceID = _billAcceptorState.value.deviceID
            if (deviceID != null) {
                try {
                    addLog("启用纸币器...")
                    val success = repository.enableAcceptor(deviceID)
                    if (success) {
                        _billAcceptorState.value = _billAcceptorState.value.copy(isEnabled = true)
                        addLog("纸币器已启用")
                    } else {
                        addLog("纸币器启用失败")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "启用纸币器异常", e)
                    addLog("启用纸币器异常: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 禁用纸币器
     */
    fun disableBillAcceptor() {
        viewModelScope.launch {
            val deviceID = _billAcceptorState.value.deviceID
            if (deviceID != null) {
                try {
                    addLog("禁用纸币器...")
                    val success = repository.disableAcceptor(deviceID)
                    if (success) {
                        _billAcceptorState.value = _billAcceptorState.value.copy(isEnabled = false)
                        addLog("纸币器已禁用")
                    } else {
                        addLog("纸币器禁用失败")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "禁用纸币器异常", e)
                    addLog("禁用纸币器异常: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 连接硬币器
     */
    fun connectCoinAcceptor() {
        viewModelScope.launch {
            try {
                addLog("开始连接硬币器 (SSP=16)...")
                val success = repository.initializeCoinAcceptor(probeApi)
                if (success) {
                    addLog("硬币器连接成功")
                } else {
                    addLog("硬币器连接失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "连接硬币器异常", e)
                addLog("连接硬币器异常: ${e.message}")
            }
        }
    }
    
    /**
     * 断开硬币器
     */
    fun disconnectCoinAcceptor() {
        viewModelScope.launch {
            val deviceID = _coinAcceptorState.value.deviceID
            if (deviceID != null) {
                try {
                    addLog("断开硬币器连接...")
                    repository.disconnectDevice(deviceID)
                    addLog("硬币器已断开")
                } catch (e: Exception) {
                    Log.e(TAG, "断开硬币器异常", e)
                    addLog("断开硬币器异常: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 启用硬币器
     */
    fun enableCoinAcceptor() {
        viewModelScope.launch {
            val deviceID = _coinAcceptorState.value.deviceID
            if (deviceID != null) {
                try {
                    addLog("启用硬币器...")
                    val success = repository.enableAcceptor(deviceID)
                    if (success) {
                        _coinAcceptorState.value = _coinAcceptorState.value.copy(isEnabled = true)
                        addLog("硬币器已启用")
                    } else {
                        addLog("硬币器启用失败")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "启用硬币器异常", e)
                    addLog("启用硬币器异常: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 禁用硬币器
     */
    fun disableCoinAcceptor() {
        viewModelScope.launch {
            val deviceID = _coinAcceptorState.value.deviceID
            if (deviceID != null) {
                try {
                    addLog("禁用硬币器...")
                    val success = repository.disableAcceptor(deviceID)
                    if (success) {
                        _coinAcceptorState.value = _coinAcceptorState.value.copy(isEnabled = false)
                        addLog("硬币器已禁用")
                    } else {
                        addLog("硬币器禁用失败")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "禁用硬币器异常", e)
                    addLog("禁用硬币器异常: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 开始轮询纸币器状态
     */
    private fun startBillPolling(deviceID: String) {
        stopBillPolling()
        billPollingJob = viewModelScope.launch {
            var lastStatus: String? = null
            while (true) {
                try {
                    val status = repository.getDeviceStatus(deviceID)
                    val statusStr = status?.status ?: "unknown"
                    
                    // 如果状态发生变化，记录事件
                    if (statusStr != lastStatus && lastStatus != null) {
                        val currentState = _billAcceptorState.value
                        _billAcceptorState.value = currentState.copy(
                            eventCount = currentState.eventCount + 1,
                            lastEvent = "状态变化: $lastStatus -> $statusStr",
                            lastStatus = statusStr
                        )
                        addLog("纸币器事件: $lastStatus -> $statusStr")
                        Log.d(TAG, "纸币器状态变化: $lastStatus -> $statusStr")
                    }
                    
                    lastStatus = statusStr
                    _billAcceptorState.value = _billAcceptorState.value.copy(lastStatus = statusStr)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "轮询纸币器状态异常", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }
    
    /**
     * 停止轮询纸币器状态
     */
    private fun stopBillPolling() {
        billPollingJob?.cancel()
        billPollingJob = null
    }
    
    /**
     * 开始轮询硬币器状态
     */
    private fun startCoinPolling(deviceID: String) {
        stopCoinPolling()
        coinPollingJob = viewModelScope.launch {
            var lastStatus: String? = null
            while (true) {
                try {
                    val status = repository.getDeviceStatus(deviceID)
                    val statusStr = status?.status ?: "unknown"
                    
                    // 如果状态发生变化，记录事件
                    if (statusStr != lastStatus && lastStatus != null) {
                        val currentState = _coinAcceptorState.value
                        _coinAcceptorState.value = currentState.copy(
                            eventCount = currentState.eventCount + 1,
                            lastEvent = "状态变化: $lastStatus -> $statusStr",
                            lastStatus = statusStr
                        )
                        addLog("硬币器事件: $lastStatus -> $statusStr")
                        Log.d(TAG, "硬币器状态变化: $lastStatus -> $statusStr")
                    }
                    
                    lastStatus = statusStr
                    _coinAcceptorState.value = _coinAcceptorState.value.copy(lastStatus = statusStr)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "轮询硬币器状态异常", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }
    
    /**
     * 停止轮询硬币器状态
     */
    private fun stopCoinPolling() {
        coinPollingJob?.cancel()
        coinPollingJob = null
    }
    
    /**
     * 添加测试日志
     */
    private fun addLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logMessage = "[$timestamp] $message"
        Log.d(TAG, logMessage)
        _testLogs.value = (_testLogs.value + logMessage).takeLast(100) // 保留最近100条
    }
    
    override fun onCleared() {
        super.onCleared()
        stopBillPolling()
        stopCoinPolling()
    }
}
