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
        private const val STATUS_POLL_INTERVAL_MS = 1500L // 状态轮询间隔 1.5秒
        private const val ASSIGNMENT_POLL_INTERVAL_MS = 6000L // 面额轮询间隔 6秒
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
        val baselineCents: Int = 0,       // 会话基线金额（分）- baselineTotalReceivedCents
        val baselineAmount: Double = 0.0, // 会话基线金额（元）
        val currentCents: Int = 0,        // 当前总收款金额（分）- currentTotalReceivedCents
        val currentAmount: Double = 0.0,  // 当前总收款金额（元）
        val deltaCents: Int = 0,           // 会话增量金额（分）- delta = current - baseline
        val deltaAmount: Double = 0.0,    // 会话增量金额（元）
        val levels: List<com.carwash.carpayment.data.cashdevice.LevelEntry> = emptyList(),  // 库存明细（兼容旧接口）
        val assignments: List<com.carwash.carpayment.data.cashdevice.CurrencyAssignment> = emptyList(),  // 货币分配列表（基于 GetCurrencyAssignment）
        val recentChanges: List<com.carwash.carpayment.data.cashdevice.CashAmountTracker.AmountChange> = emptyList(),  // 最近变化明细（用于显示 "+5€ x1" 等）
        val routeChanging: Map<Int, Boolean> = emptyMap()  // 正在切换路由的面额（value -> true/false，用于 UI loading 状态）
    )
    
    
    private val _billAcceptorState = MutableStateFlow(DeviceState())
    val billAcceptorState: StateFlow<DeviceState> = _billAcceptorState.asStateFlow()
    
    private val _coinAcceptorState = MutableStateFlow(DeviceState())
    val coinAcceptorState: StateFlow<DeviceState> = _coinAcceptorState.asStateFlow()
    
    // 测试日志
    private val _testLogs = MutableStateFlow<List<String>>(emptyList())
    val testLogs: StateFlow<List<String>> = _testLogs.asStateFlow()
    
    // 编辑模式状态（纸币器路由编辑）
    private val _isEditMode = MutableStateFlow(false)
    val isEditMode: StateFlow<Boolean> = _isEditMode.asStateFlow()
    
    // 待应用的路由变更（value -> isRecyclable）
    private val _pendingRoutes = MutableStateFlow<Map<Int, Boolean>>(emptyMap())
    val pendingRoutes: StateFlow<Map<Int, Boolean>> = _pendingRoutes.asStateFlow()
    
    // 轮询任务（拆分为状态和面额两个 Job）
    private var billStatusJob: Job? = null
    private var billAssignmentJob: Job? = null
    private var coinStatusJob: Job? = null
    private var coinAssignmentJob: Job? = null
    
    // 当前激活的设备（true=纸币器, false=硬币器）
    private var activeDeviceIsBill: Boolean = true
    
    // 页面可见性状态
    private var screenVisible: Boolean = false
    
    // 写操作互斥锁（避免并发写操作）
    private val billCmdMutex = kotlinx.coroutines.sync.Mutex()
    private val coinCmdMutex = kotlinx.coroutines.sync.Mutex()
    
    /**
     * 设置页面可见性（页面进入/退出时调用）
     * @param visible true=页面可见, false=页面不可见
     */
    fun setScreenVisible(visible: Boolean) {
        if (screenVisible == visible) {
            return
        }
        
        screenVisible = visible
        Log.d(TAG, "页面可见性变化: $visible")
        
        if (!visible) {
            // 页面不可见：停止所有轮询
            stopBillPolling()
            stopCoinPolling()
            addLog("页面不可见 -> 停止轮询")
        } else {
            // 页面可见：启动当前激活设备的轮询
            addLog("页面可见 -> 可能启动轮询")
            if (activeDeviceIsBill) {
                val deviceID = _billAcceptorState.value.deviceID
                if (deviceID != null) {
                    startBillPolling(deviceID)
                }
            } else {
                val deviceID = _coinAcceptorState.value.deviceID
                if (deviceID != null) {
                    startCoinPolling(deviceID)
                }
            }
        }
    }
    
    /**
     * 切换到指定设备（选项卡切换时调用）
     * @param isBill true=纸币器, false=硬币器
     */
    fun switchToDevice(isBill: Boolean) {
        if (activeDeviceIsBill == isBill) {
            return  // 已经是当前设备，无需切换
        }
        
        Log.d(TAG, "切换设备: ${if (isBill) "纸币器" else "硬币器"}")
        
        // 停止前一个设备的轮询
        if (activeDeviceIsBill) {
            stopBillPolling()
        } else {
            stopCoinPolling()
        }
        
        activeDeviceIsBill = isBill
        
        // 启动当前设备的轮询（如果页面可见且已连接）
        if (screenVisible) {
            if (isBill) {
                val deviceID = _billAcceptorState.value.deviceID
                if (deviceID != null) {
                    startBillPolling(deviceID)
                }
            } else {
                val deviceID = _coinAcceptorState.value.deviceID
                if (deviceID != null) {
                    startCoinPolling(deviceID)
                }
            }
        }
    }
    
    /**
     * 带设备命令锁的写操作封装（串行执行，执行期间暂停轮询）
     * @param isBill true=纸币器, false=硬币器
     * @param block 要执行的写操作
     */
    private suspend fun withDeviceCommandLock(isBill: Boolean, block: suspend () -> Unit) {
        val mutex = if (isBill) billCmdMutex else coinCmdMutex
        
        mutex.lock()
        try {
            // 暂停该设备轮询
            if (isBill) {
                stopBillPolling()
            } else {
                stopCoinPolling()
            }
            
            // 执行写操作
            block()
            
            // 写操作后延迟，等待设备响应
            delay(400)
            
        } finally {
            mutex.unlock()
            
            // 恢复轮询（如果页面可见且当前激活设备匹配）
            if (screenVisible && activeDeviceIsBill == isBill) {
                val deviceID = if (isBill) {
                    _billAcceptorState.value.deviceID
                } else {
                    _coinAcceptorState.value.deviceID
                }
                
                if (deviceID != null) {
                    if (isBill) {
                        startBillPolling(deviceID)
                    } else {
                        startCoinPolling(deviceID)
                    }
                }
            }
        }
    }
    
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
                    // 只有当前激活设备是纸币器且页面可见时才启动轮询
                    if (activeDeviceIsBill && screenVisible) {
                    startBillPolling(deviceID)
                    }
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
                    // 只有当前激活设备是硬币器且页面可见时才启动轮询
                    if (!activeDeviceIsBill && screenVisible) {
                    startCoinPolling(deviceID)
                    }
                } else {
                    addLog("硬币器已断开")
                    stopCoinPolling()
                }
            }
        }
    }
    
    /**
     * 连接纸币器
     * ⚠️ 已废弃：设备在 APP 启动时自动连接，UI 不再允许用户触发连接/断开
     * 保留此方法仅用于内部测试，UI 不应调用
     */
    internal fun connectBillAcceptor() {
        viewModelScope.launch {
            try {
                addLog("开始连接纸币器 (SSP=0)...")
                // ⚠️ 检查是否正在支付中（防止重复连接）
                val isPaymentInProgress = false  // TODO: 从 PaymentViewModel 获取支付状态
                if (isPaymentInProgress) {
                    addLog("⚠️ 支付进行中，禁止重复连接纸币器")
                    Log.w(TAG, "支付进行中，禁止重复连接纸币器")
                    return@launch
                }
                
                addLog("开始连接纸币器...")
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
     * ⚠️ 已废弃：设备必须保持连接（配置依赖 AES128 会话），UI 不再允许用户触发断开
     * 保留此方法仅用于内部测试，UI 不应调用
     */
    internal fun disconnectBillAcceptor() {
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
                withDeviceCommandLock(true) {
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
    }
    
    /**
     * 禁用纸币器
     */
    fun disableBillAcceptor() {
        viewModelScope.launch {
            val deviceID = _billAcceptorState.value.deviceID
            if (deviceID != null) {
                withDeviceCommandLock(true) {
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
    }
    
    /**
     * 连接硬币器
     * ⚠️ 已废弃：设备在 APP 启动时自动连接，UI 不再允许用户触发连接/断开
     * 保留此方法仅用于内部测试，UI 不应调用
     */
    internal fun connectCoinAcceptor() {
        viewModelScope.launch {
            try {
                addLog("开始连接硬币器 (SSP=16)...")
                // ⚠️ 检查是否正在支付中（防止重复连接）
                val isPaymentInProgress = false  // TODO: 从 PaymentViewModel 获取支付状态
                if (isPaymentInProgress) {
                    addLog("⚠️ 支付进行中，禁止重复连接硬币器")
                    Log.w(TAG, "支付进行中，禁止重复连接硬币器")
                    return@launch
                }
                
                addLog("开始连接硬币器...")
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
     * ⚠️ 已废弃：设备必须保持连接（配置依赖 AES128 会话），UI 不再允许用户触发断开
     * 保留此方法仅用于内部测试，UI 不应调用
     */
    internal fun disconnectCoinAcceptor() {
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
                withDeviceCommandLock(false) {
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
    }
    
    /**
     * 禁用硬币器
     */
    fun disableCoinAcceptor() {
        viewModelScope.launch {
            val deviceID = _coinAcceptorState.value.deviceID
            if (deviceID != null) {
                withDeviceCommandLock(false) {
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
    }
    
    /**
     * 开始轮询纸币器状态和金额（拆分为两个 Job：状态轮询和面额轮询）
     */
    private fun startBillPolling(deviceID: String) {
        stopBillPolling()
        
        // 状态轮询 Job（1.5秒一次）
        billStatusJob = viewModelScope.launch {
            var lastStatus: String? = null
            while (true) {
                try {
                    Log.d(TAG, "poll status tick (bill)")
                    val status = repository.getDeviceStatus(deviceID)
                    val statusStr = status.actualState ?: "UNKNOWN"
                    
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
                    _billAcceptorState.value = _billAcceptorState.value.copy(
                        lastStatus = statusStr
                    )
                    
                } catch (e: Exception) {
                    Log.e(TAG, "轮询纸币器状态异常", e)
                }
                delay(STATUS_POLL_INTERVAL_MS)
            }
        }
        
        // 面额轮询 Job（6秒一次）
        billAssignmentJob = viewModelScope.launch {
            while (true) {
                try {
                    Log.d(TAG, "poll assignment tick (bill)")
                    val assignments = repository.pollCurrencyAssignments(deviceID)
                    val tracker = repository.getAmountTracker()
                    val sessionCents = tracker.getDeviceSessionCents(deviceID)
                    val sessionAmount = sessionCents / 100.0
                    val totalCents = tracker.getDeviceCurrentCents(deviceID)
                    val totalAmount = totalCents / 100.0
                    val recentChanges = tracker.getRecentChanges(deviceID)
                    
                    // 获取会话基线信息（baseline/current/delta）
                    val baselineInfo = repository.getSessionBaselineInfo(deviceID)
                    val (baselineCents, currentCents, deltaCents) = baselineInfo ?: Triple(0, totalCents, sessionCents)
                    
                    _billAcceptorState.value = _billAcceptorState.value.copy(
                        sessionAmountCents = sessionCents,
                        sessionAmount = sessionAmount,
                        totalAmountCents = totalCents,
                        totalAmount = totalAmount,
                        baselineCents = baselineCents,
                        baselineAmount = baselineCents / 100.0,
                        currentCents = currentCents,
                        currentAmount = currentCents / 100.0,
                        deltaCents = deltaCents,
                        deltaAmount = deltaCents / 100.0,
                        assignments = assignments,
                        recentChanges = recentChanges
                    )
                    
                } catch (e: Exception) {
                    Log.e(TAG, "轮询纸币器面额异常", e)
                }
                delay(ASSIGNMENT_POLL_INTERVAL_MS)
            }
        }
    }
    
    /**
     * 停止轮询纸币器状态
     */
    private fun stopBillPolling() {
        billStatusJob?.cancel()
        billStatusJob = null
        billAssignmentJob?.cancel()
        billAssignmentJob = null
    }
    
    /**
     * 开始轮询硬币器状态和金额（拆分为两个 Job：状态轮询和面额轮询）
     */
    private fun startCoinPolling(deviceID: String) {
        stopCoinPolling()
        
        // 状态轮询 Job（1.5秒一次）
        coinStatusJob = viewModelScope.launch {
            var lastStatus: String? = null
            while (true) {
                try {
                    Log.d(TAG, "poll status tick (coin)")
                    val status = repository.getDeviceStatus(deviceID)
                    val statusStr = status.actualState ?: "UNKNOWN"
                    
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
                    _coinAcceptorState.value = _coinAcceptorState.value.copy(
                        lastStatus = statusStr
                    )
                    
                } catch (e: Exception) {
                    Log.e(TAG, "轮询硬币器状态异常", e)
                }
                delay(STATUS_POLL_INTERVAL_MS)
            }
        }
        
        // 面额轮询 Job（6秒一次）
        coinAssignmentJob = viewModelScope.launch {
            while (true) {
                try {
                    Log.d(TAG, "poll assignment tick (coin)")
                    val assignments = repository.pollCurrencyAssignments(deviceID)
                    val tracker = repository.getAmountTracker()
                    val sessionCents = tracker.getDeviceSessionCents(deviceID)
                    val sessionAmount = sessionCents / 100.0
                    val totalCents = tracker.getDeviceCurrentCents(deviceID)
                    val totalAmount = totalCents / 100.0
                    val recentChanges = tracker.getRecentChanges(deviceID)
                    
                    // 获取会话基线信息（baseline/current/delta）
                    val baselineInfo = repository.getSessionBaselineInfo(deviceID)
                    val (baselineCents, currentCents, deltaCents) = baselineInfo ?: Triple(0, totalCents, sessionCents)
                    
                    _coinAcceptorState.value = _coinAcceptorState.value.copy(
                        sessionAmountCents = sessionCents,
                        sessionAmount = sessionAmount,
                        totalAmountCents = totalCents,
                        totalAmount = totalAmount,
                        baselineCents = baselineCents,
                        baselineAmount = baselineCents / 100.0,
                        currentCents = currentCents,
                        currentAmount = currentCents / 100.0,
                        deltaCents = deltaCents,
                        deltaAmount = deltaCents / 100.0,
                        assignments = assignments,
                        recentChanges = recentChanges
                    )
                    
                } catch (e: Exception) {
                    Log.e(TAG, "轮询硬币器面额异常", e)
                }
                delay(ASSIGNMENT_POLL_INTERVAL_MS)
            }
        }
    }
    
    /**
     * 停止轮询硬币器状态
     */
    private fun stopCoinPolling() {
        coinStatusJob?.cancel()
        coinStatusJob = null
        coinAssignmentJob?.cancel()
        coinAssignmentJob = null
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
            } catch (e: Exception) {
                Log.e(TAG, "开始新会话异常", e)
                addLog("开始新会话异常: ${e.message}")
            }
        }
    }
    
    /**
     * 重置现金支付 baseline（重新抓取 GetAllLevels 并覆盖 baseline）
     * ⚠️ Step B: 增加"baseline 重置"能力（解决"需要清零按钮"）
     * 只影响 APP 的会话差分，不动设备端
     */
    fun resetCashBaseline() {
        viewModelScope.launch {
            try {
                android.util.Log.d("CASH_BASELINE_RESET", "BEGIN")
                addLog("重置现金支付 baseline：重新抓取 GetAllLevels")
                
                val billDeviceID = repository.billAcceptorDeviceID.value
                val coinDeviceID = repository.coinAcceptorDeviceID.value
                
                if (billDeviceID != null) {
                    try {
                        val levelsResponse = repository.readCurrentLevels(billDeviceID)
                        repository.getBaselineStore().setBaselineLevels(billDeviceID, levelsResponse)
                        val totalCents = levelsResponse.calculateTotalCents()
                        android.util.Log.d("CASH_BASELINE_RESET", "billDeviceID=$billDeviceID totalCents=$totalCents")
                        addLog("纸币器 baseline 已重置: ${totalCents / 100.0}€")
                    } catch (e: Exception) {
                        Log.e(TAG, "重置纸币器 baseline 失败", e)
                        addLog("重置纸币器 baseline 失败: ${e.message}")
                    }
                } else {
                    addLog("纸币器未连接，跳过 baseline 重置")
                }
                
                if (coinDeviceID != null) {
                    try {
                        val levelsResponse = repository.readCurrentLevels(coinDeviceID)
                        repository.getBaselineStore().setBaselineLevels(coinDeviceID, levelsResponse)
                        val totalCents = levelsResponse.calculateTotalCents()
                        android.util.Log.d("CASH_BASELINE_RESET", "coinDeviceID=$coinDeviceID totalCents=$totalCents")
                        addLog("硬币器 baseline 已重置: ${totalCents / 100.0}€")
                    } catch (e: Exception) {
                        Log.e(TAG, "重置硬币器 baseline 失败", e)
                        addLog("重置硬币器 baseline 失败: ${e.message}")
                    }
                } else {
                    addLog("硬币器未连接，跳过 baseline 重置")
                }
                
                android.util.Log.d("CASH_BASELINE_RESET", "END")
                addLog("现金支付 baseline 重置完成")
            } catch (e: Exception) {
                Log.e(TAG, "重置现金支付 baseline 异常", e)
                addLog("重置现金支付 baseline 异常: ${e.message}")
            }
        }
    }
    
    /**
     * 重置会话基线（将基线设为当前值，相当于让delta归零）
     * @param deviceID 设备ID
     */
    fun resetSessionBaseline(deviceID: String) {
        viewModelScope.launch {
            try {
                val success = repository.resetSessionBaseline(deviceID)
                if (success) {
                    addLog("会话基线已重置: deviceID=$deviceID")
                    // 刷新状态
                    refreshDeviceStates()
                } else {
                    addLog("重置会话基线失败: deviceID=$deviceID")
                }
            } catch (e: Exception) {
                Log.e(TAG, "重置会话基线异常: deviceID=$deviceID", e)
                addLog("重置会话基线异常: ${e.message}")
            }
        }
    }
    
    /**
     * 获取会话基线信息（用于UI显示）
     * @param deviceID 设备ID
     * @return Triple(baselineCents, currentCents, deltaCents)
     */
    suspend fun getSessionBaselineInfo(deviceID: String): Triple<Int, Int, Int>? {
        return repository.getSessionBaselineInfo(deviceID)
    }
    
    /**
     * 刷新设备状态（用于重置基线后刷新）
     */
    private fun refreshDeviceStates() {
        viewModelScope.launch {
            val billDeviceID = repository.billAcceptorDeviceID.value
            val coinDeviceID = repository.coinAcceptorDeviceID.value
            
            if (billDeviceID != null) {
                try {
                    val assignments = repository.pollCurrencyAssignments(billDeviceID)
                    val tracker = repository.getAmountTracker()
                    val sessionCents = tracker.getDeviceSessionCents(billDeviceID)
                    val sessionAmount = sessionCents / 100.0
                    val totalCents = tracker.getDeviceCurrentCents(billDeviceID)
                    val totalAmount = totalCents / 100.0
                    val recentChanges = tracker.getRecentChanges(billDeviceID)
                    
                    val baselineInfo = repository.getSessionBaselineInfo(billDeviceID)
                    val (baselineCents, currentCents, deltaCents) = baselineInfo ?: Triple(0, totalCents, sessionCents)
                    
                    _billAcceptorState.value = _billAcceptorState.value.copy(
                        sessionAmountCents = sessionCents,
                        sessionAmount = sessionAmount,
                        totalAmountCents = totalCents,
                        totalAmount = totalAmount,
                        baselineCents = baselineCents,
                        baselineAmount = baselineCents / 100.0,
                        currentCents = currentCents,
                        currentAmount = currentCents / 100.0,
                        deltaCents = deltaCents,
                        deltaAmount = deltaCents / 100.0,
                        assignments = assignments,
                        recentChanges = recentChanges
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "刷新纸币器状态失败", e)
                }
            }
            
            if (coinDeviceID != null) {
                try {
                    val assignments = repository.pollCurrencyAssignments(coinDeviceID)
                    val tracker = repository.getAmountTracker()
                    val sessionCents = tracker.getDeviceSessionCents(coinDeviceID)
                    val sessionAmount = sessionCents / 100.0
                    val totalCents = tracker.getDeviceCurrentCents(coinDeviceID)
                    val totalAmount = totalCents / 100.0
                    val recentChanges = tracker.getRecentChanges(coinDeviceID)
                    
                    val baselineInfo = repository.getSessionBaselineInfo(coinDeviceID)
                    val (baselineCents, currentCents, deltaCents) = baselineInfo ?: Triple(0, totalCents, sessionCents)
                    
                    _coinAcceptorState.value = _coinAcceptorState.value.copy(
                        sessionAmountCents = sessionCents,
                        sessionAmount = sessionAmount,
                        totalAmountCents = totalCents,
                        totalAmount = totalAmount,
                        baselineCents = baselineCents,
                        baselineAmount = baselineCents / 100.0,
                        currentCents = currentCents,
                        currentAmount = currentCents / 100.0,
                        deltaCents = deltaCents,
                        deltaAmount = deltaCents / 100.0,
                        assignments = assignments,
                        recentChanges = recentChanges
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "刷新硬币器状态失败", e)
                }
            }
        }
    }
    
    /**
     * 找零（纸币器）
     * @param valueCents 找零金额（分），如 200 表示 2€
     */
    fun dispenseBill(valueCents: Int) {
        viewModelScope.launch {
            Log.d(TAG, "========== dispenseBill 开始 ==========")
            Log.d(TAG, "请求找零金额: ${valueCents}分 (${valueCents / 100.0}元)")
            
            // 1. 检查设备连接状态
            val deviceID = repository.billAcceptorDeviceID.value
            if (deviceID == null) {
                val errorMsg = "纸币器未连接，无法找零"
                Log.e(TAG, "设备连接状态检查失败: $errorMsg")
                addLog(errorMsg)
                return@launch
            }
            Log.d(TAG, "✓ 设备连接状态: 已连接, deviceID=$deviceID")
            
            // 2. 预检查：先获取货币分配，检查可找零路由和库存
            try {
                Log.d(TAG, "开始预检查: 获取货币分配...")
                val assignments = repository.fetchCurrencyAssignments(deviceID)
                Log.d(TAG, "货币分配获取成功: 共 ${assignments.size} 个面额")
                
                // 打印所有面额的详细信息（用于调试）
                assignments.forEach { assignment ->
                    Log.d(TAG, "  面额 ${assignment.value}分: IsRecyclable=${assignment.isRecyclable}, Stored=${assignment.stored}, AcceptRoute=${assignment.acceptRoute}, StoredInRecycler=${assignment.storedInRecycler}, StoredInCashbox=${assignment.storedInCashbox}")
                }
                
                val targetAssignment = assignments.find { it.value == valueCents }
                
                if (targetAssignment == null) {
                    val errorMsg = "找零预检查失败: 未找到面额 ${valueCents}分 的配置"
                    Log.w(TAG, "预检查失败: $errorMsg")
                    Log.w(TAG, "可用面额列表: ${assignments.map { it.value }.joinToString(", ")}")
                    addLog(errorMsg)
                    return@launch
                }
                Log.d(TAG, "✓ 找到目标面额: ${valueCents}分")
                
                // 3. 检查可找零路由和库存（IsRecyclable == true 且 Stored > 0）
                val isRecyclable = targetAssignment.isRecyclable == true
                val hasStored = targetAssignment.stored > 0
                val acceptRouteOk = targetAssignment.acceptRoute == "PAYOUT" || targetAssignment.acceptRoute == "RECYCLER"
                val canDispense = isRecyclable && hasStored && acceptRouteOk
                
                Log.d(TAG, "预检查结果:")
                Log.d(TAG, "  IsRecyclable: $isRecyclable (期望: true)")
                Log.d(TAG, "  Stored: ${targetAssignment.stored} (期望: > 0)")
                Log.d(TAG, "  AcceptRoute: ${targetAssignment.acceptRoute} (期望: PAYOUT 或 RECYCLER)")
                Log.d(TAG, "  StoredInRecycler: ${targetAssignment.storedInRecycler}")
                Log.d(TAG, "  StoredInCashbox: ${targetAssignment.storedInCashbox}")
                Log.d(TAG, "  IsInhibited: ${targetAssignment.isInhibited}")
                Log.d(TAG, "  Channel: ${targetAssignment.channel}")
                Log.d(TAG, "  可找零判断: canDispense=$canDispense")
                
                if (!canDispense) {
                    val reason = when {
                        !isRecyclable -> "IsRecyclable=false (不可找零)"
                        !hasStored -> "Stored=0 (库存不足, Stored=${targetAssignment.stored}, StoredInRecycler=${targetAssignment.storedInRecycler}, StoredInCashbox=${targetAssignment.storedInCashbox})"
                        !acceptRouteOk -> "AcceptRoute=${targetAssignment.acceptRoute} (期望 PAYOUT 或 RECYCLER)"
                        else -> "未知原因"
                    }
                    val errorMsg = "找零预检查失败: ${valueCents}分 - $reason"
                    Log.w(TAG, "预检查失败: $errorMsg")
                    addLog(errorMsg)
                    return@launch
                }
                
                // 预检查通过，记录详细信息
                Log.d(TAG, "✓ 预检查通过: 面额=${valueCents}分, AcceptRoute=${targetAssignment.acceptRoute}, StoredInRecycler=${targetAssignment.storedInRecycler}, Stored=${targetAssignment.stored}, StoredInCashbox=${targetAssignment.storedInCashbox}")
                
            } catch (e: Exception) {
                Log.e(TAG, "预检查异常", e)
                val errorMsg = "找零预检查异常: ${e.message}，继续尝试找零"
                Log.e(TAG, errorMsg, e)
                addLog(errorMsg)
            }
            
            // 4. 发送找零请求
            withDeviceCommandLock(true) {
                try {
                    Log.d(TAG, "========== 发送找零请求 ==========")
                    Log.d(TAG, "UI: 点击找零按钮, input=${valueCents / 100.0} EUR (${valueCents}分)")
                    Log.d(TAG, "VM: requestDispense amountCents=$valueCents, currency=EUR, deviceID=$deviceID")
                    addLog("纸币器找零: ${valueCents}分 (${valueCents / 100.0}元)")
                    
                    val success = repository.dispenseValue(deviceID, valueCents, "EUR")
                    
                    // 5. 处理响应结果
                    if (success) {
                        Log.d(TAG, "========== 找零成功 ==========")
                        Log.d(TAG, "<-- 200 OK Dispense successful")
                        Log.d(TAG, "找零金额: ${valueCents}分 (${valueCents / 100.0}元)")
                        addLog("✅ 纸币器找零成功: ${valueCents}分 (${valueCents / 100.0}元)")
                        
                        // 刷新货币分配
                        Log.d(TAG, "刷新货币分配...")
                        delay(500)
                        val refreshedAssignments = repository.fetchCurrencyAssignments(deviceID)
                        val tracker = repository.getAmountTracker()
                        tracker.updateFromAssignments(deviceID, refreshedAssignments)
                        _billAcceptorState.value = _billAcceptorState.value.copy(
                            assignments = refreshedAssignments
                        )
                        Log.d(TAG, "✓ 货币分配已刷新: 共 ${refreshedAssignments.size} 个面额")
                    } else {
                        Log.e(TAG, "========== 找零失败 ==========")
                        Log.e(TAG, "<-- 400/500 Dispense failed")
                        Log.e(TAG, "找零金额: ${valueCents}分 (${valueCents / 100.0}元)")
                        val errorMsg = "❌ 纸币器找零失败: ${valueCents}分 (库存不足或其他原因)"
                        Log.e(TAG, errorMsg)
                        addLog(errorMsg)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "========== 找零异常 ==========")
                    Log.e(TAG, "找零金额: ${valueCents}分 (${valueCents / 100.0}元)")
                    Log.e(TAG, "异常类型: ${e.javaClass.simpleName}")
                    Log.e(TAG, "异常消息: ${e.message}")
                    Log.e(TAG, "纸币器找零异常", e)
                    val errorMsg = "❌ 纸币器找零异常: ${e.message}"
                    Log.e(TAG, errorMsg)
                    addLog(errorMsg)
                }
            }
            Log.d(TAG, "========== dispenseBill 结束 ==========")
        }
    }
    
    /**
     * 找零（硬币器）
     * @param valueCents 找零金额（分），如 200 表示 2€
     */
    fun dispenseCoin(valueCents: Int) {
        viewModelScope.launch {
            Log.d(TAG, "========== dispenseCoin 开始 ==========")
            Log.d(TAG, "请求找零金额: ${valueCents}分 (${valueCents / 100.0}元)")
            
            // 1. 检查设备连接状态
            val deviceID = repository.coinAcceptorDeviceID.value
            if (deviceID == null) {
                val errorMsg = "硬币器未连接，无法找零"
                Log.e(TAG, "设备连接状态检查失败: $errorMsg")
                addLog(errorMsg)
                return@launch
            }
            Log.d(TAG, "✓ 设备连接状态: 已连接, deviceID=$deviceID")
            
            // 2. 预检查：先获取货币分配，检查可找零路由和库存
            try {
                Log.d(TAG, "开始预检查: 获取货币分配...")
                val assignments = repository.fetchCurrencyAssignments(deviceID)
                Log.d(TAG, "货币分配获取成功: 共 ${assignments.size} 个面额")
                
                // 打印所有面额的详细信息（用于调试）
                assignments.forEach { assignment ->
                    Log.d(TAG, "  面额 ${assignment.value}分: IsRecyclable=${assignment.isRecyclable}, Stored=${assignment.stored}, AcceptRoute=${assignment.acceptRoute}")
                }
                
                val targetAssignment = assignments.find { it.value == valueCents }
                
                if (targetAssignment == null) {
                    val errorMsg = "找零预检查失败: 未找到面额 ${valueCents}分 的配置"
                    Log.w(TAG, "预检查失败: $errorMsg")
                    Log.w(TAG, "可用面额列表: ${assignments.map { it.value }.joinToString(", ")}")
                    addLog(errorMsg)
                    return@launch
                }
                Log.d(TAG, "✓ 找到目标面额: ${valueCents}分")
                
                // 3. 检查可找零路由和库存（IsRecyclable == true 且 Stored > 0）
                val isRecyclable = targetAssignment.isRecyclable == true
                val hasStored = targetAssignment.stored > 0
                val acceptRouteOk = targetAssignment.acceptRoute == "PAYOUT" || targetAssignment.acceptRoute == "RECYCLER"
                val canDispense = isRecyclable && hasStored && acceptRouteOk
                
                Log.d(TAG, "预检查结果:")
                Log.d(TAG, "  IsRecyclable: $isRecyclable (期望: true)")
                Log.d(TAG, "  Stored: ${targetAssignment.stored} (期望: > 0)")
                Log.d(TAG, "  AcceptRoute: ${targetAssignment.acceptRoute} (期望: PAYOUT 或 RECYCLER)")
                Log.d(TAG, "  IsInhibited: ${targetAssignment.isInhibited}")
                Log.d(TAG, "  Channel: ${targetAssignment.channel}")
                Log.d(TAG, "  可找零判断: canDispense=$canDispense")
                
                if (!canDispense) {
                    val reason = when {
                        !isRecyclable -> "IsRecyclable=false (不可找零)"
                        !hasStored -> "Stored=0 (库存不足, Stored=${targetAssignment.stored})"
                        !acceptRouteOk -> "AcceptRoute=${targetAssignment.acceptRoute} (期望 PAYOUT 或 RECYCLER)"
                        else -> "未知原因"
                    }
                    val errorMsg = "找零预检查失败: ${valueCents}分 - $reason"
                    Log.w(TAG, "预检查失败: $errorMsg")
                    addLog(errorMsg)
                    return@launch
                }
                
                // 预检查通过，记录详细信息
                Log.d(TAG, "✓ 预检查通过: 面额=${valueCents}分, AcceptRoute=${targetAssignment.acceptRoute}, Stored=${targetAssignment.stored}")
                
            } catch (e: Exception) {
                Log.e(TAG, "预检查异常", e)
                val errorMsg = "找零预检查异常: ${e.message}，继续尝试找零"
                Log.e(TAG, errorMsg, e)
                addLog(errorMsg)
            }
            
            // 4. 发送找零请求
            withDeviceCommandLock(false) {
                try {
                    Log.d(TAG, "========== 发送找零请求 ==========")
                    Log.d(TAG, "UI: 点击找零按钮, input=${valueCents / 100.0} EUR (${valueCents}分)")
                    Log.d(TAG, "VM: requestDispense amountCents=$valueCents, currency=EUR, deviceID=$deviceID")
                    addLog("硬币器找零: ${valueCents}分 (${valueCents / 100.0}元)")
                    
                    val success = repository.dispenseValue(deviceID, valueCents, "EUR")
                    
                    // 5. 处理响应结果
                    if (success) {
                        Log.d(TAG, "========== 找零成功 ==========")
                        Log.d(TAG, "<-- 200 OK Dispense successful")
                        Log.d(TAG, "找零金额: ${valueCents}分 (${valueCents / 100.0}元)")
                        addLog("✅ 硬币器找零成功: ${valueCents}分 (${valueCents / 100.0}元)")
                        
                        // 刷新货币分配
                        Log.d(TAG, "刷新货币分配...")
                        delay(500)
                        val refreshedAssignments = repository.fetchCurrencyAssignments(deviceID)
                        val tracker = repository.getAmountTracker()
                        tracker.updateFromAssignments(deviceID, refreshedAssignments)
                        _coinAcceptorState.value = _coinAcceptorState.value.copy(
                            assignments = refreshedAssignments
                        )
                        Log.d(TAG, "✓ 货币分配已刷新: 共 ${refreshedAssignments.size} 个面额")
                    } else {
                        Log.e(TAG, "========== 找零失败 ==========")
                        Log.e(TAG, "<-- 400/500 Dispense failed")
                        Log.e(TAG, "找零金额: ${valueCents}分 (${valueCents / 100.0}元)")
                        val errorMsg = "❌ 硬币器找零失败: ${valueCents}分 (库存不足或其他原因)"
                        Log.e(TAG, errorMsg)
                        addLog(errorMsg)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "========== 找零异常 ==========")
                    Log.e(TAG, "找零金额: ${valueCents}分 (${valueCents / 100.0}元)")
                    Log.e(TAG, "异常类型: ${e.javaClass.simpleName}")
                    Log.e(TAG, "异常消息: ${e.message}")
                    Log.e(TAG, "硬币器找零异常", e)
                    val errorMsg = "❌ 硬币器找零异常: ${e.message}"
                    Log.e(TAG, errorMsg)
                    addLog(errorMsg)
                }
            }
            Log.d(TAG, "========== dispenseCoin 结束 ==========")
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
    
    /**
     * 切换编辑模式
     * @param enabled true=开启编辑模式, false=关闭编辑模式
     */
    fun setEditMode(enabled: Boolean) {
        _isEditMode.value = enabled
        if (!enabled) {
            // 关闭编辑模式时清空待应用的变更
            _pendingRoutes.value = emptyMap()
        }
    }
    
    // 路由切换互斥锁（同一时刻只允许一个 route 请求）
    private val routeChangeMutex = kotlinx.coroutines.sync.Mutex()
    
    /**
     * 切换面额可找零状态（纸币器）
     * 编辑模式下：仅修改本地状态，不立即下发
     * 非编辑模式下：立即调用 SetDenominationRoute（在线配置，不导致连接断开）
     * @param value 面额（分）
     * @param isRecyclable 是否可找零（true=Route=1进recycler，false=Route=0进cashbox）
     */
    fun toggleDenominationRecyclable(value: Int, isRecyclable: Boolean) {
        if (_isEditMode.value) {
            // 编辑模式：仅修改本地状态
            val currentPending = _pendingRoutes.value.toMutableMap()
            currentPending[value] = isRecyclable
            _pendingRoutes.value = currentPending
            Log.d(TAG, "编辑模式: 面额 ${value}分 设置为 ${if (isRecyclable) "可找零" else "不可找零"} (待应用)")
        } else {
            // 非编辑模式：立即调用 SetDenominationRoute（在线配置，不导致连接断开）
            viewModelScope.launch {
                val deviceID = repository.billAcceptorDeviceID.value
                if (deviceID == null) {
                    Log.w(TAG, "toggleDenominationRecyclable: 纸币器未连接，无法切换面额路由")
                    addLog("纸币器未连接，无法切换面额路由")
                    return@launch
                }
                
                // 设置 loading 状态
                val currentRouteChanging = _billAcceptorState.value.routeChanging.toMutableMap()
                currentRouteChanging[value] = true
                _billAcceptorState.value = _billAcceptorState.value.copy(routeChanging = currentRouteChanging)
                
                // 使用 withDeviceCommandLock 确保写操作串行化，并在执行期间暂停轮询
                // 注意：SetDenominationRoute 是在线配置，不会断开连接
                withDeviceCommandLock(true) {
                    routeChangeMutex.lock()
                    try {
                        // 获取面额的货币代码
                        val assignments = repository.getDeviceAssignments(deviceID)
                        val assignment = assignments.find { it.value == value }
                        val currency = assignment?.countryCode ?: "EUR"
                        
                        // Route: 1=RECYCLER（可找零），0=CASHBOX（不可找零）
                        // 使用探测模式：如果失败，尝试其他 route 值
                        val route = if (isRecyclable) 1 else 0
                        
                        Log.d(TAG, "toggleDenominationRecyclable: 调用 SetDenominationRoute (在线模式，不断开连接), deviceID=$deviceID, value=$value, currency=$currency, route=$route (探测模式)")
                        addLog("切换面额路由: ${value}分 -> ${if (isRecyclable) "可找零(Route=1)" else "不可找零(Route=0)"} (在线模式)")
                        
                        // 调用 SetDenominationRoute（在线配置，不导致连接断开）
                        val success = repository.setDenominationRoute(deviceID, value, currency, route, probeMode = true)
                        
                        Log.d("DEVICE_TEST", "SET_ROUTE value=$value route=$route device=$deviceID result=$success")
                        
                        if (success) {
                            Log.d(TAG, "toggleDenominationRecyclable: SetDenominationRoute 成功，刷新货币分配")
                            addLog("面额路由切换成功")
                            
                            // 延迟 400ms 等待设备响应
                            kotlinx.coroutines.delay(400)
                            
                            // 立即刷新货币分配
                            val refreshedAssignments = repository.fetchCurrencyAssignments(deviceID)
                            val tracker = repository.getAmountTracker()
                            tracker.updateFromAssignments(deviceID, refreshedAssignments)
                            _billAcceptorState.value = _billAcceptorState.value.copy(
                                assignments = refreshedAssignments
                            )
                        } else {
                            Log.w(TAG, "toggleDenominationRecyclable: SetDenominationRoute 失败，但设备保持连接状态")
                            addLog("面额路由切换失败（设备保持连接）")
                            // 注意：失败时不更新 UI 状态，但设备保持连接，可以重试
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "toggleDenominationRecyclable: 切换面额路由异常（设备保持连接）", e)
                        e.printStackTrace()
                        addLog("切换面额路由异常: ${e.message}（设备保持连接）")
                        // 注意：异常时不更新 UI 状态，但设备保持连接，可以重试
                    } finally {
                        routeChangeMutex.unlock()
                    }
                }
                
                // 清除 loading 状态（在 withDeviceCommandLock 外部，确保总是执行）
                val finalRouteChanging = _billAcceptorState.value.routeChanging.toMutableMap()
                finalRouteChanging.remove(value)
                _billAcceptorState.value = _billAcceptorState.value.copy(routeChanging = finalRouteChanging)
            }
        }
    }
    
    /**
     * 切换面额 Host Enable 状态（是否允许接收该面额）
     * @param value 面额（分）
     * @param isEnabled 是否允许接收（true=允许接收，false=禁止接收）
     */
    fun toggleDenominationEnabled(value: Int, isEnabled: Boolean) {
        viewModelScope.launch {
            val deviceID = repository.billAcceptorDeviceID.value
            if (deviceID == null) {
                Log.w(TAG, "toggleDenominationEnabled: 纸币器未连接，无法切换面额接收状态")
                addLog("纸币器未连接，无法切换面额接收状态")
                return@launch
            }
            
            Log.d(TAG, "toggleDenominationEnabled called: deviceID=$deviceID, value=$value, isEnabled=$isEnabled")
            addLog("切换面额接收状态: ${value}分 -> ${if (isEnabled) "允许接收" else "禁止接收"}")
            
            // 获取面额的货币代码
            val assignments = repository.getDeviceAssignments(deviceID)
            val assignment = assignments.find { it.value == value }
            val currency = assignment?.countryCode ?: "EUR"
            
            // 调用 SetDenominationInhibit API
            // inhibit = !isEnabled（isEnabled=true 表示允许接收，所以 inhibit=false）
            val inhibit = !isEnabled
            val success = repository.setDenominationInhibit(deviceID, value, currency, inhibit)
            
            Log.d("DEVICE_TEST", "SET_INHIBIT value=$value enabled=$isEnabled device=$deviceID result=$success")
            
            if (success) {
                Log.d(TAG, "toggleDenominationEnabled: SetDenominationInhibit 成功，刷新货币分配")
                addLog("面额接收状态切换成功")
                
                // 立即刷新货币分配
                val refreshedAssignments = repository.fetchCurrencyAssignments(deviceID)
                _billAcceptorState.value = _billAcceptorState.value.copy(
                    assignments = refreshedAssignments
                )
            } else {
                Log.e(TAG, "toggleDenominationEnabled: SetDenominationInhibit 失败")
                addLog("面额接收状态切换失败")
            }
        }
    }
    
    /**
     * 批量应用待应用的路由变更
     * @param pending 待应用的路由变更（value -> isRecyclable）
     */
    fun applyPendingRoutes(pending: Map<Int, Boolean>) {
        viewModelScope.launch {
            val deviceID = repository.billAcceptorDeviceID.value
            if (deviceID == null) {
                addLog("纸币器未连接，无法应用路由变更")
                return@launch
            }
            
            if (pending.isEmpty()) {
                addLog("没有待应用的路由变更")
                return@launch
            }
            
            val startTime = System.currentTimeMillis()
            
            withDeviceCommandLock(true) {
                try {
                    // 步骤 1: 禁用接收器（临时禁止收款，减少设备抖动和误投）
                    Log.d(TAG, "批量应用路由: 步骤1 - 禁用接收器")
                    addLog("批量应用路由: 禁用接收器...")
                    repository.disableAcceptor(deviceID)
                    
                    // 步骤 2: 获取当前货币分配
                    val assignments = repository.getDeviceAssignments(deviceID)
                    if (assignments.isEmpty()) {
                        addLog("纸币器没有货币分配数据，无法应用路由变更")
                        return@withDeviceCommandLock
                    }
                    
                    // 步骤 3: 计算应当可找零的面额列表
                    val recyclableValues = mutableListOf<Int>()
                    assignments.forEach { assignment ->
                        val pendingValue = pending[assignment.value]
                        if (pendingValue == true) {
                            recyclableValues.add(assignment.value)
                        } else if (pendingValue == null) {
                            // 没有待应用变更的，保持当前状态
                            if (assignment.acceptRoute == "PAYOUT" || assignment.isAcceptRouteRecyclable) {
                                recyclableValues.add(assignment.value)
                            }
                        }
                        // pendingValue == false 表示设置为不可找零，不加入列表
                    }
                    
                    Log.d(TAG, "批量应用路由: 步骤2 - 应用路由配置, 可找零面额数=${recyclableValues.size}, 变更数=${pending.size}")
                    addLog("批量应用路由: 应用 ${pending.size} 项变更...")
                    
                    // 步骤 4: 调用 applyRoutesFromUI
                    val success = repository.applyRoutesFromUI(deviceID, assignments, recyclableValues)
                    
                    if (!success) {
                        addLog("批量应用路由失败")
                        return@withDeviceCommandLock
                    }
                    
                    // 步骤 5: 延迟等待设备响应
                    delay(400)
                    
                    // 步骤 6: 刷新货币分配
                    Log.d(TAG, "批量应用路由: 步骤3 - 刷新货币分配")
                    val refreshedAssignments = repository.fetchCurrencyAssignments(deviceID)
                    val tracker = repository.getAmountTracker()
                    tracker.updateFromAssignments(deviceID, refreshedAssignments)
                    _billAcceptorState.value = _billAcceptorState.value.copy(
                        assignments = refreshedAssignments
                    )
                    
                    // 步骤 7: 启用接收器（恢复收款）
                    Log.d(TAG, "批量应用路由: 步骤4 - 启用接收器")
                    addLog("批量应用路由: 启用接收器...")
                    repository.enableAcceptor(deviceID)
                    
                    val elapsed = System.currentTimeMillis() - startTime
                    val changedDenoms = pending.map { (value, isRecyclable) ->
                        "${value}分->${if (isRecyclable) "可找零" else "不可找零"}"
                    }.joinToString(", ")
                    
                    addLog("批量应用路由成功: $changedDenoms (耗时 ${elapsed}ms)")
                    Log.d(TAG, "批量应用路由成功: 变更数=${pending.size}, 耗时=${elapsed}ms")
                    
                    // 清空待应用的变更
                    _pendingRoutes.value = emptyMap()
                    
                } catch (e: Exception) {
                    Log.e(TAG, "批量应用路由异常", e)
                    e.printStackTrace()
                    addLog("批量应用路由异常: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 智能清空（清空循环鼓 recycler）- 仅纸币器
     */
    /**
     * 切换面额 Host Enable 状态（硬币器）
     * @param value 面额（分）
     * @param isEnabled 是否允许接收（true=允许接收，false=禁止接收）
     */
    fun toggleDenominationEnabledCoin(value: Int, isEnabled: Boolean) {
        viewModelScope.launch {
            val deviceID = repository.coinAcceptorDeviceID.value
            if (deviceID == null) {
                Log.w(TAG, "toggleDenominationEnabledCoin: 硬币器未连接，无法切换面额接收状态")
                addLog("硬币器未连接，无法切换面额接收状态")
                return@launch
            }
            
            Log.d(TAG, "toggleDenominationEnabledCoin called: deviceID=$deviceID, value=$value, isEnabled=$isEnabled")
            addLog("切换面额接收状态: ${value}分 -> ${if (isEnabled) "允许接收" else "禁止接收"}")
            
            // 获取面额的货币代码
            val assignments = repository.getDeviceAssignments(deviceID)
            val assignment = assignments.find { it.value == value }
            val currency = assignment?.countryCode ?: "EUR"
            
            // 调用 SetDenominationInhibit API
            // inhibit = !isEnabled（isEnabled=true 表示允许接收，所以 inhibit=false）
            val inhibit = !isEnabled
            val success = repository.setDenominationInhibit(deviceID, value, currency, inhibit)
            
            Log.d("DEVICE_TEST", "SET_INHIBIT value=$value enabled=$isEnabled device=$deviceID result=$success")
            
            if (success) {
                Log.d(TAG, "toggleDenominationEnabledCoin: SetDenominationInhibit 成功，刷新货币分配")
                addLog("面额接收状态切换成功")
                
                // 立即刷新货币分配
                val refreshedAssignments = repository.fetchCurrencyAssignments(deviceID)
                _coinAcceptorState.value = _coinAcceptorState.value.copy(
                    assignments = refreshedAssignments
                )
            } else {
                Log.e(TAG, "toggleDenominationEnabledCoin: SetDenominationInhibit 失败")
                addLog("面额接收状态切换失败")
            }
        }
    }
    
    /**
     * 切换面额 Route To Payout 状态（硬币器）
     * @param value 面额（分）
     * @param isRecyclable 是否可找零（true=可找零，false=不可找零）
     */
    fun toggleDenominationRecyclableCoin(value: Int, isRecyclable: Boolean) {
        viewModelScope.launch {
            val deviceID = repository.coinAcceptorDeviceID.value
            if (deviceID == null) {
                Log.w(TAG, "toggleDenominationRecyclableCoin: 硬币器未连接，无法切换面额路由")
                addLog("硬币器未连接，无法切换面额路由")
                return@launch
            }
            
            Log.d(TAG, "toggleDenominationRecyclableCoin called: deviceID=$deviceID, value=$value, isRecyclable=$isRecyclable")
            
            // 获取面额的货币代码
            val assignments = repository.getDeviceAssignments(deviceID)
            val assignment = assignments.find { it.value == value }
            val currency = assignment?.countryCode ?: "EUR"
            
            // 设置 loading 状态
            val currentRouteChanging = _coinAcceptorState.value.routeChanging.toMutableMap()
            currentRouteChanging[value] = true
            _coinAcceptorState.value = _coinAcceptorState.value.copy(routeChanging = currentRouteChanging)
            
            try {
                // Route 值：1=RECYCLER（可找零），0=CASHBOX（不可找零）
                val route = if (isRecyclable) 1 else 0
                
                Log.d(TAG, "toggleDenominationRecyclableCoin: 调用 SetDenominationRoute (在线模式，不断开连接), deviceID=$deviceID, value=$value, currency=$currency, route=$route")
                addLog("切换面额路由: ${value}分 -> ${if (isRecyclable) "可找零(Route=1)" else "不可找零(Route=0)"} (在线模式)")
                
                // 调用 SetDenominationRoute（在线配置，不导致连接断开）
                val success = repository.setDenominationRoute(deviceID, value, currency, route, probeMode = true)
                
                Log.d("DEVICE_TEST", "SET_ROUTE value=$value route=$route device=$deviceID result=$success")
                
                if (success) {
                    Log.d(TAG, "toggleDenominationRecyclableCoin: SetDenominationRoute 成功，刷新货币分配")
                    addLog("面额路由切换成功")
                    
                    // 延迟 400ms 等待设备响应
                    kotlinx.coroutines.delay(400)
                    
                    // 立即刷新货币分配
                    val refreshedAssignments = repository.fetchCurrencyAssignments(deviceID)
                    val tracker = repository.getAmountTracker()
                    tracker.updateFromAssignments(deviceID, refreshedAssignments)
                    _coinAcceptorState.value = _coinAcceptorState.value.copy(
                        assignments = refreshedAssignments
                    )
                } else {
                    Log.w(TAG, "toggleDenominationRecyclableCoin: SetDenominationRoute 失败，但设备保持连接状态")
                    addLog("面额路由切换失败，但设备保持连接状态")
                }
            } catch (e: Exception) {
                Log.e(TAG, "toggleDenominationRecyclableCoin: 异常", e)
                addLog("切换面额路由异常: ${e.message}")
            } finally {
                // 清除 loading 状态
                val finalRouteChanging = _coinAcceptorState.value.routeChanging.toMutableMap()
                finalRouteChanging.remove(value)
                _coinAcceptorState.value = _coinAcceptorState.value.copy(routeChanging = finalRouteChanging)
            }
        }
    }
    
    /**
     * 智能清空（纸币器）
     */
    fun smartEmptyBill() {
        viewModelScope.launch {
            withDeviceCommandLock(true) {
                try {
                    Log.d(TAG, "========== SmartEmpty 纸币器 ==========")
                    addLog("执行智能清空（清空循环鼓）...")
                    val success = repository.smartEmptyBill()
                    val deviceID = repository.billAcceptorDeviceID.value
                    Log.d("DEVICE_TEST", "SMART_EMPTY device=$deviceID result=$success")
                    if (success) {
                        Log.d(TAG, "SmartEmpty 纸币器成功")
                        addLog("✅ 智能清空成功")
                        // 刷新货币分配
                        delay(500)
                        val refreshedDeviceID = repository.billAcceptorDeviceID.value
                        if (refreshedDeviceID != null) {
                            val refreshedAssignments = repository.fetchCurrencyAssignments(refreshedDeviceID)
                            val tracker = repository.getAmountTracker()
                            tracker.updateFromAssignments(refreshedDeviceID, refreshedAssignments)
                            _billAcceptorState.value = _billAcceptorState.value.copy(
                                assignments = refreshedAssignments
                            )
                        }
                    } else {
                        Log.e(TAG, "SmartEmpty 纸币器失败")
                        addLog("❌ 智能清空失败")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "SmartEmpty 纸币器异常", e)
                    addLog("❌ 智能清空异常: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 智能清空（硬币器）
     */
    fun smartEmptyCoin() {
        viewModelScope.launch {
            withDeviceCommandLock(false) {
                try {
                    Log.d(TAG, "========== SmartEmpty 硬币器 ==========")
                    addLog("执行智能清空（清空库存）...")
                    val success = repository.smartEmptyCoin()
                    val deviceID = repository.coinAcceptorDeviceID.value
                    Log.d("DEVICE_TEST", "SMART_EMPTY device=$deviceID result=$success")
                    if (success) {
                        Log.d(TAG, "SmartEmpty 硬币器成功")
                        addLog("✅ 智能清空成功")
                        // 刷新货币分配
                        delay(500)
                        val refreshedDeviceID = repository.coinAcceptorDeviceID.value
                        if (refreshedDeviceID != null) {
                            val refreshedAssignments = repository.fetchCurrencyAssignments(refreshedDeviceID)
                            val tracker = repository.getAmountTracker()
                            tracker.updateFromAssignments(refreshedDeviceID, refreshedAssignments)
                            _coinAcceptorState.value = _coinAcceptorState.value.copy(
                                assignments = refreshedAssignments
                            )
                        }
                    } else {
                        Log.e(TAG, "SmartEmpty 硬币器失败")
                        addLog("❌ 智能清空失败")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "SmartEmpty 硬币器异常", e)
                    addLog("❌ 智能清空异常: ${e.message}")
                }
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        stopBillPolling()
        stopCoinPolling()
    }
}
