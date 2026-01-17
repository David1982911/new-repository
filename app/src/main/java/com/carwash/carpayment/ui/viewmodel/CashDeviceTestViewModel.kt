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
        val lastStatus: String? = null,
        val sessionAmountCents: Int = 0,  // 本次会话累计金额（分）- sessionDeltaCents
        val sessionAmount: Double = 0.0,  // 本次会话累计金额（元）
        val totalAmountCents: Int = 0,    // 设备总库存金额（分）- currentTotalCents
        val totalAmount: Double = 0.0,    // 设备总库存金额（元）
        val levels: List<com.carwash.carpayment.data.cashdevice.LevelEntry> = emptyList(),  // 库存明细
        val isPayoutEnabled: Boolean = false  // 是否启用找零
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
     * 开始轮询纸币器状态和金额
     */
    private fun startBillPolling(deviceID: String) {
        stopBillPolling()
        billPollingJob = viewModelScope.launch {
            var lastStatus: String? = null
            while (true) {
                try {
                    // 轮询状态
                    val status = repository.getDeviceStatus(deviceID)
                    val statusStr = status.actualState ?: "UNKNOWN"
                    
                    // 轮询库存（金额）
                    val levelsResponse = repository.pollLevels(deviceID)
                    val tracker = repository.getAmountTracker()
                    val sessionCents = tracker.getDeviceSessionCents(deviceID)
                    val sessionAmount = sessionCents / 100.0
                    val totalCents = tracker.getDeviceCurrentCents(deviceID)
                    val totalAmount = totalCents / 100.0
                    val levels = levelsResponse.levels ?: emptyList()
                    
                    // 如果状态发生变化，记录事件
                    if (statusStr != lastStatus && lastStatus != null) {
                        val currentState = _billAcceptorState.value
                        _billAcceptorState.value = currentState.copy(
                            eventCount = currentState.eventCount + 1,
                            lastEvent = "状态变化: $lastStatus -> $statusStr",
                            lastStatus = statusStr,
                            sessionAmountCents = sessionCents,
                            sessionAmount = sessionAmount,
                            totalAmountCents = totalCents,
                            totalAmount = totalAmount,
                            levels = levels
                        )
                        addLog("纸币器事件: $lastStatus -> $statusStr")
                        Log.d(TAG, "纸币器状态变化: $lastStatus -> $statusStr")
                    }
                    
                    lastStatus = statusStr
                    _billAcceptorState.value = _billAcceptorState.value.copy(
                        lastStatus = statusStr,
                        sessionAmountCents = sessionCents,
                        sessionAmount = sessionAmount,
                        totalAmountCents = totalCents,
                        totalAmount = totalAmount,
                        levels = levels
                    )
                    
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
     * 开始轮询硬币器状态和金额
     */
    private fun startCoinPolling(deviceID: String) {
        stopCoinPolling()
        coinPollingJob = viewModelScope.launch {
            var lastStatus: String? = null
            while (true) {
                try {
                    // 轮询状态
                    val status = repository.getDeviceStatus(deviceID)
                    val statusStr = status.actualState ?: "UNKNOWN"
                    
                    // 轮询库存（金额）
                    val levelsResponse = repository.pollLevels(deviceID)
                    val tracker = repository.getAmountTracker()
                    val sessionCents = tracker.getDeviceSessionCents(deviceID)
                    val sessionAmount = sessionCents / 100.0
                    val totalCents = tracker.getDeviceCurrentCents(deviceID)
                    val totalAmount = totalCents / 100.0
                    val levels = levelsResponse.levels ?: emptyList()
                    
                    // 如果状态发生变化，记录事件
                    if (statusStr != lastStatus && lastStatus != null) {
                        val currentState = _coinAcceptorState.value
                        _coinAcceptorState.value = currentState.copy(
                            eventCount = currentState.eventCount + 1,
                            lastEvent = "状态变化: $lastStatus -> $statusStr",
                            lastStatus = statusStr,
                            sessionAmountCents = sessionCents,
                            sessionAmount = sessionAmount,
                            totalAmountCents = totalCents,
                            totalAmount = totalAmount,
                            levels = levels
                        )
                        addLog("硬币器事件: $lastStatus -> $statusStr")
                        Log.d(TAG, "硬币器状态变化: $lastStatus -> $statusStr")
                    }
                    
                    lastStatus = statusStr
                    _coinAcceptorState.value = _coinAcceptorState.value.copy(
                        lastStatus = statusStr,
                        sessionAmountCents = sessionCents,
                        sessionAmount = sessionAmount,
                        totalAmountCents = totalCents,
                        totalAmount = totalAmount,
                        levels = levels
                    )
                    
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
     * 开始新会话（重置金额跟踪器，重新采集基线）
     */
    fun startNewSession() {
        viewModelScope.launch {
            try {
                addLog("开始新会话：重置金额跟踪器")
                repository.getAmountTracker().reset()
                
                // 重新采集基线（如果设备已连接）
                val billDeviceID = repository.billAcceptorDeviceID.value
                val coinDeviceID = repository.coinAcceptorDeviceID.value
                
                if (billDeviceID != null) {
                    try {
                        val levelsResponse = repository.readCurrentLevels(billDeviceID)
                        val levels = levelsResponse.levels?.associate { it.value to it.stored } ?: emptyMap()
                        repository.getAmountTracker().setBaseline(billDeviceID, levels)
                        addLog("纸币器基线已重置")
                    } catch (e: Exception) {
                        Log.e(TAG, "重置纸币器基线失败", e)
                        addLog("重置纸币器基线失败: ${e.message}")
                    }
                }
                
                if (coinDeviceID != null) {
                    try {
                        val levelsResponse = repository.readCurrentLevels(coinDeviceID)
                        val levels = levelsResponse.levels?.associate { it.value to it.stored } ?: emptyMap()
                        repository.getAmountTracker().setBaseline(coinDeviceID, levels)
                        addLog("硬币器基线已重置")
                    } catch (e: Exception) {
                        Log.e(TAG, "重置硬币器基线失败", e)
                        addLog("重置硬币器基线失败: ${e.message}")
                    }
                }
                
                addLog("新会话已开始")
            } catch (e: Exception) {
                Log.e(TAG, "开始新会话异常", e)
                addLog("开始新会话异常: ${e.message}")
            }
        }
    }
    
    /**
     * 找零（纸币器）
     * @param valueCents 找零金额（分），如 200 表示 2€
     */
    fun dispenseBill(valueCents: Int) {
        viewModelScope.launch {
            val deviceID = repository.billAcceptorDeviceID.value
            if (deviceID == null) {
                addLog("纸币器未连接，无法找零")
                return@launch
            }
            
            try {
                addLog("纸币器找零: ${valueCents}分 (${valueCents / 100.0}元)")
                val success = repository.dispenseValue(deviceID, valueCents, "EUR")
                if (success) {
                    addLog("纸币器找零成功: ${valueCents}分")
                } else {
                    addLog("纸币器找零失败: ${valueCents}分")
                }
            } catch (e: Exception) {
                Log.e(TAG, "纸币器找零异常", e)
                addLog("纸币器找零异常: ${e.message}")
            }
        }
    }
    
    /**
     * 找零（硬币器）
     * @param valueCents 找零金额（分），如 200 表示 2€
     */
    fun dispenseCoin(valueCents: Int) {
        viewModelScope.launch {
            val deviceID = repository.coinAcceptorDeviceID.value
            if (deviceID == null) {
                addLog("硬币器未连接，无法找零")
                return@launch
            }
            
            try {
                addLog("硬币器找零: ${valueCents}分 (${valueCents / 100.0}元)")
                val success = repository.dispenseValue(deviceID, valueCents, "EUR")
                if (success) {
                    addLog("硬币器找零成功: ${valueCents}分")
                } else {
                    addLog("硬币器找零失败: ${valueCents}分")
                }
            } catch (e: Exception) {
                Log.e(TAG, "硬币器找零异常", e)
                addLog("硬币器找零异常: ${e.message}")
            }
        }
    }
    
    /**
     * 启用找零（纸币器）
     */
    fun enablePayoutBill() {
        viewModelScope.launch {
            val deviceID = repository.billAcceptorDeviceID.value
            if (deviceID == null) {
                addLog("纸币器未连接，无法启用找零")
                return@launch
            }
            
            try {
                addLog("启用纸币器找零...")
                val success = repository.enablePayout(deviceID)
                if (success) {
                    addLog("纸币器找零已启用")
                    _billAcceptorState.value = _billAcceptorState.value.copy(isPayoutEnabled = true)
                } else {
                    addLog("纸币器找零启用失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "启用纸币器找零异常", e)
                addLog("启用纸币器找零异常: ${e.message}")
            }
        }
    }
    
    /**
     * 禁用找零（纸币器）
     */
    fun disablePayoutBill() {
        viewModelScope.launch {
            val deviceID = repository.billAcceptorDeviceID.value
            if (deviceID == null) {
                addLog("纸币器未连接，无法禁用找零")
                return@launch
            }
            
            try {
                addLog("禁用纸币器找零...")
                val success = repository.disablePayout(deviceID)
                if (success) {
                    addLog("纸币器找零已禁用")
                    _billAcceptorState.value = _billAcceptorState.value.copy(isPayoutEnabled = false)
                } else {
                    addLog("纸币器找零禁用失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "禁用纸币器找零异常", e)
                addLog("禁用纸币器找零异常: ${e.message}")
            }
        }
    }
    
    /**
     * 启用找零（硬币器）
     */
    fun enablePayoutCoin() {
        viewModelScope.launch {
            val deviceID = repository.coinAcceptorDeviceID.value
            if (deviceID == null) {
                addLog("硬币器未连接，无法启用找零")
                return@launch
            }
            
            try {
                addLog("启用硬币器找零...")
                val success = repository.enablePayout(deviceID)
                if (success) {
                    addLog("硬币器找零已启用")
                    _coinAcceptorState.value = _coinAcceptorState.value.copy(isPayoutEnabled = true)
                } else {
                    addLog("硬币器找零启用失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "启用硬币器找零异常", e)
                addLog("启用硬币器找零异常: ${e.message}")
            }
        }
    }
    
    /**
     * 禁用找零（硬币器）
     */
    fun disablePayoutCoin() {
        viewModelScope.launch {
            val deviceID = repository.coinAcceptorDeviceID.value
            if (deviceID == null) {
                addLog("硬币器未连接，无法禁用找零")
                return@launch
            }
            
            try {
                addLog("禁用硬币器找零...")
                val success = repository.disablePayout(deviceID)
                if (success) {
                    addLog("硬币器找零已禁用")
                    _coinAcceptorState.value = _coinAcceptorState.value.copy(isPayoutEnabled = false)
                } else {
                    addLog("硬币器找零禁用失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "禁用硬币器找零异常", e)
                addLog("禁用硬币器找零异常: ${e.message}")
            }
        }
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
