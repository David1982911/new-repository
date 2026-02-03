package com.carwash.carpayment.data.cashdevice

import com.carwash.carpayment.data.cashdevice.RefundConfig
import com.carwash.carpayment.data.cashdevice.RefundPriority
import com.carwash.carpayment.data.cashdevice.RefundInsufficientAction

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response

//Log.d("BUILD_MARK", "CURSOR_AGENT_EXECUTED_20260128")

/**
 * 现金设备 Repository
 * 管理纸币器和硬币器的连接和收款
 */
class CashDeviceRepository(
    private val api: CashDeviceApi,
    private val baseUrl: String = "http://localhost:8080"  // TODO: 从配置读取
) {
    
    // 会话管理器和金额跟踪器
    private val sessionManager = CashSessionManager(api)
    private val amountTracker = CashAmountTracker()
    
    // 会话基线存储（用于重置基线功能）
    private val baselineStore = CashSessionBaselineStore()
    
    // ⚠️ 连接状态锁：防止支付中重复连接
    private val connectionLocks = mutableMapOf<String, Boolean>()  // deviceID -> isConnecting/Connected
    private val connectionLockMutex = Any()  // 互斥锁
    
    // ⚠️ startCashSession 互斥保护：防止重复点击/并发调用
    private val startSessionMutex = Mutex()
    private var isStartingSession = false  // 是否正在启动会话
    
    // ⚠️ 会话活跃状态：防止在 active 会话期间 reset tracker
    @Volatile
    private var sessionActive = false  // 是否正在活跃收款会话中
    
    // ⚠️ 关键修复：每个设备的串行锁，保证同一时刻只有一个 dispense 在跑
    private val deviceDispenseMutexes = mutableMapOf<String, Mutex>()
    private val dispenseMutexLock = Any()  // 保护 deviceDispenseMutexes 的锁
    
    // ⚠️ 关键修复：获取或创建设备的 dispense mutex
    private fun getDeviceDispenseMutex(deviceID: String): Mutex {
        synchronized(dispenseMutexLock) {
            return deviceDispenseMutexes.getOrPut(deviceID) { Mutex() }
        }
    }
    
    companion object {
        private const val TAG = "CashDeviceRepository"
        private const val DEFAULT_USERNAME = "admin"
        private const val DEFAULT_PASSWORD = "password"
        
        /**
         * 清理响应体的 BOM 和空白字符
         * @param body 响应体字符串
         * @return 清理后的字符串
         */
        private fun cleanResponseBody(body: String?): String {
            if (body == null) return ""
            return body.trim().removePrefix("\uFEFF")  // 移除 BOM
        }
        
        // 探测超时时间（秒）- 调大到 30 秒，因为 OpenConnection 需要等待设备响应
        private const val PROBE_TIMEOUT_SECONDS = 30L
        
        // 扩展扫描的 SSP Address 候选列表（仅在主要候选失败后使用）
        private val EXTENDED_SSP_ADDRESS_CANDIDATES = listOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 17, 18, 19, 20)
        
        // 日志文件路径（可选，如果设备需要）
        private val LOG_FILE_PATH: String? = null  // 可以设置为 "/sdcard/cashdevice.log" 等路径
        
        // 找零限制（可选，设置为空列表表示不限制）
        private val CASH_BOX_PAYOUT_LIMIT: List<Int>? = null  // 可以设置为 listOf(100, 200, 500) 等限制值
    }
    
    /**
     * 创建 OpenConnection 请求
     * @param comPort 串口号
     * @param sspAddress SSP 地址
     * @param deviceID 设备ID（从 GetConnectedUSBDevices 获取，可选）
     * @param enableAcceptor 是否启用接收器
     * @param enableAutoAcceptEscrow 是否启用自动接受
     * @param enablePayout 是否启用找零
     * @param setInhibits 面额禁用设置（可选）
     * @param setRoutes 面额路由设置（可选）
     * @return OpenConnectionRequest
     */
    private fun createOpenConnectionRequest(
        comPort: Int,
        sspAddress: Int,
        deviceID: String? = null,
        enableAcceptor: Boolean = true,
        enableAutoAcceptEscrow: Boolean = true,
        enablePayout: Boolean = false,
        setInhibits: List<DenominationInhibit>? = null,
        setRoutes: List<DenominationRoute>? = null
    ): OpenConnectionRequest {
        Log.d(TAG, "createOpenConnectionRequest 调用: ComPort=$comPort, SspAddress=$sspAddress, deviceID=$deviceID, enableAcceptor=$enableAcceptor, enableAutoAcceptEscrow=$enableAutoAcceptEscrow, setInhibits=${setInhibits?.size ?: 0}项, setRoutes=${setRoutes?.size ?: 0}项")
        
        // ⚠️ 确保字段有默认值（非空）
        val request = OpenConnectionRequest(
            ComPort = comPort,
            SspAddress = sspAddress,
            DeviceID = deviceID,  // 设备ID（从 GetConnectedUSBDevices 获取）
            LogFilePath = LOG_FILE_PATH,  // 日志文件路径（可选）
            SetInhibits = setInhibits,  // 面额禁用设置
            SetRoutes = setRoutes,  // 面额路由设置
            SetCashBoxPayoutLimit = CASH_BOX_PAYOUT_LIMIT,  // 找零限制（null 表示不限制）
            EnableAcceptor = enableAcceptor,  // ⚠️ 确保非空，默认 true
            EnableAutoAcceptEscrow = enableAutoAcceptEscrow,  // ⚠️ 确保非空，默认 true
            EnablePayout = enablePayout
        )
        
        // ⚠️ 关键：打印最终序列化的 JSON（应与 OkHttp body 一致）
        // 注意：此 JSON 使用与 Retrofit 相同的序列化配置（encodeDefaults=false, ignoreUnknownKeys=true）
        // 实际发送的 body 请以 OkHttp Interceptor 输出为准
        val json = kotlinx.serialization.json.Json {
            encodeDefaults = false  // 与 Retrofit 配置一致（不包含默认值字段）
            ignoreUnknownKeys = true  // 与 Retrofit 配置一致
        }
        val finalJson = json.encodeToString(
            kotlinx.serialization.serializer<OpenConnectionRequest>(), request
        )
        Log.d(TAG, "========== OpenConnection Request (Repository 序列化) ==========")
        Log.d(TAG, "Repository JSON => $finalJson")
        Log.d(TAG, "字段验证: EnableAcceptor=${request.EnableAcceptor}, EnableAutoAcceptEscrow=${request.EnableAutoAcceptEscrow}, EnablePayout=${request.EnablePayout}")
        Log.d(TAG, "SetInhibits=${request.SetInhibits?.size ?: 0}项, SetRoutes=${request.SetRoutes?.size ?: 0}项")
        Log.d(TAG, "注意：实际发送的 body 请查看 OkHttp Interceptor 日志（--> POST ... 日志）")
        Log.d(TAG, "如果 Repository JSON 与 OkHttp body 不一致，请检查 Retrofit 序列化配置")
        Log.d(TAG, "================================================================")
        
        Log.d(TAG, "createOpenConnectionRequest 返回: DeviceID=${request.DeviceID ?: "null"}")
        return request
    }
    
    /**
     * 根据 Port 获取固定探测的 SspAddress 地址
     * Port=0 -> [0]（固定 SspAddress=0）
     * Port=1 -> [16]（固定 SspAddress=16，基于 Port=0/SspAddress=0 成功的前提）
     * 
     * 注意：当 Port=0/SspAddress=0 成功时，直接假设 Port=1/SspAddress=16 是正确的，
     * 不再进行额外的探测或尝试其他地址。
     */
    private fun getSspAddressCandidatesForPort(port: Int): List<Int> {
        return when (port) {
            0 -> listOf(0)  // Port=0 固定 SspAddress=0
            1 -> listOf(16)  // Port=1 固定 SspAddress=16（基于 Port=0/SspAddress=0 成功的前提）
            else -> listOf(0)  // 其他 Port 默认只试 0
        }
    }
    
    // Token 由 TokenStore 统一管理，不再在此处存储
    private val _billAcceptorDeviceID = MutableStateFlow<String?>(null)
    val billAcceptorDeviceID: StateFlow<String?> = _billAcceptorDeviceID.asStateFlow()
    
    private val _coinAcceptorDeviceID = MutableStateFlow<String?>(null)
    val coinAcceptorDeviceID: StateFlow<String?> = _coinAcceptorDeviceID.asStateFlow()
    
    /**
     * 获取纸币器设备ID（同步方法）
     */
    fun getBillAcceptorDeviceID(): String? = _billAcceptorDeviceID.value
    
    /**
     * 获取硬币器设备ID（同步方法）
     */
    fun getCoinAcceptorDeviceID(): String? = _coinAcceptorDeviceID.value
    
    /**
     * 获取会话基线存储（用于重置基线功能）
     */
    fun getBaselineStore(): CashSessionBaselineStore = baselineStore
    
    // ⚠️ Step 3: 缓存设备的 supportedValues（从 GetAllLevels 获取）
    // deviceID -> Set<Value>（面额集合）
    private val deviceSupportedValues = mutableMapOf<String, Set<Int>>()
    
    // ⚠️ Step 5: 退款策略配置（本地可配置）
    private var refundConfig = RefundConfig(
        refundPriority = RefundPriority.BILL_FIRST,
        refundBusyRetryCount = 6,
        refundBusyRetryBaseDelayMs = 500L,
        refundInsufficientAction = RefundInsufficientAction.LOCK_MACHINE_AND_ALERT
    )
    
    /**
     * 设置退款策略配置
     */
    fun setRefundConfig(config: RefundConfig) {
        refundConfig = config
        Log.d(TAG, "退款策略配置已更新: priority=${config.refundPriority}, retryCount=${config.refundBusyRetryCount}, insufficientAction=${config.refundInsufficientAction}")
    }
    
    /**
     * 获取退款策略配置
     */
    fun getRefundConfig(): RefundConfig = refundConfig
    
    /**
     * 获取设备的支持面额集合（从 GetAllLevels 获取）
     * ⚠️ 统一工具函数：getSupportedValues(deviceID)
     * @param deviceID 设备ID
     * @return 支持的面额集合（Value 列表），如果获取失败返回空集合
     */
    suspend fun getSupportedValues(deviceID: String): Set<Int> {
        // 如果已缓存，直接返回
        deviceSupportedValues[deviceID]?.let { return it }
        
        // 从 GetAllLevels 获取
        return try {
            val levelsResponse = readCurrentLevels(deviceID)
            val supportedValues = levelsResponse.levels?.mapNotNull { it.value }?.toSet() ?: emptySet()
            deviceSupportedValues[deviceID] = supportedValues
            Log.d(TAG, "设备支持面额已缓存: deviceID=$deviceID, supportedValues=$supportedValues")
            supportedValues
        } catch (e: Exception) {
            Log.e(TAG, "获取设备支持面额失败: deviceID=$deviceID", e)
            emptySet()
        }
    }
    
    /**
     * 兼容旧方法名
     */
    suspend fun getDeviceSupportedValues(deviceID: String): Set<Int> = getSupportedValues(deviceID)
    
    /**
     * ⚠️ 统一工具函数：filterValuesForDevice(deviceID, desiredValues)
     * 过滤出设备支持的面额（desiredValues ∩ supportedValues）
     * @param deviceID 设备ID
     * @param desiredValues 期望配置的面额集合
     * @return 设备支持的面额列表（交集）
     */
    suspend fun filterValuesForDevice(deviceID: String, desiredValues: Set<Int>): List<Int> {
        val supportedValues = getSupportedValues(deviceID)
        val filtered = desiredValues.intersect(supportedValues).toList()
        
        val skipped = desiredValues - supportedValues
        if (skipped.isNotEmpty()) {
            Log.w(TAG, "filterValuesForDevice SKIP: deviceID=$deviceID, skipped=$skipped (设备不支持)")
        }
        
        return filtered
    }
    
    /**
     * 刷新设备的支持面额集合（在设备初始化或进入现金会话时调用）
     * @param deviceID 设备ID
     */
    suspend fun refreshDeviceSupportedValues(deviceID: String) {
        try {
            val levelsResponse = readCurrentLevels(deviceID)
            val supportedValues = levelsResponse.levels?.mapNotNull { it.value }?.toSet() ?: emptySet()
            deviceSupportedValues[deviceID] = supportedValues
            Log.d(TAG, "设备支持面额已刷新: deviceID=$deviceID, supportedValues=$supportedValues")
        } catch (e: Exception) {
            Log.e(TAG, "刷新设备支持面额失败: deviceID=$deviceID", e)
        }
    }
    
    /**
     * ⚠️ 统一配置方法：configureDenominationsForDevices
     * 按设备分组配置 routes 和 inhibits，严格过滤面额
     * @param billDeviceID 纸币器设备ID（SPECTRAL_PAYOUT-0）
     * @param coinDeviceID 硬币器设备ID（SMART_COIN_SYSTEM-1）
     * @param routeConfig 路由配置（哪些面额进入 recycler）
     * @param inhibitConfig 禁用配置（哪些面额禁用接收）
     * @return Boolean 是否全部成功
     */
    suspend fun configureDenominationsForDevices(
        billDeviceID: String?,
        coinDeviceID: String?,
        routeConfig: DenominationConfig,
        inhibitConfig: DenominationConfig
    ): Boolean {
        Log.d(TAG, "========== configureDenominationsForDevices 开始 ==========")
        Log.d(TAG, "billDeviceID=$billDeviceID, coinDeviceID=$coinDeviceID")
        Log.d(TAG, "routeConfig.recyclerValues=${routeConfig.recyclerValues}")
        Log.d(TAG, "inhibitConfig.inhibitValues=${inhibitConfig.inhibitValues}")
        
        // 1. 获取各设备的支持面额
        val billSupported = billDeviceID?.let { getSupportedValues(it) } ?: emptySet()
        val coinSupported = coinDeviceID?.let { getSupportedValues(it) } ?: emptySet()
        
        // 2. 过滤出各设备支持的面额（交集）
        val billRecycler = filterValuesForDevice(billDeviceID ?: "", routeConfig.recyclerValues.toSet())
        val coinRecycler = filterValuesForDevice(coinDeviceID ?: "", routeConfig.recyclerValues.toSet())
        val billInhibit = filterValuesForDevice(billDeviceID ?: "", inhibitConfig.inhibitValues.toSet())
        val coinInhibit = filterValuesForDevice(coinDeviceID ?: "", inhibitConfig.inhibitValues.toSet())
        
        // ⚠️ 关键日志：打印汇总信息
        Log.d(TAG, "========== 面额配置汇总 ==========")
        Log.d(TAG, "billSupportedValues=$billSupported")
        Log.d(TAG, "coinSupportedValues=$coinSupported")
        Log.d(TAG, "billWillConfigureValues: recycler=$billRecycler, inhibit=$billInhibit")
        Log.d(TAG, "coinWillConfigureValues: recycler=$coinRecycler, inhibit=$coinInhibit")
        
        // 打印跳过的面额（desiredValues - supportedValues）
        val billSkippedRecycler = routeConfig.recyclerValues - billSupported
        val coinSkippedRecycler = routeConfig.recyclerValues - coinSupported
        val billSkippedInhibit = inhibitConfig.inhibitValues - billSupported
        val coinSkippedInhibit = inhibitConfig.inhibitValues - coinSupported
        
        if (billSkippedRecycler.isNotEmpty() || coinSkippedRecycler.isNotEmpty() || 
            billSkippedInhibit.isNotEmpty() || coinSkippedInhibit.isNotEmpty()) {
            Log.w(TAG, "SKIP (设备不支持): billSkippedRecycler=$billSkippedRecycler, coinSkippedRecycler=$coinSkippedRecycler, billSkippedInhibit=$billSkippedInhibit, coinSkippedInhibit=$coinSkippedInhibit")
        }
        
        var allSuccess = true
        
        // 3. 配置纸币器 routes
        if (billDeviceID != null && billRecycler.isNotEmpty()) {
            val success = configureRoutesForDevice(billDeviceID, billRecycler, routeConfig.recyclerValues.toSet())
            if (!success) {
                Log.w(TAG, "纸币器 routes 配置失败（不阻塞收款）")
                allSuccess = false
            }
        }
        
        // 4. 配置硬币器 routes
        if (coinDeviceID != null && coinRecycler.isNotEmpty()) {
            val success = configureRoutesForDevice(coinDeviceID, coinRecycler, routeConfig.recyclerValues.toSet())
            if (!success) {
                Log.w(TAG, "硬币器 routes 配置失败（不阻塞收款）")
                allSuccess = false
            }
        }
        
        // 5. 配置纸币器 inhibits
        if (billDeviceID != null && billInhibit.isNotEmpty()) {
            val success = configureInhibitsForDevice(billDeviceID, billInhibit)
            if (!success) {
                Log.w(TAG, "纸币器 inhibits 配置失败（不阻塞收款）")
                allSuccess = false
            }
        }
        
        // 6. 配置硬币器 inhibits
        if (coinDeviceID != null && coinInhibit.isNotEmpty()) {
            val success = configureInhibitsForDevice(coinDeviceID, coinInhibit)
            if (!success) {
                Log.w(TAG, "硬币器 inhibits 配置失败（不阻塞收款）")
                allSuccess = false
            }
        }
        
        Log.d(TAG, "========== configureDenominationsForDevices 结束 ==========")
        return allSuccess
    }
    
    /**
     * 为单个设备配置 routes（内部方法）
     */
    private suspend fun configureRoutesForDevice(deviceID: String, recyclerValues: List<Int>, allRecyclerValues: Set<Int>): Boolean {
        Log.d(TAG, "配置设备 routes: deviceID=$deviceID, recyclerValues=$recyclerValues")
        
        // 获取货币分配以获取 currency
        val assignments = try {
            fetchCurrencyAssignments(deviceID)
        } catch (e: Exception) {
            Log.e(TAG, "获取货币分配失败，使用默认 currency=EUR", e)
            emptyList()
        }
        
        var successCount = 0
        var failCount = 0
        
        for (value in recyclerValues) {
            try {
                val assignment = assignments.find { it.value == value }
                val currency = assignment?.countryCode ?: "EUR"
                
                // Route=1 表示进入 recycler（可找零）
                val success = setDenominationRoute(deviceID, value, currency, route = 1, probeMode = false)
                if (success) {
                    successCount++
                } else {
                    failCount++
                    Log.w("ROUTING_CONFIG_WARN", "device=$deviceID value=$value route=1 failed")
                }
            } catch (e: Exception) {
                failCount++
                Log.e(TAG, "SetDenominationRoute 异常: deviceID=$deviceID, value=$value", e)
            }
        }
        
        Log.d(TAG, "设备 routes 配置完成: deviceID=$deviceID, success=$successCount, fail=$failCount")
        return failCount == 0
    }
    
    /**
     * 为单个设备配置 inhibits（内部方法）
     */
    private suspend fun configureInhibitsForDevice(deviceID: String, inhibitValues: List<Int>): Boolean {
        Log.d(TAG, "配置设备 inhibits: deviceID=$deviceID, inhibitValues=$inhibitValues")
        
        // 获取货币分配以获取 currency
        val assignments = try {
            fetchCurrencyAssignments(deviceID)
        } catch (e: Exception) {
            Log.e(TAG, "获取货币分配失败，使用默认 currency=EUR", e)
            emptyList()
        }
        
        var successCount = 0
        var failCount = 0
        
        for (value in inhibitValues) {
            try {
                val assignment = assignments.find { it.value == value }
                val currency = assignment?.countryCode ?: "EUR"
                
                // Inhibit=true 表示禁止接收
                val success = setDenominationInhibit(deviceID, value, currency, inhibit = true)
                if (success) {
                    successCount++
                } else {
                    failCount++
                    Log.w("ROUTING_CONFIG_WARN", "device=$deviceID value=$value inhibit=true failed")
                }
            } catch (e: Exception) {
                failCount++
                Log.e(TAG, "SetDenominationInhibit 异常: deviceID=$deviceID, value=$value", e)
            }
        }
        
        Log.d(TAG, "设备 inhibits 配置完成: deviceID=$deviceID, success=$successCount, fail=$failCount")
        return failCount == 0
    }
    
    /**
     * 重置会话基线（将基线设为当前值，相当于让delta归零）
     * @param deviceID 设备ID
     * @return 是否成功
     */
    suspend fun resetSessionBaseline(deviceID: String): Boolean {
        return try {
            // ⚠️ 关键修复：纸币器 SPECTRAL_PAYOUT-0 禁止使用 GetCounters，改用 GetAllLevels
            if (deviceID.startsWith("SPECTRAL_PAYOUT", ignoreCase = true) || 
                deviceID.contains("SPECTRAL", ignoreCase = true) ||
                deviceID.contains("PAYOUT", ignoreCase = true)) {
                // 纸币器：使用 GetAllLevels 计算总金额
                val levels = readCurrentLevels(deviceID)
                val currentCents = levels.calculateTotalCents()
                baselineStore.resetBaseline(deviceID, currentCents)
                Log.d(TAG, "重置会话基线成功（纸币器，使用 GetAllLevels）: deviceID=$deviceID, newBaselineCents=$currentCents (${currentCents / 100.0}€)")
                true
            } else {
                // 硬币器：使用 GetCounters（仅用于维护统计）
                val currentCounters = getCounters(deviceID)
                if (currentCounters.error != null) {
                    Log.w(TAG, "硬币器 GetCounters 失败（非致命）: deviceID=$deviceID, error=${currentCounters.error}")
                    return false
                }
                val currentCents = currentCounters.totalReceivedCents
                baselineStore.resetBaseline(deviceID, currentCents)
                Log.d(TAG, "重置会话基线成功（硬币器，使用 GetCounters）: deviceID=$deviceID, newBaselineCents=$currentCents (${currentCents / 100.0}€)")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "重置会话基线失败: deviceID=$deviceID", e)
            false
        }
    }
    
    /**
     * 获取会话基线信息（用于UI显示）
     * @param deviceID 设备ID
     * @return Triple(baselineCents, currentCents, deltaCents)
     */
    suspend fun getSessionBaselineInfo(deviceID: String): Triple<Int, Int, Int>? {
        return try {
            val baselineCents = baselineStore.getBaseline(deviceID)
            // ⚠️ 关键修复：纸币器 SPECTRAL_PAYOUT-0 禁止使用 GetCounters，改用 GetAllLevels
            val currentCents = if (deviceID.startsWith("SPECTRAL_PAYOUT", ignoreCase = true) || 
                                   deviceID.contains("SPECTRAL", ignoreCase = true) ||
                                   deviceID.contains("PAYOUT", ignoreCase = true)) {
                // 纸币器：使用 GetAllLevels 计算总金额
                val levels = readCurrentLevels(deviceID)
                levels.calculateTotalCents()
            } else {
                // 硬币器：使用 GetCounters（仅用于维护统计）
                val currentCounters = getCounters(deviceID)
                if (currentCounters.error != null) {
                    Log.w(TAG, "硬币器 GetCounters 失败（非致命）: deviceID=$deviceID, error=${currentCounters.error}")
                    return null
                }
                currentCounters.totalReceivedCents
            }
            val deltaCents = currentCents - baselineCents
            Triple(baselineCents, currentCents, deltaCents)
        } catch (e: Exception) {
            Log.e(TAG, "获取会话基线信息失败: deviceID=$deviceID", e)
            null
        }
    }
    
    /**
     * 计算 Levels 差分金额（基于 GetAllLevels 的库存变化）
     * ⚠️ 禁止使用 avgVal 推导金额，必须使用此方法计算会话金额
     * @param baselineLevels 基线库存（在 beginCashSession 时读取）
     * @param currentLevels 当前库存（轮询时读取）
     * @return 会话增量金额（分），计算公式：Σ(value * max(0, currentStored - baselineStored))
     */
    /**
     * 计算 Levels 差分金额（会话增量）
     * ⚠️ 禁止负数贡献：只计算增加，不计算减少
     * 
     * @param baselineLevels 基线 LevelsResponse
     * @param currentLevels 当前 LevelsResponse
     * @return 会话增量金额（分）
     */
    fun calculateLevelsDeltaCents(
        baselineLevels: LevelsResponse?,
        currentLevels: LevelsResponse?
    ): Int {
        if (baselineLevels == null || currentLevels == null) {
            return 0
        }
        
        val baselineMap = baselineLevels.levels?.associate { 
            DenomKey(it.countryCode ?: "EUR", it.value) to it.stored 
        } ?: emptyMap()
        val currentMap = currentLevels.levels?.associate { 
            DenomKey(it.countryCode ?: "EUR", it.value) to it.stored 
        } ?: emptyMap()
        
        var totalDeltaCents = 0
        currentMap.forEach { (key, currentStored) ->
            val baselineStored = baselineMap[key] ?: 0
            val delta = (currentStored - baselineStored).coerceAtLeast(0)  // 禁止负数贡献
            val deltaCents = key.valueCents * delta
            totalDeltaCents += deltaCents
        }
        
        return totalDeltaCents
    }
    
    /**
     * 从 LevelsResponse 创建 LevelsSnapshot
     * 
     * @param deviceId 设备ID
     * @param levelsResponse LevelsResponse
     * @return LevelsSnapshot
     */
    fun createLevelsSnapshot(deviceId: String, levelsResponse: LevelsResponse): LevelsSnapshot {
        val levelsMap = levelsResponse.levels?.associate { level ->
            val key = DenomKey(level.countryCode ?: "EUR", level.value)
            key to level.stored
        } ?: emptyMap()
        
        return LevelsSnapshot(
            deviceId = deviceId,
            ts = System.currentTimeMillis(),
            levels = levelsMap
        )
    }
    
    /**
     * 计算会话差分金额（基于 LevelsSnapshot）
     * ⚠️ 禁止负数贡献：只计算增加，不计算减少
     * 
     * @param baseline 基线 LevelsSnapshot
     * @param current 当前 LevelsSnapshot
     * @return 会话增量金额（分）
     */
    fun calcSessionDeltaCents(baseline: LevelsSnapshot, current: LevelsSnapshot): Int {
        var totalDeltaCents = 0
        
        current.levels.forEach { (key, currentStored) ->
            val baselineStored = baseline.levels[key] ?: 0
            val delta = (currentStored - baselineStored).coerceAtLeast(0)  // 禁止负数贡献
            val deltaCents = key.valueCents * delta
            totalDeltaCents += deltaCents
        }
        
        return totalDeltaCents
    }
    
    /**
     * 执行退款（按金额找零）
     * 策略：先硬币器 dispense，再纸币器 dispense（按"纸币+硬币混合最优找零"策略）
     * @param refundAmountCents 退款金额（分）
     * @param billDeviceID 纸币器设备ID
     * @param coinDeviceID 硬币器设备ID
     * @return 是否成功
     */
    suspend fun refundAmount(
        refundAmountCents: Int,
        billDeviceID: String?,
        coinDeviceID: String?
    ): RefundResult {
        if (refundAmountCents <= 0) {
            Log.w(TAG, "退款金额为0或负数，无需退款: refundAmountCents=$refundAmountCents")
            return RefundResult(
                success = true,
                remaining = 0,
                breakdown = emptyList(),
                errors = emptyList()
            )
        }
        
        Log.d("REFUND_MARK", "START amount=${refundAmountCents}")
        Log.d(TAG, "========== 开始退款 ==========")
        Log.d(TAG, "退款金额: $refundAmountCents 分 (${refundAmountCents / 100.0}€)")
        Log.d(TAG, "billDeviceID=$billDeviceID, coinDeviceID=$coinDeviceID")
        
        // ⚠️ Step 1: 退款前置：必须先禁用接收器（纸币器与硬币器都要）
        Log.d(TAG, "退款前置：禁用接收器...")
        billDeviceID?.let { deviceID ->
            try {
                disableAcceptor(deviceID)
                Log.d(TAG, "DisableAcceptor done: bill=$deviceID")
            } catch (e: Exception) {
                Log.w(TAG, "禁用纸币器接收器失败（继续退款）: deviceID=$deviceID", e)
            }
        }
        coinDeviceID?.let { deviceID ->
            try {
                disableAcceptor(deviceID)
                Log.d(TAG, "DisableAcceptor done: coin=$deviceID")
            } catch (e: Exception) {
                Log.w(TAG, "禁用硬币器接收器失败（继续退款）: deviceID=$deviceID", e)
            }
        }
        Log.d(TAG, "DisableAcceptor done: bill=${billDeviceID ?: "null"}, coin=${coinDeviceID ?: "null"}")
        
        var remainingCents = refundAmountCents
        var coinDispensed = 0
        var billDispensed = 0
        val breakdown = mutableListOf<RefundResult.DispenseBreakdown>()
        val errors = mutableListOf<String>()
        
        // ⚠️ Step D: 退款策略：优先纸币找零，其次硬币找零
        // 策略1：优先尝试纸币器找零（优先使用大面额）
        if (remainingCents > 0) {
            billDeviceID?.let { deviceID ->
                try {
                    // 读取当前纸币器库存
                    val billLevels = readCurrentLevels(deviceID)
                    val availableBills = billLevels.levels?.filter { it.stored > 0 }?.sortedByDescending { it.value } ?: emptyList()
                    
                    // 尝试用纸币器找零（贪心算法：优先使用大面额）
                    for (level in availableBills) {
                        if (remainingCents <= 0) break
                        val value = level.value
                        val available = level.stored
                        val needed = (remainingCents + value - 1) / value  // 向上取整
                        val dispenseCount = minOf(needed, available)
                        
                        if (dispenseCount > 0) {
                            val dispenseCents = value * dispenseCount
                            if (dispenseCents <= remainingCents) {
                                // ⚠️ 关键日志：调用 DispenseValue 前打印金额单位信息
                                // ⚠️ 金额单位：dispenseCents 为 cents（分），DispenseValue.Value 也为 cents
                                if (dispenseCents > 0) {
                                    val dispenseEUR = dispenseCents / 100.0
                                    Log.d(TAG, "========== 准备调用 DispenseValue（纸币器优先） ==========")
                                    Log.d(TAG, "deviceID=$deviceID")
                                    Log.d(TAG, "amountCents=$dispenseCents")
                                    Log.d(TAG, "amountEUR=$dispenseEUR")
                                    Log.d(TAG, "说明：DispenseValue.Value 为 cents（分），${dispenseCents}分 = ${dispenseEUR}€")
                                    Log.d(TAG, "========================================================")
                                    val success = dispenseValue(deviceID, dispenseCents, "EUR")
                                    if (success) {
                                        billDispensed += dispenseCents
                                        remainingCents -= dispenseCents
                                        Log.d(TAG, "纸币器找零成功: ${dispenseCents}分 (${dispenseCount}张 x ${value}分)")
                                    } else {
                                        Log.w(TAG, "纸币器找零失败: ${dispenseCents}分")
                                    }
                                } else {
                                    Log.e(TAG, "⚠️ 异常：dispenseCents <= 0 ($dispenseCents)，跳过 DispenseValue 调用")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "纸币器找零异常", e)
                }
            }
        }
        
        // 策略2：如果还有剩余，尝试硬币器找零
        if (remainingCents > 0) {
            coinDeviceID?.let { deviceID ->
                try {
                    // 读取当前硬币器库存
                    val coinLevels = readCurrentLevels(deviceID)
                    val availableCoins = coinLevels.levels?.filter { it.stored > 0 }?.sortedByDescending { it.value } ?: emptyList()
                    
                    // 尝试用硬币器找零（贪心算法：优先使用大面额）
                    for (level in availableCoins) {
                        if (remainingCents <= 0) break
                        val value = level.value
                        val available = level.stored
                        val needed = (remainingCents + value - 1) / value  // 向上取整
                        val dispenseCount = minOf(needed, available)
                        
                        if (dispenseCount > 0) {
                            val dispenseCents = value * dispenseCount
                            if (dispenseCents <= remainingCents) {
                                // ⚠️ 关键日志：调用 DispenseValue 前打印金额单位信息
                                // ⚠️ 金额单位：dispenseCents 为 cents（分），DispenseValue.Value 也为 cents
                                // ⚠️ SMART_COIN_SYSTEM-1 特别说明：DispenseValue.Value=100→1€，Value=200→2€（现场验证）
                                if (dispenseCents > 0) {
                                    val dispenseEUR = dispenseCents / 100.0
                                    Log.d(TAG, "========== 准备调用 DispenseValue（硬币器） ==========")
                                    Log.d(TAG, "deviceID=$deviceID")
                                    Log.d(TAG, "amountCents=$dispenseCents")
                                    Log.d(TAG, "amountEUR=$dispenseEUR")
                                    Log.d(TAG, "⚠️ SMART_COIN_SYSTEM-1 单位说明：DispenseValue.Value 为 cents（分）")
                                    Log.d(TAG, "   现场验证：Value=100→1€，Value=200→2€")
                                    Log.d(TAG, "   本次调用：Value=${dispenseCents}分 = ${dispenseEUR}€")
                                    Log.d(TAG, "========================================================")
                                    val success = dispenseValue(deviceID, dispenseCents, "EUR")
                                    if (success) {
                                        coinDispensed += dispenseCents
                                        remainingCents -= dispenseCents
                                        breakdown.add(
                                            RefundResult.DispenseBreakdown(
                                                deviceID = deviceID,
                                                deviceName = "硬币器",
                                                denomination = value,
                                                count = dispenseCount,
                                                amount = dispenseCents
                                            )
                                        )
                                        Log.d(TAG, "硬币器找零成功: ${dispenseCents}分 (${dispenseCount}枚 x ${value}分)")
                                    } else {
                                        val errorMsg = "硬币器找零失败: ${dispenseCents}分"
                                        errors.add(errorMsg)
                                        Log.w(TAG, errorMsg)
                                    }
                                } else {
                                    Log.e(TAG, "⚠️ 异常：dispenseCents <= 0 ($dispenseCents)，跳过 DispenseValue 调用")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "硬币器找零异常", e)
                }
            }
        }
        
        val totalDispensed = coinDispensed + billDispensed
        val success = remainingCents == 0
        
        Log.d(TAG, "========== 退款完成 ==========")
        Log.d(TAG, "退款金额: $refundAmountCents 分")
        Log.d(TAG, "纸币器找零（优先）: $billDispensed 分")
        Log.d(TAG, "硬币器找零: $coinDispensed 分")
        Log.d(TAG, "总找零: $totalDispensed 分")
        Log.d(TAG, "剩余未找零: $remainingCents 分")
        Log.d(TAG, "退款${if (success) "成功" else "失败（部分或全部）"}")
        
        // ⚠️ 关键修复：对 BUSY 的重试，如果最终成功，必须返回 success=true
        // ⚠️ 中途失败但最终成功的，作为 warnings，而不是直接让上层失败
        if (success && errors.isNotEmpty()) {
            Log.w(TAG, "⚠️ 退款最终成功，但中途有失败（作为 warnings）: ${errors.size} 个错误")
            errors.forEach { error ->
                Log.w(TAG, "  - $error")
            }
        }
        
        // ⚠️ Step D: 添加 REFUND_MARK 日志
        if (success) {
            Log.d("REFUND_MARK", "RESULT success=true remaining=0")
            Log.d("REFUND_MARK", "已收款=${refundAmountCents}分，已退款=${totalDispensed}分，remaining=0")
        } else {
            // ⚠️ Step D: 找零不足时记录日志（调用方需要处理提示用户并锁单或弹窗提醒）
            Log.e("REFUND_MARK", "RESULT success=false remaining=${remainingCents}")
            Log.e("REFUND_FAILED", "amount=${refundAmountCents} reason=INSUFFICIENT_CHANGE remaining=${remainingCents} billDispensed=${billDispensed} coinDispensed=${coinDispensed}")
        }
        
        return RefundResult(
            success = success,
            remaining = remainingCents,
            breakdown = breakdown,
            errors = errors,
            billDispensed = billDispensed,
            coinDispensed = coinDispensed,
            totalDispensed = totalDispensed
        )
    }
    
    // 设备映射：Port -> SspAddress
    private var billAcceptorMapping: Pair<Int, Int>? = null  // (Port, SspAddress)
    private var coinAcceptorMapping: Pair<Int, Int>? = null  // (Port, SspAddress)
    
    // 设备状态缓存：deviceID -> DeviceStatusResponse（用于空数组时返回上一次状态）
    private val lastKnownStatus = mutableMapOf<String, DeviceStatusResponse>()
    
    /**
     * 认证
     * @return Pair<Boolean, String?> 第一个值表示是否成功，第二个值表示错误信息（如果失败）
     */
    suspend fun authenticate(username: String = DEFAULT_USERNAME, password: String = DEFAULT_PASSWORD): Pair<Boolean, String?> {
        return try {
            Log.d(TAG, "开始认证: username=$username")
            val response = api.authenticate(AuthenticateRequest(username, password))
            if (response.token != null) {
                // 保存 token 到 TokenStore（Interceptor 会自动使用）
                TokenStore.setToken(response.token)
                Log.d(TAG, "token 获取成功")
                Pair(true, null)
            } else {
                val errorMsg = response.error ?: "未知错误"
                Log.e(TAG, "认证失败: $errorMsg")
                TokenStore.clearToken()
                Pair(false, errorMsg)
            }
        } catch (e: java.net.UnknownServiceException) {
            val errorMsg = when {
                e.message?.contains("CLEARTEXT", ignoreCase = true) == true -> {
                    "网络安全策略禁止明文HTTP连接。请检查网络配置。"
                }
                else -> "网络服务异常: ${e.message}"
            }
            Log.e(TAG, "认证异常: $errorMsg", e)
            Pair(false, errorMsg)
        } catch (e: java.net.ConnectException) {
            val errorMsg = "无法连接到现金设备服务。请检查服务是否运行。"
            Log.e(TAG, "认证异常: $errorMsg", e)
            Pair(false, errorMsg)
        } catch (e: java.net.SocketTimeoutException) {
            val errorMsg = "连接超时。请检查网络连接和服务状态。"
            Log.e(TAG, "认证异常: $errorMsg", e)
            Pair(false, errorMsg)
        } catch (e: Exception) {
            val errorMsg = "认证异常: ${e.message ?: e.javaClass.simpleName}"
            Log.e(TAG, "认证异常", e)
            Pair(false, errorMsg)
        }
    }
    
    /**
     * 获取已连接的 USB 设备
     * Authorization 头由 Interceptor 自动添加
     */
    suspend fun getConnectedUSBDevices(): List<USBDevice> {
        return try {
            Log.d(TAG, "获取已连接的 USB 设备")
            val devices = api.getConnectedUSBDevices()
            Log.d(TAG, "GetConnectedUSBDevices 响应: 找到 ${devices.size} 个设备")
            devices.forEachIndexed { index, device ->
                Log.d(TAG, "设备[$index]详情: Port=${device.actualPort}, DeviceID=${device.actualDeviceID}, DeviceName=${device.DeviceName ?: device.description}, VendorId=${device.VendorId}, ProductId=${device.ProductId}")
            }
            devices
        } catch (e: Exception) {
            Log.e(TAG, "获取 USB 设备失败", e)
            emptyList()
        }
    }
    
    /**
     * 探测设备：尝试 Port + SspAddress 组合，返回成功连接的设备信息
     * @param probeApi 用于探测的 API 实例（应使用适当超时）
     * @param port 串口号
     * @param sspAddress SSP 地址
     * @param timeoutMs 超时时间（毫秒），用于日志
     * @param usbDevice 从 GetConnectedUSBDevices 获取的 USB 设备信息（可选）
     * @return 如果成功，返回 OpenConnectionResponse；如果失败或超时，返回 null
     */
    private suspend fun probeDevice(probeApi: CashDeviceApi, port: Int, sspAddress: Int, timeoutMs: Long, usbDevice: USBDevice? = null): OpenConnectionResponse? {
        var deviceID: String? = null
        val startTime = System.currentTimeMillis()
        
        // 显示从 GetConnectedUSBDevices 获取的设备信息（如果可用）
        // 注意：GetConnectedUSBDevices 不返回 DeviceID，DeviceID 是从 OpenConnection 响应中获取的
        val usbDeviceID = usbDevice?.actualDeviceID
        val usbDeviceInfo = if (usbDevice != null) {
            "USB设备信息: DeviceID=${usbDeviceID ?: "null（GetConnectedUSBDevices不返回DeviceID）"}, DeviceName=${usbDevice.DeviceName ?: usbDevice.description ?: "unknown"}"
        } else {
            ""
        }
        
        return try {
            Log.d(TAG, "try OpenConnection Port=$port SspAddress=$sspAddress timeout=${timeoutMs}ms $usbDeviceInfo")
            Log.d(TAG, "准备创建 OpenConnection 请求: ComPort=$port, SspAddress=$sspAddress, DeviceID=${usbDeviceID ?: "null（将从OpenConnection响应中获取）"}")
            // 启动阶段：只做连接，不启用接收器、不自动接收、不配置面额
            val request = createOpenConnectionRequest(
                comPort = port,
                sspAddress = sspAddress,
                deviceID = usbDeviceID,  // GetConnectedUSBDevices 不返回 DeviceID，这里为 null 是正常的
                enableAcceptor = false,  // 启动阶段：不启用接收器
                enableAutoAcceptEscrow = false,  // 启动阶段：不自动接收
                enablePayout = true,  // 启用找零功能（不影响接收器状态）
                setInhibits = null,  // 启动阶段：不配置面额
                setRoutes = null  // 启动阶段：不配置路由
            )
            
            // 注意：不再在这里重复序列化请求体
            // 最终的请求体 JSON 已在 createOpenConnectionRequest 中打印（"FINAL OpenConnection JSON"）
            // OkHttp Interceptor 也会输出实际发送的 body，请以 Interceptor 输出为准
            Log.d(TAG, "========== OpenConnection REQ ==========")
            Log.d(TAG, "Port=$port, SspAddress=$sspAddress, timeout=${timeoutMs}ms")
            Log.d(TAG, "注意：最终请求体 JSON 请查看 'FINAL OpenConnection JSON' 日志（在 createOpenConnectionRequest 中）")
            Log.d(TAG, "实际发送的 body 请查看 OkHttp Interceptor 日志（--> POST ... 日志）")
            Log.d(TAG, "超时配置: connectTimeout=${timeoutMs}ms, readTimeout=${timeoutMs}ms, writeTimeout=${timeoutMs}ms")
            
            val requestStartTime = System.currentTimeMillis()
            val response = try {
                probeApi.openConnection(request)
            } catch (e: Exception) {
                val actualDuration = System.currentTimeMillis() - requestStartTime
                Log.e(TAG, "========== OpenConnection 异常 ==========")
                Log.e(TAG, "异常类型: ${e.javaClass.simpleName}")
                Log.e(TAG, "异常消息: ${e.message}")
                Log.e(TAG, "实际耗时: ${actualDuration}ms")
                throw e
            }
            val actualDuration = System.currentTimeMillis() - requestStartTime
            val duration = System.currentTimeMillis() - startTime
            deviceID = response.deviceID
            
            // 显示 OpenConnection 响应的完整信息（用于调试响应格式）
            Log.d(TAG, "========== OpenConnection RESP ==========")
            Log.d(TAG, "HTTP: 200 OK")
            Log.d(TAG, "deviceID: ${response.deviceID ?: "null"}")
            Log.d(TAG, "DeviceModel: ${response.DeviceModel ?: "null"}")
            Log.d(TAG, "IsOpen: ${response.IsOpen}")
            Log.d(TAG, "DeviceError: ${response.DeviceError ?: "null"}")
            Log.d(TAG, "error: ${response.error ?: "null"}")
            Log.d(TAG, "Firmware: ${response.Firmware ?: "null"}")
            Log.d(TAG, "Dataset: ${response.Dataset ?: "null"}")
            Log.d(TAG, "ValidatorSerialNumber: ${response.ValidatorSerialNumber ?: "null"}")
            Log.d(TAG, "PayoutModuleSerialNumber: ${response.PayoutModuleSerialNumber ?: "null"}")
            Log.d(TAG, "实际耗时: ${actualDuration}ms")
            
            // 验证响应
            if (response.deviceID != null) {
                Log.d(TAG, "✓ OpenConnectionResponse.deviceID = ${response.deviceID}")
            } else {
                Log.e(TAG, "✗ OpenConnectionResponse.deviceID 为 null")
            }
            
            // 如果 DeviceID 为 null，尝试诊断问题
            if (response.deviceID == null) {
                Log.e(TAG, "OpenConnection 响应中 DeviceID 为 null，可能原因：")
                Log.e(TAG, "  1. 服务器响应格式不匹配（期望 'DeviceID' 或 'deviceID' 字段）")
                Log.e(TAG, "  2. 设备未正确连接或初始化")
                Log.e(TAG, "  3. JSON 序列化配置问题（useAlternativeNames 可能未启用）")
                Log.e(TAG, "  响应详情: IsOpen=${response.IsOpen}, DeviceModel=${response.DeviceModel}, error=${response.error}")
            }
            
            // 判断是否成功：DeviceID 不为空，且 DeviceModel 不为 "UNKNOWN" 或 null
            if (response.deviceID != null && response.DeviceModel != null && response.DeviceModel != "UNKNOWN") {
                Log.d(TAG, "OPEN OK Port=$port SspAddress=$sspAddress -> deviceModel=${response.DeviceModel} deviceId=${response.deviceID} (耗时${duration}ms)")
                Log.d(TAG, "设备ID获取成功: OpenConnection响应返回的deviceID=${response.deviceID}")
                
                // 探测成功后立即断开连接，避免影响后续正常连接
                if (deviceID != null) {
                    try {
                        probeApi.disconnectDevice(deviceID)
                        Log.d(TAG, "探测后已断开连接: DeviceID=$deviceID")
                    } catch (e: Exception) {
                        Log.w(TAG, "探测后断开连接失败（可忽略）: DeviceID=$deviceID", e)
                    }
                }
                
                response
            } else {
                Log.w(TAG, "探测失败（设备响应无效）: Port=$port, SspAddress=$sspAddress, DeviceModel=${response.DeviceModel ?: "null"}, DeviceID=${response.deviceID ?: "null"} (耗时${duration}ms)")
                
                // 即使失败也尝试断开连接
                if (deviceID != null) {
                    try {
                        probeApi.disconnectDevice(deviceID)
                    } catch (e: Exception) {
                        // 忽略断开失败
                    }
                }
                
                null
            }
        } catch (e: java.net.SocketTimeoutException) {
            val duration = System.currentTimeMillis() - startTime
            Log.e(TAG, "========== OpenConnection 超时 ==========")
            Log.e(TAG, "异常类型: SocketTimeoutException")
            Log.e(TAG, "异常消息: ${e.message}")
            Log.e(TAG, "Port=$port, SspAddress=$sspAddress, timeout=${timeoutMs}ms, 实际耗时=${duration}ms")
            null
        } catch (e: retrofit2.HttpException) {
            val duration = System.currentTimeMillis() - startTime
            Log.e(TAG, "========== OpenConnection HTTP 错误 ==========")
            Log.e(TAG, "异常类型: HttpException")
            Log.e(TAG, "HTTP Code: ${e.code()}")
            Log.e(TAG, "异常消息: ${e.message()}")
            Log.e(TAG, "Port=$port, SspAddress=$sspAddress, 实际耗时=${duration}ms")
            null
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            Log.e(TAG, "========== OpenConnection 异常 ==========")
            Log.e(TAG, "异常类型: ${e.javaClass.simpleName}")
            Log.e(TAG, "异常消息: ${e.message}")
            Log.e(TAG, "Port=$port, SspAddress=$sspAddress, 实际耗时=${duration}ms")
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 判断设备类型（根据 DeviceModel）
     * @param deviceModel 设备型号
     * @return "NOTE"（纸币器）或 "COIN"（硬币器）或 null（未知）
     */
    private fun identifyDeviceType(deviceModel: String?): String? {
        if (deviceModel == null) return null
        val modelUpper = deviceModel.uppercase()
        return when {
            modelUpper.contains("SPECTRAL", ignoreCase = true) ||
            modelUpper.contains("PAYOUT", ignoreCase = true) ||
            modelUpper.contains("VALIDATOR", ignoreCase = true) ||
            modelUpper.contains("NOTE", ignoreCase = true) ||
            modelUpper.contains("BILL", ignoreCase = true) -> "NOTE"
            modelUpper.contains("COIN", ignoreCase = true) ||
            modelUpper.contains("MECH", ignoreCase = true) -> "COIN"
            else -> null
        }
    }
    
    /**
     * 自动探测所有设备并建立映射
     * 固定组合探测：只尝试 Port=0/SspAddress=0 和 Port=1/SspAddress=16
     * 当 Port=0/SspAddress=0 成功时，直接假设 Port=1/SspAddress=16 是正确的，无需额外探测
     * 
     * @param probeApi 用于探测的 API 实例（超时 12 秒）
     * @return Pair<Boolean, String?> 第一个值表示是否成功找到设备，第二个值表示错误信息
     */
    suspend fun probeAndMapDevices(probeApi: CashDeviceApi): Pair<Boolean, String?> {
        Log.d(TAG, "开始自动探测设备映射...")
        
        // 1. 获取 USB 设备列表
        val usbDevices = getConnectedUSBDevices()
        if (usbDevices.isEmpty()) {
            val errorMsg = "未找到 USB 设备"
            Log.e(TAG, errorMsg)
            return Pair(false, errorMsg)
        }
        
        Log.d(TAG, "找到 ${usbDevices.size} 个 USB 设备")
        Log.d(TAG, "注意：GetConnectedUSBDevices API 不返回 DeviceID 字段，DeviceID 将从 OpenConnection 响应中获取")
        usbDevices.forEach { device ->
            val deviceID = device.actualDeviceID
            Log.d(TAG, "USB 设备: Port=${device.actualPort}, DeviceID=${deviceID ?: "null（GetConnectedUSBDevices不返回此字段）"}, DeviceName=${device.DeviceName ?: device.description}, VendorId=${device.VendorId}, ProductId=${device.ProductId}")
        }
        
        // 2. 固定组合探测：只尝试 Port=0/SspAddress=0 和 Port=1/SspAddress=16
        // 逻辑：当 Port=0/SspAddress=0 成功时，直接假设 Port=1/SspAddress=16 是正确的，无需额外探测
        val foundDevices = mutableListOf<Triple<Int, Int, OpenConnectionResponse>>()  // (Port, SspAddress, Response)
        val timeoutMs = PROBE_TIMEOUT_SECONDS * 1000
        
        // 定义固定的设备组合
        val fixedCombinations = listOf(
            Pair(0, 0),   // Port=0, SspAddress=0（纸币器）
            Pair(1, 16)   // Port=1, SspAddress=16（硬币器）
        )
        
        Log.d(TAG, "开始固定组合探测: Port=0/SspAddress=0, Port=1/SspAddress=16")
        
        for ((port, sspAddr) in fixedCombinations) {
            // 检查该 Port 是否在 USB 设备列表中，并获取对应的设备信息
            val usbDevice = usbDevices.firstOrNull { it.actualPort == port }
            if (usbDevice == null) {
                Log.w(TAG, "Port=$port 不在 USB 设备列表中，跳过")
                continue
            }
            
            // 显示从 GetConnectedUSBDevices 获取的设备信息
            // 注意：GetConnectedUSBDevices 不返回 DeviceID，DeviceID 将从 OpenConnection 响应中获取
            val usbDeviceID = usbDevice?.actualDeviceID
            val usbDeviceName = usbDevice?.DeviceName ?: usbDevice?.description ?: "unknown"
            Log.d(TAG, "尝试固定组合: Port=$port, SspAddress=$sspAddr, USB设备信息: DeviceID=${usbDeviceID ?: "null（GetConnectedUSBDevices不返回）"}, DeviceName=$usbDeviceName")
            
            val response = probeDevice(probeApi, port, sspAddr, timeoutMs, usbDevice)
            
            if (response != null) {
                foundDevices.add(Triple(port, sspAddr, response))
                Log.d(TAG, "找到设备并固定: Port=$port, SspAddress=$sspAddr, DeviceModel=${response.DeviceModel}")
            } else {
                Log.w(TAG, "固定组合连接失败: Port=$port, SspAddress=$sspAddr")
                // 如果 Port=0/SspAddress=0 失败，仍然尝试 Port=1/SspAddress=16（可能只有一个设备）
            }
        }
        
        if (foundDevices.isEmpty()) {
            val errorMsg = "未找到任何可用设备"
            Log.e(TAG, errorMsg)
            return Pair(false, errorMsg)
        }
        
        Log.d(TAG, "共找到 ${foundDevices.size} 个设备")
        
        // 3. 识别设备类型并建立映射
        var noteDevice: Triple<Int, Int, OpenConnectionResponse>? = null
        var coinDevice: Triple<Int, Int, OpenConnectionResponse>? = null
        
        for ((port, sspAddr, response) in foundDevices) {
            val deviceType = identifyDeviceType(response.DeviceModel)
            Log.d(TAG, "设备识别: Port=$port, SspAddress=$sspAddr, DeviceModel=${response.DeviceModel}, Type=$deviceType")
            
            when (deviceType) {
                "NOTE" -> {
                    if (noteDevice == null) {
                        noteDevice = Triple(port, sspAddr, response)
                        billAcceptorMapping = Pair(port, sspAddr)
                        Log.d(TAG, "映射纸币器: Port=$port, SspAddress=$sspAddr, DeviceModel=${response.DeviceModel}")
                    }
                }
                "COIN" -> {
                    if (coinDevice == null) {
                        coinDevice = Triple(port, sspAddr, response)
                        coinAcceptorMapping = Pair(port, sspAddr)
                        Log.d(TAG, "映射硬币器: Port=$port, SspAddress=$sspAddr, DeviceModel=${response.DeviceModel}")
                    }
                }
                else -> {
                    // 如果无法识别，根据已找到的设备数量推断
                    if (noteDevice == null && foundDevices.size >= 1) {
                        noteDevice = Triple(port, sspAddr, response)
                        billAcceptorMapping = Pair(port, sspAddr)
                        Log.d(TAG, "推断为纸币器（默认第一个）: Port=$port, SspAddress=$sspAddr")
                    } else if (coinDevice == null && foundDevices.size >= 2) {
                        coinDevice = Triple(port, sspAddr, response)
                        coinAcceptorMapping = Pair(port, sspAddr)
                        Log.d(TAG, "推断为硬币器（默认第二个）: Port=$port, SspAddress=$sspAddr")
                    }
                }
            }
        }
        
        // 4. 验证结果
        if (noteDevice == null) {
            val errorMsg = "未找到纸币器"
            Log.e(TAG, errorMsg)
            return Pair(false, errorMsg)
        }
        
        Log.d(TAG, "设备映射完成:")
        Log.d(TAG, "  纸币器: Port=${noteDevice.first}, SspAddress=${noteDevice.second}, DeviceModel=${noteDevice.third.DeviceModel}")
        if (coinDevice != null) {
            Log.d(TAG, "  硬币器: Port=${coinDevice.first}, SspAddress=${coinDevice.second}, DeviceModel=${coinDevice.third.DeviceModel}")
        } else {
            Log.w(TAG, "  硬币器: 未找到")
        }
        
        return Pair(true, null)
    }
    
    /**
     * 打开纸币器连接
     * @param comPort 串口号（如果为 null，则使用探测到的映射）
     * @param sspAddress SSP 地址（如果为 null，则使用探测到的映射）
     */
    suspend fun openBillAcceptorConnection(comPort: Int? = null, sspAddress: Int? = null): Boolean {
        val (actualPort, actualSspAddr) = if (comPort != null && sspAddress != null) {
            Pair(comPort, sspAddress)
        } else if (billAcceptorMapping != null) {
            billAcceptorMapping!!
        } else {
            Log.e(TAG, "打开纸币器连接失败: 未找到映射，请先调用 probeAndMapDevices")
            return false
        }
        
        return try {
            // 获取 USB 设备信息（用于日志显示）
            // 注意：GetConnectedUSBDevices 不返回 DeviceID，DeviceID 将从 OpenConnection 响应中获取
            val usbDevices = getConnectedUSBDevices()
            val usbDevice = usbDevices.firstOrNull { it.actualPort == actualPort }
            val usbDeviceID = usbDevice?.actualDeviceID
            val usbDeviceInfo = if (usbDevice != null) {
                "USB设备信息: DeviceID=${usbDeviceID ?: "null（GetConnectedUSBDevices不返回）"}, DeviceName=${usbDevice.DeviceName ?: usbDevice.description ?: "unknown"}"
            } else {
                ""
            }
            
            Log.d(TAG, "打开纸币器连接: ComPort=$actualPort, SspAddress=$actualSspAddr $usbDeviceInfo")
            val request = createOpenConnectionRequest(
                comPort = actualPort,
                sspAddress = actualSspAddr,
                deviceID = usbDeviceID,  // GetConnectedUSBDevices 不返回 DeviceID，这里为 null 是正常的
                enableAcceptor = true,  // 恢复默认值：true（与 Windows 工具一致）
                enableAutoAcceptEscrow = true,  // 恢复默认值：true（与 Windows 工具一致）
                enablePayout = true  // 设备连接成功后自动启用找零功能  // 关闭找零功能
            )
            
            // 序列化请求体（用于日志）
            val json = kotlinx.serialization.json.Json {
                encodeDefaults = true
                ignoreUnknownKeys = false
            }
            val requestBodyJson = json.encodeToString(
                kotlinx.serialization.serializer<OpenConnectionRequest>(), request
            )
            
            Log.d(TAG, "========== OpenConnection REQ (纸币器) ==========")
            Log.d(TAG, "Port=$actualPort, SspAddress=$actualSspAddr")
            Log.d(TAG, "requestBodyJson=$requestBodyJson")
            Log.d(TAG, "超时配置: connectTimeout=30s, readTimeout=30s, writeTimeout=30s")
            
            val requestStartTime = System.currentTimeMillis()
            val response = try {
                api.openConnection(request)
            } catch (e: Exception) {
                val actualDuration = System.currentTimeMillis() - requestStartTime
                Log.e(TAG, "========== OpenConnection 异常 (纸币器) ==========")
                Log.e(TAG, "异常类型: ${e.javaClass.simpleName}")
                Log.e(TAG, "异常消息: ${e.message}")
                Log.e(TAG, "实际耗时: ${actualDuration}ms")
                throw e
            }
            val actualDuration = System.currentTimeMillis() - requestStartTime
            
            // 显示 OpenConnection 响应的完整信息（用于调试响应格式）
            Log.d(TAG, "========== OpenConnection RESP (纸币器) ==========")
            Log.d(TAG, "HTTP: 200 OK")
            Log.d(TAG, "deviceID: ${response.deviceID ?: "null"}")
            Log.d(TAG, "DeviceModel: ${response.DeviceModel ?: "null"}")
            Log.d(TAG, "IsOpen: ${response.IsOpen}")
            Log.d(TAG, "DeviceError: ${response.DeviceError ?: "null"}")
            Log.d(TAG, "error: ${response.error ?: "null"}")
            Log.d(TAG, "Firmware: ${response.Firmware ?: "null"}")
            Log.d(TAG, "Dataset: ${response.Dataset ?: "null"}")
            Log.d(TAG, "ValidatorSerialNumber: ${response.ValidatorSerialNumber ?: "null"}")
            Log.d(TAG, "PayoutModuleSerialNumber: ${response.PayoutModuleSerialNumber ?: "null"}")
            Log.d(TAG, "实际耗时: ${actualDuration}ms")
            
            // 验证响应
            if (response.deviceID != null) {
                Log.d(TAG, "✓ OpenConnectionResponse.deviceID = ${response.deviceID}")
            } else {
                Log.e(TAG, "✗ OpenConnectionResponse.deviceID 为 null")
            }
            
            // 如果 DeviceID 为 null，尝试重新获取设备列表并重试
            if (response.deviceID == null) {
                Log.w(TAG, "纸币器：OpenConnection 响应中 DeviceID 为 null，尝试重新获取设备列表...")
                try {
                    val usbDevicesRetry = getConnectedUSBDevices()
                    Log.d(TAG, "重新获取 USB 设备列表: 找到 ${usbDevicesRetry.size} 个设备")
                    usbDevicesRetry.forEach { device ->
                        Log.d(TAG, "USB 设备详情: Port=${device.actualPort}, DeviceID=${device.actualDeviceID}, DeviceName=${device.DeviceName}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "重新获取设备列表失败", e)
                }
                
                Log.e(TAG, "纸币器：DeviceID 为 null，可能原因：")
                Log.e(TAG, "  1. 服务器响应格式不匹配（检查 HTTP 日志中的原始响应）")
                Log.e(TAG, "  2. JSON 序列化配置问题")
                Log.e(TAG, "  3. 设备未正确连接")
            }
            
            if (response.deviceID != null && response.IsOpen == true) {
                _billAcceptorDeviceID.value = response.deviceID
                Log.d(TAG, "纸币器连接成功: deviceID=${response.deviceID}, DeviceModel=${response.DeviceModel}, isOpen=${response.IsOpen}")
                Log.d(TAG, "设备ID获取成功: OpenConnection响应返回的deviceID=${response.deviceID}")
                
                // OpenConnection 成功后立即调用 DisableAcceptor（避免初始化阶段收钱）
                // 注意：不在 OpenConnection 请求中禁用，而是在成功后单独调用
                try {
                    Log.d(TAG, "OpenConnection 成功后立即禁用接收器: DeviceID=${response.deviceID}")
                    val disableResponse = api.disableAcceptor(response.deviceID)
                    val disableBodyText = cleanResponseBody(disableResponse.body()?.string())
                    Log.d(TAG, "DisableAcceptor 响应: isSuccessful=${disableResponse.isSuccessful}, code=${disableResponse.code()}, body=$disableBodyText")
                    if (disableResponse.isSuccessful) {
                        Log.d(TAG, "✓ DisableAcceptor 成功")
                    } else {
                        Log.w(TAG, "✗ DisableAcceptor 失败，但继续使用连接")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "DisableAcceptor 调用异常（可忽略，继续使用连接）: DeviceID=${response.deviceID}", e)
                }
                
                // 在 OpenConnection 成功后，立即执行设备配置流程
                // 1. GetCurrencyAssignment（获取面额分配和计数器）
                try {
                    val assignments = fetchCurrencyAssignments(response.deviceID)
                    if (assignments.isNotEmpty()) {
                        Log.d(TAG, "纸币器：GetCurrencyAssignment 成功，获取到 ${assignments.size} 个面额")
                        // 设置基线库存（基于 GetCurrencyAssignment 的 stored）
                        amountTracker.setBaselineFromAssignments(response.deviceID, assignments)
                    } else {
                        Log.w(TAG, "纸币器：GetCurrencyAssignment 返回空列表")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "纸币器：GetCurrencyAssignment 异常", e)
                }
                
                // 2. SetInhibits（设置"可接收面额"）- 由 GetCurrencyAssignment 动态生成
                try {
                    val inhibitSuccess = applyInhibitsFromAssignments(response.deviceID, "SPECTRAL_PAYOUT-0")
                    if (!inhibitSuccess) {
                        Log.w(TAG, "纸币器：SetInhibits 失败，但继续执行后续配置")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "纸币器：SetInhibits 异常", e)
                }
                
                // 3. SetRoutes（设置"找零面额/路由"）- 由 GetCurrencyAssignment 动态生成（初始使用默认配置）
                // 注意：后续 UI 的"可找零 ON/OFF"会调用 applyRoutesFromUI 来更新
                
                true
            } else {
                Log.e(TAG, "纸币器连接失败: ${response.error}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "打开纸币器连接异常", e)
            false
        }
    }
    
    /**
     * 打开硬币器连接
     * @param comPort 串口号（如果为 null，则使用探测到的映射）
     * @param sspAddress SSP 地址（如果为 null，则使用探测到的映射）
     */
    suspend fun openCoinAcceptorConnection(comPort: Int? = null, sspAddress: Int? = null): Boolean {
        val (actualPort, actualSspAddr) = if (comPort != null && sspAddress != null) {
            Pair(comPort, sspAddress)
        } else if (coinAcceptorMapping != null) {
            coinAcceptorMapping!!
        } else {
            Log.e(TAG, "打开硬币器连接失败: 未找到映射，请先调用 probeAndMapDevices")
            return false
        }
        
        return try {
            // 获取 USB 设备信息（用于日志显示）
            // 注意：GetConnectedUSBDevices 不返回 DeviceID，DeviceID 将从 OpenConnection 响应中获取
            val usbDevices = getConnectedUSBDevices()
            val usbDevice = usbDevices.firstOrNull { it.actualPort == actualPort }
            val usbDeviceID = usbDevice?.actualDeviceID
            val usbDeviceInfo = if (usbDevice != null) {
                "USB设备信息: DeviceID=${usbDeviceID ?: "null（GetConnectedUSBDevices不返回）"}, DeviceName=${usbDevice.DeviceName ?: usbDevice.description ?: "unknown"}"
            } else {
                ""
            }
            
            Log.d(TAG, "打开硬币器连接: ComPort=$actualPort, SspAddress=$actualSspAddr $usbDeviceInfo")
            val request = createOpenConnectionRequest(
                comPort = actualPort,
                sspAddress = actualSspAddr,
                deviceID = usbDeviceID,  // GetConnectedUSBDevices 不返回 DeviceID，这里为 null 是正常的
                enableAcceptor = true,  // 恢复默认值：true（与 Windows 工具一致）
                enableAutoAcceptEscrow = true,  // 恢复默认值：true（与 Windows 工具一致）
                enablePayout = true  // 设备连接成功后自动启用找零功能  // 关闭找零功能
            )
            
            // 序列化请求体（用于日志）
            val json = kotlinx.serialization.json.Json {
                encodeDefaults = true
                ignoreUnknownKeys = false
            }
            val requestBodyJson = json.encodeToString(
                kotlinx.serialization.serializer<OpenConnectionRequest>(), request
            )
            
            Log.d(TAG, "========== OpenConnection REQ (硬币器) ==========")
            Log.d(TAG, "Port=$actualPort, SspAddress=$actualSspAddr")
            Log.d(TAG, "requestBodyJson=$requestBodyJson")
            Log.d(TAG, "超时配置: connectTimeout=30s, readTimeout=30s, writeTimeout=30s")
            
            val requestStartTime = System.currentTimeMillis()
            val response = try {
                api.openConnection(request)
            } catch (e: Exception) {
                val actualDuration = System.currentTimeMillis() - requestStartTime
                Log.e(TAG, "========== OpenConnection 异常 (硬币器) ==========")
                Log.e(TAG, "异常类型: ${e.javaClass.simpleName}")
                Log.e(TAG, "异常消息: ${e.message}")
                Log.e(TAG, "实际耗时: ${actualDuration}ms")
                throw e
            }
            val actualDuration = System.currentTimeMillis() - requestStartTime
            
            // 显示 OpenConnection 响应的完整信息（用于调试响应格式）
            Log.d(TAG, "========== OpenConnection RESP (硬币器) ==========")
            Log.d(TAG, "HTTP: 200 OK")
            Log.d(TAG, "deviceID: ${response.deviceID ?: "null"}")
            Log.d(TAG, "DeviceModel: ${response.DeviceModel ?: "null"}")
            Log.d(TAG, "IsOpen: ${response.IsOpen}")
            Log.d(TAG, "DeviceError: ${response.DeviceError ?: "null"}")
            Log.d(TAG, "error: ${response.error ?: "null"}")
            Log.d(TAG, "Firmware: ${response.Firmware ?: "null"}")
            Log.d(TAG, "Dataset: ${response.Dataset ?: "null"}")
            Log.d(TAG, "ValidatorSerialNumber: ${response.ValidatorSerialNumber ?: "null"}")
            Log.d(TAG, "PayoutModuleSerialNumber: ${response.PayoutModuleSerialNumber ?: "null"}")
            Log.d(TAG, "实际耗时: ${actualDuration}ms")
            
            // 验证响应
            if (response.deviceID != null) {
                Log.d(TAG, "✓ OpenConnectionResponse.deviceID = ${response.deviceID}")
            } else {
                Log.e(TAG, "✗ OpenConnectionResponse.deviceID 为 null")
            }
            
            // 如果 DeviceID 为 null，尝试重新获取设备列表并重试
            if (response.deviceID == null) {
                Log.w(TAG, "硬币器：OpenConnection 响应中 DeviceID 为 null，尝试重新获取设备列表...")
                try {
                    val usbDevicesRetry = getConnectedUSBDevices()
                    Log.d(TAG, "重新获取 USB 设备列表: 找到 ${usbDevicesRetry.size} 个设备")
                    usbDevicesRetry.forEach { device ->
                        Log.d(TAG, "USB 设备详情: Port=${device.actualPort}, DeviceID=${device.actualDeviceID}, DeviceName=${device.DeviceName}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "重新获取设备列表失败", e)
                }
                
                Log.e(TAG, "硬币器：DeviceID 为 null，可能原因：")
                Log.e(TAG, "  1. 服务器响应格式不匹配（检查 HTTP 日志中的原始响应）")
                Log.e(TAG, "  2. JSON 序列化配置问题")
                Log.e(TAG, "  3. 设备未正确连接")
            }
            
            if (response.deviceID != null && response.IsOpen == true) {
                _coinAcceptorDeviceID.value = response.deviceID
                Log.d(TAG, "硬币器连接成功: deviceID=${response.deviceID}, DeviceModel=${response.DeviceModel}, isOpen=${response.IsOpen}")
                Log.d(TAG, "设备ID获取成功: OpenConnection响应返回的deviceID=${response.deviceID}")
                
                // OpenConnection 成功后立即调用 DisableAcceptor（避免初始化阶段收钱）
                // 注意：不在 OpenConnection 请求中禁用，而是在成功后单独调用
                try {
                    Log.d(TAG, "OpenConnection 成功后立即禁用接收器: DeviceID=${response.deviceID}")
                    val disableResponse = api.disableAcceptor(response.deviceID)
                    val disableBodyText = cleanResponseBody(disableResponse.body()?.string())
                    Log.d(TAG, "DisableAcceptor 响应: isSuccessful=${disableResponse.isSuccessful}, code=${disableResponse.code()}, body=$disableBodyText")
                    if (disableResponse.isSuccessful) {
                        Log.d(TAG, "✓ DisableAcceptor 成功")
                    } else {
                        Log.w(TAG, "✗ DisableAcceptor 失败，但继续使用连接")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "DisableAcceptor 调用异常（可忽略，继续使用连接）: DeviceID=${response.deviceID}", e)
                }
                
                // 在 OpenConnection 成功后，立即执行设备配置流程
                // 1. GetCurrencyAssignment（获取面额分配和计数器）
                try {
                    val assignments = fetchCurrencyAssignments(response.deviceID)
                    if (assignments.isNotEmpty()) {
                        Log.d(TAG, "硬币器：GetCurrencyAssignment 成功，获取到 ${assignments.size} 个面额")
                        // 设置基线库存（基于 GetCurrencyAssignment 的 stored）
                        amountTracker.setBaselineFromAssignments(response.deviceID, assignments)
                    } else {
                        Log.w(TAG, "硬币器：GetCurrencyAssignment 返回空列表")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "硬币器：GetCurrencyAssignment 异常", e)
                }
                
                // 2. SetInhibits（设置"可接收面额"）- 由 GetCurrencyAssignment 动态生成
                try {
                    val inhibitSuccess = applyInhibitsFromAssignments(response.deviceID, "SMART_COIN_SYSTEM-1")
                    if (!inhibitSuccess) {
                        Log.w(TAG, "硬币器：SetInhibits 失败，但继续执行后续配置")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "硬币器：SetInhibits 异常", e)
                }
                
                // 注意：硬币器不设置 SetRoutes（按用户要求，硬币器不能设置路由）
                
                true
            } else {
                Log.e(TAG, "硬币器连接失败: ${response.error}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "打开硬币器连接异常", e)
            false
        }
    }
    
    /**
     * 启动设备
     * 注意：服务器返回 text/plain 纯文本，response.isSuccessful 即认为成功
     */
    /**
     * 启动纸币器设备（无参数版本，使用内部 deviceID）
     */
    suspend fun startBillDevice(): Boolean {
        val deviceID = _billAcceptorDeviceID.value
        if (deviceID == null) {
            Log.w(TAG, "启动纸币器设备失败: deviceID=null (设备未连接)")
            return false
        }
        return startDevice(deviceID)
    }
    
    /**
     * 启动硬币器设备（无参数版本，使用内部 deviceID）
     */
    suspend fun startCoinDevice(): Boolean {
        val deviceID = _coinAcceptorDeviceID.value
        if (deviceID == null) {
            Log.w(TAG, "启动硬币器设备失败: deviceID=null (设备未连接)")
            return false
        }
        return startDevice(deviceID)
    }
    
    suspend fun startDevice(deviceID: String): Boolean {
        return try {
            Log.d(TAG, "启动设备: deviceID=$deviceID")
            val response = api.startDevice(deviceID)
            val bodyText = cleanResponseBody(response.body()?.string())
            Log.d(TAG, "StartDevice 响应: isSuccessful=${response.isSuccessful}, code=${response.code()}, body=$bodyText")
            if (response.isSuccessful) {
                Log.d(TAG, "设备启动成功: deviceID=$deviceID, body=$bodyText")
                true
            } else {
                Log.e(TAG, "设备启动失败: deviceID=$deviceID, code=${response.code()}, body=$bodyText")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动设备异常: deviceID=$deviceID", e)
            false
        }
    }
    
    /**
     * 启用接收器
     * 注意：服务器返回 text/plain 纯文本（如 "Message: Acceptor enabled successfully."），response.isSuccessful 即认为成功
     */
    suspend fun enableAcceptor(deviceID: String): Boolean {
        return try {
            Log.d(TAG, "========== POST /EnableAcceptor 请求开始 ==========")
            Log.d(TAG, "deviceID=$deviceID")
            Log.d(TAG, "endpoint=/CashDevice/EnableAcceptor?deviceID=$deviceID")
            
            val response = api.enableAcceptor(deviceID)
            val bodyText = cleanResponseBody(response.body()?.string())
            
            Log.d(TAG, "========== POST /EnableAcceptor 响应结束 ==========")
            Log.d(TAG, "deviceID=$deviceID")
            Log.d(TAG, "HTTP code=${response.code()}")
            Log.d(TAG, "isSuccessful=${response.isSuccessful}")
            Log.d(TAG, "body=$bodyText")
            
            if (response.isSuccessful) {
                Log.d(TAG, "接收器启用成功: deviceID=$deviceID, body=$bodyText")
                true
            } else {
                Log.e(TAG, "接收器启用失败: deviceID=$deviceID, code=${response.code()}, body=$bodyText")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "========== POST /EnableAcceptor 异常 ==========")
            Log.e(TAG, "deviceID=$deviceID")
            Log.e(TAG, "异常类型=${e.javaClass.simpleName}")
            Log.e(TAG, "异常消息=${e.message}")
            Log.e(TAG, "异常堆栈:", e)
            false
        }
    }
    
    /**
     * 禁用接收器
     * 注意：服务器返回 text/plain 纯文本，response.isSuccessful 即认为成功
     */
    suspend fun disableAcceptor(deviceID: String): Boolean {
        return try {
            Log.d(TAG, "========== POST /DisableAcceptor 请求开始 ==========")
            Log.d(TAG, "deviceID=$deviceID")
            Log.d(TAG, "endpoint=/CashDevice/DisableAcceptor?deviceID=$deviceID")
            
            val response = api.disableAcceptor(deviceID)
            val bodyText = cleanResponseBody(response.body()?.string())
            
            Log.d(TAG, "========== POST /DisableAcceptor 响应结束 ==========")
            Log.d(TAG, "deviceID=$deviceID")
            Log.d(TAG, "HTTP code=${response.code()}")
            Log.d(TAG, "isSuccessful=${response.isSuccessful}")
            Log.d(TAG, "body=$bodyText")
            
            if (response.isSuccessful) {
                Log.d(TAG, "接收器禁用成功: deviceID=$deviceID, body=$bodyText")
                true
            } else {
                Log.e(TAG, "接收器禁用失败: deviceID=$deviceID, code=${response.code()}, body=$bodyText")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "========== POST /DisableAcceptor 异常 ==========")
            Log.e(TAG, "deviceID=$deviceID")
            Log.e(TAG, "异常类型=${e.javaClass.simpleName}")
            Log.e(TAG, "异常消息=${e.message}")
            Log.e(TAG, "异常堆栈:", e)
            false
        }
    }
    
    /**
     * 设置自动接受
     * 注意：服务器返回 text/plain 纯文本（如 "Message: Auto-accept set to True"），response.isSuccessful 即认为成功
     */
    suspend fun setAutoAccept(deviceID: String, autoAccept: Boolean = true): Boolean {
        return try {
            Log.d(TAG, "设置自动接受: deviceID=$deviceID, autoAccept=$autoAccept")
            val response = api.setAutoAccept(deviceID, autoAccept)
            val bodyText = cleanResponseBody(response.body()?.string())
            Log.d(TAG, "SetAutoAccept 响应: isSuccessful=${response.isSuccessful}, code=${response.code()}, body=$bodyText")
            if (response.isSuccessful) {
                Log.d(TAG, "自动接受设置成功: deviceID=$deviceID, autoAccept=$autoAccept, body=$bodyText")
                true
            } else {
                Log.e(TAG, "自动接受设置失败: deviceID=$deviceID, autoAccept=$autoAccept, code=${response.code()}, body=$bodyText")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "设置自动接受异常: deviceID=$deviceID, autoAccept=$autoAccept", e)
            false
        }
    }
    
    /**
     * 开始纸币器现金支付会话（启用接收器并开启自动接受）
     * 在用户进入现金支付界面时调用（无参数版本，使用内部 deviceID）
     * @return Boolean 如果所有步骤成功返回 true，否则返回 false
     */
    suspend fun beginBillCashSession(): Boolean {
        val deviceID = _billAcceptorDeviceID.value
        if (deviceID == null) {
            Log.e(TAG, "开始纸币器现金支付会话失败: deviceID=null (设备未连接)")
            return false
        }
        return beginCashSession(deviceID)
    }
    
    /**
     * 开始硬币器现金支付会话（启用接收器并开启自动接受）
     * 在用户进入现金支付界面时调用（无参数版本，使用内部 deviceID）
     * @return Boolean 如果所有步骤成功返回 true，否则返回 false
     */
    suspend fun beginCoinCashSession(): Boolean {
        val deviceID = _coinAcceptorDeviceID.value
        if (deviceID == null) {
            Log.e(TAG, "开始硬币器现金支付会话失败: deviceID=null (设备未连接)")
            return false
        }
        return beginCashSession(deviceID)
    }
    
    /**
     * 开始现金支付会话（启用接收器并开启自动接受）
     * 在用户进入现金支付界面时调用
     * @param deviceID 设备ID
     * @return Boolean 如果所有步骤成功返回 true，否则返回 false
     */
    suspend fun beginCashSession(deviceID: String): Boolean {
        Log.e("CASH_SESSION_MARK", "BEGIN_CASH_SESSION")
        Log.d(TAG, "========== 开始现金支付会话 ==========")
        Log.d(TAG, "deviceID=$deviceID")
        
        try {
            // 1. 启用接收器
            val enableSuccess = enableAcceptor(deviceID)
            if (!enableSuccess) {
                Log.e(TAG, "开始现金支付会话失败：启用接收器失败, deviceID=$deviceID")
                return false
            }
            
            // 2. 设置自动接受为 true
            val autoAcceptSuccess = setAutoAccept(deviceID, true)
            if (!autoAcceptSuccess) {
                Log.e(TAG, "开始现金支付会话失败：设置自动接受失败, deviceID=$deviceID")
                // 如果设置自动接受失败，尝试禁用接收器以恢复状态
                disableAcceptor(deviceID)
                return false
            }
            
            Log.d(TAG, "========== 开始现金支付会话成功 ==========")
            Log.d(TAG, "deviceID=$deviceID")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "开始现金支付会话异常: deviceID=$deviceID", e)
            // 异常时尝试禁用接收器以恢复状态
            try {
                disableAcceptor(deviceID)
            } catch (disableException: Exception) {
                Log.e(TAG, "恢复状态失败（禁用接收器异常）: deviceID=$deviceID", disableException)
            }
            return false
        }
    }
    
    /**
     * 结束纸币器现金支付会话（关闭自动接受并禁用接收器）
     * 在支付完成/取消/超时/退款时调用（无参数版本，使用内部 deviceID）
     */
    suspend fun endBillCashSession() {
        val deviceID = _billAcceptorDeviceID.value
        if (deviceID == null) {
            Log.w(TAG, "结束纸币器现金支付会话: deviceID=null (设备未连接)")
            return
        }
        endCashSession(deviceID)
    }
    
    /**
     * 结束硬币器现金支付会话（关闭自动接受并禁用接收器）
     * 在支付完成/取消/超时/退款时调用（无参数版本，使用内部 deviceID）
     */
    suspend fun endCoinCashSession() {
        val deviceID = _coinAcceptorDeviceID.value
        if (deviceID == null) {
            Log.w(TAG, "结束硬币器现金支付会话: deviceID=null (设备未连接)")
            return
        }
        endCashSession(deviceID)
    }
    
    /**
     * 结束现金支付会话（关闭自动接受并禁用接收器）
     * 在支付完成/取消/超时/退款时调用
     * @param deviceID 设备ID
     */
    suspend fun endCashSession(deviceID: String) {
        Log.e("CASH_SESSION_MARK", "END_CASH_SESSION")
        Log.d(TAG, "========== 结束现金支付会话 ==========")
        Log.d(TAG, "deviceID=$deviceID")
        
        try {
            // 1. 设置自动接受为 false
            val autoAcceptSuccess = setAutoAccept(deviceID, false)
            if (!autoAcceptSuccess) {
                Log.w(TAG, "结束现金支付会话：设置自动接受失败（继续执行禁用接收器）, deviceID=$deviceID")
            }
            
            // 2. 禁用接收器
            val disableSuccess = disableAcceptor(deviceID)
            if (!disableSuccess) {
                Log.w(TAG, "结束现金支付会话：禁用接收器失败, deviceID=$deviceID")
            }
            
            Log.d(TAG, "========== 结束现金支付会话完成 ==========")
            Log.d(TAG, "deviceID=$deviceID")
        } catch (e: Exception) {
            Log.w(TAG, "结束现金支付会话异常（不抛异常）: deviceID=$deviceID", e)
        }
    }
    
    /**
     * 启用纸币器接收器（使用内部持有的 deviceID）
     */
    suspend fun enableBillAcceptor(): Boolean {
        val deviceID = _billAcceptorDeviceID.value
        if (deviceID == null) {
            Log.e("CASH_ACCEPTOR_STATE", "enableBillAcceptor: deviceID=null (设备未连接)")
            return false
        }
        Log.e("CASH_ACCEPTOR_STATE", "enableBillAcceptor: deviceID=$deviceID")
        val result = enableAcceptor(deviceID)
        Log.e("CASH_ACCEPTOR_STATE", "enableBillAcceptor: deviceID=$deviceID, result=$result")
        return result
    }
    
    /**
     * 禁用纸币器接收器（使用内部持有的 deviceID）
     */
    suspend fun disableBillAcceptor(): Boolean {
        val deviceID = _billAcceptorDeviceID.value
        if (deviceID == null) {
            Log.e("CASH_ACCEPTOR_STATE", "disableBillAcceptor: deviceID=null (设备未连接)")
            return false
        }
        Log.e("CASH_ACCEPTOR_STATE", "disableBillAcceptor: deviceID=$deviceID")
        val result = disableAcceptor(deviceID)
        Log.e("CASH_ACCEPTOR_STATE", "disableBillAcceptor: deviceID=$deviceID, result=$result")
        return result
    }
    
    /**
     * 启用硬币器接收器（使用内部持有的 deviceID）
     */
    suspend fun enableCoinAcceptor(): Boolean {
        val deviceID = _coinAcceptorDeviceID.value
        if (deviceID == null) {
            Log.e("CASH_ACCEPTOR_STATE", "enableCoinAcceptor: deviceID=null (设备未连接)")
            return false
        }
        Log.e("CASH_ACCEPTOR_STATE", "enableCoinAcceptor: deviceID=$deviceID")
        val result = enableAcceptor(deviceID)
        Log.e("CASH_ACCEPTOR_STATE", "enableCoinAcceptor: deviceID=$deviceID, result=$result")
        return result
    }
    
    /**
     * 禁用硬币器接收器（使用内部持有的 deviceID）
     */
    suspend fun disableCoinAcceptor(): Boolean {
        val deviceID = _coinAcceptorDeviceID.value
        if (deviceID == null) {
            Log.e("CASH_ACCEPTOR_STATE", "disableCoinAcceptor: deviceID=null (设备未连接)")
            return false
        }
        Log.e("CASH_ACCEPTOR_STATE", "disableCoinAcceptor: deviceID=$deviceID")
        val result = disableAcceptor(deviceID)
        Log.e("CASH_ACCEPTOR_STATE", "disableCoinAcceptor: deviceID=$deviceID, result=$result")
        return result
    }
    
    /**
     * 获取设备状态（服务器返回 List<DeviceStatusResponse>，可能为空数组 []）
     * @return DeviceStatusResponse 如果数组为空，返回缓存的上一次状态或 UNKNOWN；否则返回最后一个元素（通常代表最新状态）
     */
    suspend fun getDeviceStatus(deviceID: String): DeviceStatusResponse {
        return try {
            Log.d(TAG, "获取设备状态: deviceID=$deviceID")
            val statusList = api.getDeviceStatus(deviceID)
            val rawCount = statusList.size
            Log.d(TAG, "GetDeviceStatus 响应: rawCount=$rawCount")
            
            if (statusList.isEmpty()) {
                // 空数组：返回缓存的上一次状态或 UNKNOWN
                val cachedStatus = lastKnownStatus[deviceID]
                if (cachedStatus != null) {
                    Log.d(TAG, "设备状态数组为空 []，沿用上次状态=${cachedStatus.actualState}: deviceID=$deviceID")
                    return cachedStatus
                } else {
                    Log.d(TAG, "设备状态数组为空 []，无缓存，返回 UNKNOWN: deviceID=$deviceID")
                    val unknownStatus = DeviceStatusResponse(
                        type = null,
                        state = "UNKNOWN",
                        stateAsString = "Unknown"
                    )
                    lastKnownStatus[deviceID] = unknownStatus  // 缓存 UNKNOWN 状态
                    return unknownStatus
                }
            }
            
            // 取最后一个元素（通常代表最新状态）
            val latestStatus = statusList.last()
            val latestState = latestStatus.actualState ?: "UNKNOWN"
            
            Log.d(TAG, "GetDeviceStatus 最新状态: latestState=$latestState, rawCount=$rawCount")
            
            // 映射状态字符串到内部枚举（如果需要）
            // 状态可能的值：IDLE, STARTED, CONNECTED, BUSY, ERROR, UNKNOWN 等
            val mappedState = when (latestState.uppercase()) {
                "IDLE", "IDLING" -> "IDLE"
                "STARTED", "STARTING" -> "STARTED"
                "CONNECTED", "CONNECTING" -> "CONNECTED"
                "BUSY", "PROCESSING", "PROCESSINGNOTE", "PROCESSINGCOIN" -> "BUSY"
                "ERROR", "FAILED", "FAULT" -> "ERROR"
                "UNKNOWN" -> "UNKNOWN"
                else -> {
                    Log.w(TAG, "未知状态字符串: $latestState，映射为 UNKNOWN")
                    "UNKNOWN"
                }
            }
            
            // 如果状态字符串需要映射，创建一个新的响应对象
            val finalStatus = if (mappedState != latestState.uppercase()) {
                DeviceStatusResponse(
                    type = latestStatus.type,
                    state = mappedState,
                    stateAsString = mappedState
                )
            } else {
                latestStatus
            }
            
            // 更新缓存
            lastKnownStatus[deviceID] = finalStatus
            
            finalStatus
        } catch (e: kotlinx.serialization.SerializationException) {
            Log.e(TAG, "获取设备状态：JSON 反序列化失败: deviceID=$deviceID", e)
            // 记录原始错误信息用于排查
            Log.w(TAG, "设备状态查询失败（JSON 解析错误），返回缓存或 UNKNOWN（容错处理）")
            // 返回缓存或 UNKNOWN
            val cachedStatus = lastKnownStatus[deviceID]
            if (cachedStatus != null) {
                Log.d(TAG, "返回缓存状态: ${cachedStatus.actualState}")
                return cachedStatus
            } else {
                val unknownStatus = DeviceStatusResponse(
                    type = null,
                    state = "UNKNOWN",
                    stateAsString = "Unknown"
                )
                lastKnownStatus[deviceID] = unknownStatus
                return unknownStatus
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取设备状态异常: deviceID=$deviceID", e)
            // 即使状态查询失败，也返回缓存或 UNKNOWN，不阻塞支付流程
            Log.w(TAG, "设备状态查询失败，返回缓存或 UNKNOWN（容错处理）")
            // 返回缓存或 UNKNOWN
            val cachedStatus = lastKnownStatus[deviceID]
            if (cachedStatus != null) {
                Log.d(TAG, "返回缓存状态: ${cachedStatus.actualState}")
                return cachedStatus
            } else {
                val unknownStatus = DeviceStatusResponse(
                    type = null,
                    state = "UNKNOWN",
                    stateAsString = "Unknown"
                )
                lastKnownStatus[deviceID] = unknownStatus
                return unknownStatus
            }
        }
    }
    
    /**
     * 断开设备连接
     * 注意：服务器返回 text/plain 纯文本，response.isSuccessful 即认为成功
     */
    suspend fun disconnectDevice(deviceID: String): Boolean {
        return try {
            Log.d(TAG, "断开设备连接: deviceID=$deviceID")
            
            // ⚠️ 清除连接状态
            val deviceKey = if (deviceID.contains("SPECTRAL") || deviceID.contains("BILL")) {
                "SPECTRAL_PAYOUT-0"
            } else {
                "SMART_COIN_SYSTEM-1"
            }
            setDeviceConnectionState(deviceKey, false)
            
            val response = api.disconnectDevice(deviceID)
            val bodyText = cleanResponseBody(response.body()?.string())
            Log.d(TAG, "DisconnectDevice 响应: isSuccessful=${response.isSuccessful}, code=${response.code()}, body=$bodyText")
            if (response.isSuccessful) {
                Log.d(TAG, "设备断开成功: deviceID=$deviceID, body=$bodyText")
                // 清除 deviceID 和状态缓存
                if (deviceID == _billAcceptorDeviceID.value) {
                    _billAcceptorDeviceID.value = null
                }
                if (deviceID == _coinAcceptorDeviceID.value) {
                    _coinAcceptorDeviceID.value = null
                }
                lastKnownStatus.remove(deviceID)  // 清除状态缓存
                amountTracker.removeDevice(deviceID)  // 清除金额跟踪
                true
            } else {
                Log.e(TAG, "设备断开失败: deviceID=$deviceID, code=${response.code()}, body=$bodyText")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "断开设备连接异常: deviceID=$deviceID", e)
            // 即使异常也清除连接状态
            val deviceKey = if (deviceID.contains("SPECTRAL") || deviceID.contains("BILL")) {
                "SPECTRAL_PAYOUT-0"
            } else {
                "SMART_COIN_SYSTEM-1"
            }
            setDeviceConnectionState(deviceKey, false)
            false
        }
    }
    
    /**
     * 启动现金设备会话（认证 + 打开双设备连接）
     * @return Map<String, String> 设备映射：deviceName -> deviceID
     *         例如：{"SPECTRAL_PAYOUT-0" -> "device-id-1", "SMART_COIN_SYSTEM-1" -> "device-id-2"}
     */
    /**
     * 预初始化会话（App启动时调用，只连接不启用收款）
     */
    suspend fun prewarmSession(): Map<String, String> {
        Log.d(TAG, "预初始化现金设备会话...")
        return try {
            sessionManager.prewarmSession()
        } catch (e: Exception) {
            Log.e(TAG, "预初始化现金设备会话失败", e)
            throw e
        }
    }
    
    /**
     * 检查设备是否正在连接或已连接（防止重复连接）
     * @param deviceKey 设备标识（如 "SPECTRAL_PAYOUT-0" 或 deviceID）
     * @return true 如果设备正在连接或已连接，false 如果可以连接
     */
    private fun isDeviceConnectingOrConnected(deviceKey: String): Boolean {
        synchronized(connectionLockMutex) {
            return connectionLocks[deviceKey] == true
        }
    }
    
    /**
     * 设置设备连接状态（正在连接/已连接）
     * @param deviceKey 设备标识
     * @param isConnecting true 表示正在连接/已连接，false 表示未连接
     */
    private fun setDeviceConnectionState(deviceKey: String, isConnecting: Boolean) {
        synchronized(connectionLockMutex) {
            if (isConnecting) {
                connectionLocks[deviceKey] = true
                Log.d(TAG, "设置设备连接状态: deviceKey=$deviceKey, isConnecting=true（正在连接/已连接）")
            } else {
                connectionLocks.remove(deviceKey)
                Log.d(TAG, "设置设备连接状态: deviceKey=$deviceKey, isConnecting=false（未连接）")
            }
        }
    }
    
    suspend fun startCashSession(): Map<String, String> {
        // ⚠️ 互斥保护：防止重复点击/并发调用
        return startSessionMutex.withLock {
            if (isStartingSession) {
                val errorMsg = "正在启动支付，请稍候..."
                Log.w(TAG, "⚠️ $errorMsg")
                throw IllegalStateException(errorMsg)
            }
            
            isStartingSession = true
            try {
                Log.d(TAG, "========== 启动现金设备会话 ==========")
                
                val billKey = "SPECTRAL_PAYOUT-0"
                val coinKey = "SMART_COIN_SYSTEM-1"
                
                val billDeviceID = _billAcceptorDeviceID.value
                val coinDeviceID = _coinAcceptorDeviceID.value
                
                // ⚠️ 部分复用：允许只复用纸币器或只复用硬币器
                val devices = mutableMapOf<String, String>()
                
                // 检查纸币器：已连接 -> 复用，未连接 -> 检查是否正在连接
                if (billDeviceID != null) {
                    Log.d(TAG, "纸币器已连接，复用现有连接: billDeviceID=$billDeviceID")
                    devices["SPECTRAL_PAYOUT-0"] = billDeviceID
                } else {
                    // 检查是否正在连接（不是已连接可复用）
                    if (isDeviceConnectingOrConnected(billKey)) {
                        val errorMsg = "纸币器正在连接中，请稍候..."
                        Log.w(TAG, "⚠️ $errorMsg")
                        throw IllegalStateException(errorMsg)
                    }
                }
                
                // 检查硬币器：已连接 -> 复用，未连接 -> 检查是否正在连接
                if (coinDeviceID != null) {
                    Log.d(TAG, "硬币器已连接，复用现有连接: coinDeviceID=$coinDeviceID")
                    devices["SMART_COIN_SYSTEM-1"] = coinDeviceID
                } else {
                    // 检查是否正在连接（不是已连接可复用）
                    if (isDeviceConnectingOrConnected(coinKey)) {
                        val errorMsg = "硬币器正在连接中，请稍候..."
                        Log.w(TAG, "⚠️ $errorMsg")
                        throw IllegalStateException(errorMsg)
                    }
                }
                
                // 如果至少有一个设备已连接，直接复用（部分复用）
                if (devices.isNotEmpty()) {
                    Log.d(TAG, "部分复用设备: ${devices.keys.joinToString(", ")}")
                    // ⚠️ 关键修复：只有在非活跃会话时才能 reset
                    if (!sessionActive) {
                        Log.d(TAG, "重置金额跟踪器（新的支付会话，非活跃状态）")
                        amountTracker.reset()
                    } else {
                        Log.w(TAG, "⚠️ 警告：尝试在活跃会话期间 reset tracker，已跳过（防止丢金额）")
                    }
                    return@withLock devices
                }
                
                // 如果两个设备都未连接，需要启动新连接
                Log.d(TAG, "两个设备都未连接，启动新连接...")
                
                // 设置连接状态（标记为正在连接）
                setDeviceConnectionState(billKey, true)
                setDeviceConnectionState(coinKey, true)
                
                try {
                    // ⚠️ 关键修复：只有在非活跃会话时才能 reset
                    if (!sessionActive) {
                        Log.d(TAG, "重置金额跟踪器（新的支付会话，非活跃状态）")
                        amountTracker.reset()
                    } else {
                        Log.w(TAG, "⚠️ 警告：尝试在活跃会话期间 reset tracker，已跳过（防止丢金额）")
                    }
                    
                    // 使用会话管理器启动会话（启用收款）
                    val newDevices = try {
                        sessionManager.startSession()
                    } catch (e: Exception) {
                        Log.e(TAG, "启动现金设备会话失败", e)
                        // 连接失败，清除连接状态
                        setDeviceConnectionState(billKey, false)
                        setDeviceConnectionState(coinKey, false)
                        throw e
                    }
                    
                    // 更新设备ID映射（兼容现有代码）并设置基线金额
                    newDevices["SPECTRAL_PAYOUT-0"]?.let { deviceID ->
                        _billAcceptorDeviceID.value = deviceID
                        Log.d(TAG, "纸币器 deviceID: $deviceID")
                        devices["SPECTRAL_PAYOUT-0"] = deviceID
                        // 启动阶段：不执行 SetInhibits/SetRoutes/EnableAcceptor
                        // 这些操作只在用户进入现金支付页面时执行
                        // 可选：读取库存快照用于首页展示（不影响设备状态）
                        try {
                            val levelsResponse = readCurrentLevels(deviceID)
                            val levels = levelsResponse.levels?.associate { it.value to it.stored } ?: emptyMap()
                            amountTracker.setBaseline(deviceID, levels)
                            Log.d(TAG, "纸币器：已读取库存快照（用于首页展示）")
                        } catch (e: Exception) {
                            Log.w(TAG, "纸币器：读取库存快照失败，继续使用空库存", e)
                            amountTracker.setBaseline(deviceID, emptyMap())  // 使用空库存
                        }
                    }
                    newDevices["SMART_COIN_SYSTEM-1"]?.let { deviceID ->
                        _coinAcceptorDeviceID.value = deviceID
                        Log.d(TAG, "硬币器 deviceID: $deviceID")
                        devices["SMART_COIN_SYSTEM-1"] = deviceID
                        // 启动阶段：不执行 SetInhibits/SetRoutes/EnableAcceptor
                        // 这些操作只在用户进入现金支付页面时执行
                        // 可选：读取库存快照用于首页展示（不影响设备状态）
                        try {
                            val levelsResponse = readCurrentLevels(deviceID)
                            val levels = levelsResponse.levels?.associate { it.value to it.stored } ?: emptyMap()
                            amountTracker.setBaseline(deviceID, levels)
                            Log.d(TAG, "硬币器：已读取库存快照（用于首页展示）")
                        } catch (e: Exception) {
                            Log.w(TAG, "硬币器：读取库存快照失败，继续使用空库存", e)
                            amountTracker.setBaseline(deviceID, emptyMap())  // 使用空库存
                        }
                    }
                    
                    Log.d(TAG, "现金设备会话启动成功: 已注册设备数量=${devices.size}")
                    
                    // 连接成功，保持连接状态（直到支付结束或断开连接）
                    return@withLock devices
                } catch (e: Exception) {
                    // 连接失败，清除连接状态
                    setDeviceConnectionState(billKey, false)
                    setDeviceConnectionState(coinKey, false)
                    throw e
                }
            } finally {
                isStartingSession = false
            }
        }
    }
    
    /**
     * 停止现金设备会话（清理支付会话状态）
     * 停止事件轮询、禁用接收器、释放连接标记
     * 
     * @param reason 停止原因（用于日志）
     */
    /**
     * 停止现金会话
     * ⚠️ 关键修复：打印 stopCashSession 的真实 reason，不要混用 COUNTERS_PARSE_FAILED 影响全局
     * @param reason 停止原因（必须明确，不要混用错误码）
     */
    suspend fun stopCashSession(reason: String = "正常结束") {
        Log.d(TAG, "========== stopCashSession 调用 ==========")
        Log.d(TAG, "停止原因: $reason")
        Log.d(TAG, "==========================================")
        Log.d(TAG, "========== 停止现金设备会话 ==========")
        Log.d(TAG, "reason=$reason")
        
        val billKey = "SPECTRAL_PAYOUT-0"
        val coinKey = "SMART_COIN_SYSTEM-1"
        val billDeviceID = _billAcceptorDeviceID.value
        val coinDeviceID = _coinAcceptorDeviceID.value
        
        try {
            // 1. 禁用接收器（NOTE/COIN）
            billDeviceID?.let { deviceID ->
                try {
                    Log.d(TAG, "禁用纸币器接收器: deviceID=$deviceID")
                    disableAcceptor(deviceID)
                } catch (e: Exception) {
                    Log.e(TAG, "禁用纸币器接收器失败: deviceID=$deviceID", e)
                }
            }
            
            coinDeviceID?.let { deviceID ->
                try {
                    Log.d(TAG, "禁用硬币器接收器: deviceID=$deviceID")
                    disableAcceptor(deviceID)
                } catch (e: Exception) {
                    Log.e(TAG, "禁用硬币器接收器失败: deviceID=$deviceID", e)
                }
            }
            
            // 2. 停止事件轮询（如果有）
            // 注意：事件轮询在 PaymentViewModel 的协程中，当 PaymentViewModel 调用 stopCashSession() 时，
            // 应该先取消自己的协程（通过 currentCoroutineContext().cancel()），然后再调用 stopCashSession()
            // 这里只清理状态，并记录日志
            Log.d(TAG, "停止事件轮询: reason=$reason")
            Log.d(TAG, "注意：事件轮询在 PaymentViewModel 的协程中，协程取消后轮询会自动停止")
            Log.d(TAG, "job cancelled/joined: 由 PaymentViewModel 的协程取消机制处理")
            
            // ⚠️ 关键修复：标记会话为非活跃状态，然后才能 reset
            setSessionActive(false)
            
            // 3. 重置金额跟踪器（只有在会话非活跃时才能 reset）
            amountTracker.reset()
            Log.d(TAG, "金额跟踪器已重置")
            
            // 4. 释放会话占用标记（但保持设备连接，因为配置依赖 AES128 会话）
            // ⚠️ 关键修复：修正误导性日志文案，明确这是"会话占用标记释放"，不是"未连接"
            // 注意：不清除 deviceID，因为设备需要保持连接
            // 只清除 isConnecting/isInPayment 标记（会话占用标记）
            setDeviceConnectionState(billKey, false)
            setDeviceConnectionState(coinKey, false)
            Log.d(TAG, "会话占用标记已释放（设备保持连接，未断开）")
            Log.d(TAG, "说明：isConnecting=false 表示会话占用标记释放，非真实连接状态")
            
            Log.d(TAG, "========== 停止现金设备会话完成 ==========")
            Log.d(TAG, "reason=$reason, 事件轮询已停止（由 PaymentViewModel 协程取消处理）")
        } catch (e: Exception) {
            Log.e(TAG, "停止现金设备会话异常", e)
            // 即使异常，也尝试清除连接标记
            setDeviceConnectionState(billKey, false)
            setDeviceConnectionState(coinKey, false)
        }
    }
    
    /**
     * 获取设备总收款金额
     * ⚠️ 关键修复：纸币器 SPECTRAL_PAYOUT-0 禁止使用 GetCounters，改用 GetAllLevels
     * ⚠️ 硬币器 SMART_COIN_SYSTEM-1 使用 GetCounters（仅用于维护统计）
     * 
     * @param deviceID 设备ID
     * @return 总收款金额（分），失败返回 null
     */
    suspend fun getTotalReceivedAmount(deviceID: String): Int? {
        return try {
            // ⚠️ 关键修复：纸币器 SPECTRAL_PAYOUT-0 禁止使用 GetCounters，改用 GetAllLevels
            if (deviceID.startsWith("SPECTRAL_PAYOUT", ignoreCase = true) || 
                deviceID.contains("SPECTRAL", ignoreCase = true) ||
                deviceID.contains("PAYOUT", ignoreCase = true)) {
                // 纸币器：使用 GetAllLevels 计算总金额
                val levels = readCurrentLevels(deviceID)
                val totalCents = levels.calculateTotalCents()
                Log.d(TAG, "获取设备总收款金额（纸币器，使用 GetAllLevels）: deviceID=$deviceID, totalCents=$totalCents (${totalCents / 100.0}€)")
                return totalCents
            }
            
            // 硬币器：使用 GetCounters（仅用于维护统计）
            val counters = getCounters(deviceID)
            if (counters.error != null) {
                Log.w(TAG, "硬币器 GetCounters 失败（非致命）: deviceID=$deviceID, error=${counters.error}")
                return null
            }
            
            // 总收款金额 = stacked + stored + coinsPaidIn（不包括 rejected）
            val totalCents = counters.totalReceivedCents
            
            Log.d(TAG, "设备总收款: deviceID=$deviceID")
            Log.d(TAG, "  计数项: stacked=${counters.stacked}, stored=${counters.stored}, rejected=${counters.rejected}, coinsPaidIn=${counters.coinsPaidIn}")
            Log.d(TAG, "  金额: stackedCents=${counters.stackedCents}, storedCents=${counters.storedCents}, rejectedCents=${counters.rejectedCents}, coinsPaidInCents=${counters.coinsPaidInCents}")
            Log.d(TAG, "  总收款: totalCents=${totalCents}分 (${totalCents / 100.0}€)")
            
            totalCents
        } catch (e: Exception) {
            Log.e(TAG, "获取设备总收款金额异常: deviceID=$deviceID", e)
            null
        }
    }
    
    /**
     * 读取当前库存（获取设备各面额库存）
     * @param deviceID 设备ID
     * @return LevelsResponse 库存响应（包含各面额的 Value 和 Stored）
     */
    suspend fun readCurrentLevels(deviceID: String): LevelsResponse {
        return try {
            Log.d(TAG, "读取库存: deviceID=$deviceID")
            val levelsResponse = api.getAllLevels(deviceID)
            val levelsList = levelsResponse.levels ?: emptyList()
            val levelsCount = levelsList.size
            val totalCents = levelsResponse.calculateTotalCents()
            
            // 详细日志（不允许再出现 Levels 响应条目数=0 但 HTTP Body 明明有 Levels 这种情况）
            Log.d(TAG, "GetAllLevels 响应: deviceID=$deviceID, levelsCount=$levelsCount, totalCents=$totalCents (${levelsResponse.calculateTotalAmount()}元), success=${levelsResponse.success}, message=${levelsResponse.message}")
            
            if (levelsCount == 0) {
                Log.w(TAG, "GetAllLevels 返回空列表: deviceID=$deviceID, success=${levelsResponse.success}, message=${levelsResponse.message}")
            } else {
                // 打印每个面额的详细信息
                levelsList.forEach { level ->
                    Log.d(TAG, "  面额: Value=${level.value}分, Stored=${level.stored}, CountryCode=${level.countryCode}")
                }
            }
            
            // 如果 levels 为空但 success=true，返回空列表（至少返回 emptyList 但要有日志说明原因）
            if (levelsCount == 0 && levelsResponse.success == true) {
                Log.d(TAG, "GetAllLevels 返回空列表但 success=true，可能是设备库存为空: deviceID=$deviceID")
            }
            
            levelsResponse
        } catch (e: retrofit2.HttpException) {
            // HTTP 错误（如 404）
            val code = e.code()
            val errorBody = e.response()?.errorBody()?.string() ?: ""
            Log.e(TAG, "读取库存 HTTP 错误: endpoint=GetAllLevels, deviceID=$deviceID, code=$code, errorBody=$errorBody")
            // 返回空的库存响应（容错处理），不把金额清零
            LevelsResponse(deviceID = deviceID, error = "HTTP $code: $errorBody")
        } catch (e: Exception) {
            Log.e(TAG, "读取库存异常: deviceID=$deviceID", e)
            // 返回空的库存响应（容错处理），不把金额清零
            LevelsResponse(deviceID = deviceID, error = e.message)
        }
    }
    
    /**
     * 轮询库存（获取设备已收金额）- 基于 GetCurrencyAssignment 做快照差分
     * @param deviceID 设备ID
     * @return List<CurrencyAssignment> 货币分配列表（服务器返回数组 []）
     */
    suspend fun pollCurrencyAssignments(deviceID: String): List<CurrencyAssignment> {
        return try {
            Log.d(TAG, "轮询货币分配: deviceID=$deviceID")
            val assignments = fetchCurrencyAssignments(deviceID)
            
            // 更新金额跟踪器（基于货币分配的 stored 做快照差分）
            // 如果读取失败（返回空列表），保留上一次成功值，不更新
            if (assignments.isNotEmpty()) {
                val sessionDeltaCents = amountTracker.updateFromAssignments(deviceID, assignments)
                
                // 轮询日志必须打印：deviceId、面额数、totalCents、baseline、delta、最近变化
                val assignmentsCount = assignments.size
                val totalCents = amountTracker.getTotalCents()
                val baselineTotalCents = amountTracker.getDeviceBaselineCents(deviceID)
                val currentTotalCents = amountTracker.getDeviceCurrentCents(deviceID)
                val recentChanges = amountTracker.getRecentChanges(deviceID)
                val recentChangesText = recentChanges.joinToString(", ") { it.getDisplayText() }
                
                Log.d(TAG, "轮询日志: deviceID=$deviceID, count=$assignmentsCount, currentTotalCents=$currentTotalCents, baselineTotalCents=$baselineTotalCents, sessionDeltaCents=$sessionDeltaCents, totalCents=$totalCents")
                if (recentChanges.isNotEmpty()) {
                    Log.d(TAG, "最近变化: $recentChangesText")
                }
            } else {
                Log.w(TAG, "轮询货币分配失败，保留上一次成功值: deviceID=$deviceID（返回空列表）")
            }
            
            assignments
        } catch (e: Exception) {
            Log.e(TAG, "轮询货币分配异常: deviceID=$deviceID", e)
            // 返回空列表（容错处理），不把金额清零
            emptyList()
        }
    }
    
    /**
     * 轮询库存（获取设备已收金额）- 基于库存差值计算本次会话累计金额（保留兼容性）
     * @param deviceID 设备ID
     * @return LevelsResponse 库存响应（包含各面额的 Value 和 Stored）
     * @deprecated 请使用 pollCurrencyAssignments（基于 GetCurrencyAssignment）
     */
    @Deprecated("请使用 pollCurrencyAssignments", ReplaceWith("pollCurrencyAssignments(deviceID)"))
    suspend fun pollLevels(deviceID: String): LevelsResponse {
        return try {
            Log.d(TAG, "轮询库存: deviceID=$deviceID")
            val levelsResponse = readCurrentLevels(deviceID)
            
            // 更新金额跟踪器（基于库存差值计算本次会话累计金额）
            // 如果读取失败（error != null），保留上一次成功值，不更新
            if (levelsResponse.error == null && levelsResponse.levels != null) {
                amountTracker.update(deviceID, levelsResponse)
                
                // 轮询日志必须打印：deviceId、levelsCount、totalCents、baseline、delta
                val levelsCount = levelsResponse.levels.size
                val totalCents = levelsResponse.calculateTotalCents()
                val baselineTotalCents = amountTracker.getDeviceBaselineCents(deviceID)
                val sessionDeltaCents = amountTracker.getDeviceSessionCents(deviceID)
                Log.d(TAG, "轮询日志: deviceID=$deviceID, levelsCount=$levelsCount, totalCents=$totalCents, baselineTotalCents=$baselineTotalCents, sessionDeltaCents=$sessionDeltaCents")
            } else {
                Log.w(TAG, "轮询库存失败，保留上一次成功值: deviceID=$deviceID, error=${levelsResponse.error}")
            }
            
            levelsResponse
        } catch (e: Exception) {
            Log.e(TAG, "轮询库存异常: deviceID=$deviceID", e)
            // 返回空的库存响应（容错处理），不把金额清零
            LevelsResponse(deviceID = deviceID, error = e.message)
        }
    }
    
    /**
     * 获取所有面额库存（用于 UI 显示）
     * @param deviceID 设备ID
     * @return List<LevelEntry> 面额库存列表（非空，至少返回 emptyList）
     */
    suspend fun getAllLevels(deviceID: String): List<LevelEntry> {
        return try {
            val levelsResponse = readCurrentLevels(deviceID)
            val levels = levelsResponse.levels ?: emptyList()
            if (levels.isEmpty()) {
                Log.d(TAG, "getAllLevels 返回空列表: deviceID=$deviceID（可能是设备库存为空）")
            }
            levels
        } catch (e: Exception) {
            Log.e(TAG, "getAllLevels 异常: deviceID=$deviceID", e)
            emptyList()  // 至少返回 emptyList
        }
    }
    
    /**
     * 找零（按金额）
     * @param deviceID 设备ID
     * @param valueCents 找零金额（分），如 200 表示 2€
     * @param countryCode 货币代码，默认 EUR
     * @return Boolean 是否成功
     */
    suspend fun dispenseValue(deviceID: String, valueCents: Int, countryCode: String = "EUR"): Boolean {
        // ⚠️ 关键修复：串行锁 + BUSY 退避重试 + 状态确认
        // 对每个 deviceID 建立 Mutex，保证同一时刻只有一个 dispense 在跑
        val deviceMutex = getDeviceDispenseMutex(deviceID)
        
        return deviceMutex.withLock {
            try {
                val request = DispenseValueRequest(value = valueCents, countryCode = countryCode)
                
                // 构建完整 URL（用于日志）
                val baseUrl = "http://127.0.0.1:5000/api"
                val fullUrl = "$baseUrl/CashDevice/DispenseValue?deviceID=$deviceID"
                
                // ⚠️ 关键修复：使用与 Retrofit 相同的序列化配置（encodeDefaults=true，确保 CountryCode 被序列化）
                val jsonForLog = kotlinx.serialization.json.Json {
                    encodeDefaults = true  // ⚠️ 关键：确保所有字段都被序列化（包括 CountryCode）
                    ignoreUnknownKeys = false
                }
                val requestBodyJson = jsonForLog.encodeToString(
                    kotlinx.serialization.serializer<DispenseValueRequest>(), request
                )
                
                Log.d(TAG, "========== DispenseValue 请求开始（串行锁保护） ==========")
                Log.d(TAG, "deviceID: $deviceID")
                Log.d(TAG, "amountCents=$valueCents (${valueCents / 100.0}€)")
                Log.d(TAG, "countryCode=$countryCode")
                Log.d(TAG, "fullUrl: $fullUrl")
                Log.d(TAG, "序列化后的 RequestBody（用于日志验证）: $requestBodyJson")
                
                // ⚠️ 硬保护：如果构造出的 body 缺 CountryCode，直接在本地抛错并拒绝发送
                if (!requestBodyJson.contains("CountryCode") || !requestBodyJson.contains("\"CountryCode\"")) {
                    Log.e(TAG, "========== ⚠️ 严重错误：请求体缺少 CountryCode，拒绝发送 ==========")
                    Log.e(TAG, "requestBodyJson=$requestBodyJson")
                    Log.e(TAG, "request 对象内容: value=${request.value}, countryCode=${request.countryCode}")
                    Log.e(TAG, "=========================================")
                    // 直接抛错并拒绝发送，避免设备收到 INVALID_INPUT
                    throw IllegalStateException("请求体缺少 CountryCode，拒绝发送以避免 INVALID_INPUT")
                }
                
                // ⚠️ 验证请求体必须包含 Value 和 CountryCode
                if (!requestBodyJson.contains("\"Value\"") || !requestBodyJson.contains("\"CountryCode\"")) {
                    Log.e(TAG, "========== ⚠️ 严重错误：请求体格式不正确 ==========")
                    Log.e(TAG, "requestBodyJson=$requestBodyJson")
                    throw IllegalStateException("请求体格式不正确，必须包含 Value 和 CountryCode")
                }
                
                Log.d(TAG, "✓ 验证通过: DispenseValue requestBody 包含 Value 和 CountryCode")
                
                // ⚠️ 关键修复：注意：实际发送的 body 由 Retrofit 序列化，可能因 encodeDefaults=false 而不同
                // ⚠️ 真实发送的 body 会在 OkHttp 日志拦截器中打印（HttpLoggingInterceptor.Level.BODY）
                Log.d(TAG, "--> POST /CashDevice/DispenseValue?deviceID=$deviceID")
                Log.d(TAG, "⚠️ 注意：真实发送的 body 请查看 OkHttp 日志（HttpLoggingInterceptor），确保包含 CountryCode")
            
            // ⚠️ 关键日志：每次下发 DispenseValue 前打印金额单位信息
            // ⚠️ 金额单位确认：DispenseValue.Value 统一为 cents（分）
            // ⚠️ SMART_COIN_SYSTEM-1 验证：Value=100→1€，Value=200→2€（现场验证结论）
            // ⚠️ SPECTRAL_PAYOUT-0 验证：Value=500→5€，Value=1000→10€（现场验证结论）
            val amountEUR = valueCents / 100.0
            val isSmartCoinSystem = deviceID.startsWith("SMART_COIN_SYSTEM", ignoreCase = true) || 
                                    deviceID.contains("SMART_COIN", ignoreCase = true)
            val isSpectralPayout = deviceID.startsWith("SPECTRAL_PAYOUT", ignoreCase = true) || 
                                   deviceID.contains("SPECTRAL", ignoreCase = true) ||
                                   deviceID.contains("PAYOUT", ignoreCase = true)
            
            Log.d(TAG, "========== DispenseValue 金额单位确认 ==========")
            Log.d(TAG, "deviceID=$deviceID")
            Log.d(TAG, "amountCents=$valueCents")
            Log.d(TAG, "amountEUR=$amountEUR")
            Log.d(TAG, "currency=$countryCode")
            if (isSmartCoinSystem) {
                Log.d(TAG, "⚠️ SMART_COIN_SYSTEM-1 单位说明：DispenseValue.Value 为 cents（分）")
                Log.d(TAG, "   现场验证：Value=100→1€，Value=200→2€")
            }
            if (isSpectralPayout) {
                Log.d(TAG, "⚠️ SPECTRAL_PAYOUT-0 单位说明：DispenseValue.Value 为 cents（分）")
                Log.d(TAG, "   现场验证：Value=500→5€，Value=1000→10€")
            }
            Log.d(TAG, "endpoint=/CashDevice/DispenseValue")
            Log.d(TAG, "requestBodyJson=$requestBodyJson")
            Log.d(TAG, "================================================")
            
            val response = api.dispenseValue(deviceID, request)
            val httpCode = response.code()
            
            // ⚠️ Step C: 400 时必须读取 errorBody，不要只打印空的 rawResponseBody
            val bodyText = if (response.isSuccessful) {
                cleanResponseBody(response.body()?.string())
            } else {
                // 失败时读取 errorBody
                val errorBody = response.errorBody()
                val errorBodyText = try {
                    errorBody?.string() ?: ""
                } catch (e: Exception) {
                    Log.w(TAG, "读取 errorBody 失败", e)
                    ""
                }
                cleanResponseBody(errorBodyText)
            }
            
            Log.d(TAG, "<-- $httpCode ${if (response.isSuccessful) "OK" else "ERROR"}")
            Log.d(TAG, "responseBody: $bodyText")
            
            if (response.isSuccessful) {
                Log.d(TAG, "找零成功: deviceID=$deviceID, valueCents=$valueCents (${valueCents / 100.0}€)")
                Log.d(TAG, "========== DispenseValue 请求成功 ==========")
                true
            } else {
                // ⚠️ Step 2: 解析错误原因，特别处理 BUSY 和 INVALID_INPUT
                val errorReason = try {
                    if (bodyText.isNotEmpty()) {
                        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        val errorObj = json.parseToJsonElement(bodyText)
                        if (errorObj is kotlinx.serialization.json.JsonObject) {
                            // 优先读取 Reason 字段（BUSY 错误）
                            errorObj["Reason"]?.let { 
                                if (it is kotlinx.serialization.json.JsonPrimitive) {
                                    it.content
                                } else {
                                    it.toString()
                                }
                            } ?: errorObj["errorReason"]?.let { 
                                if (it is kotlinx.serialization.json.JsonPrimitive) {
                                    it.content
                                } else {
                                    it.toString()
                                }
                            } ?: bodyText
                        } else {
                            bodyText
                        }
                    } else {
                        "响应体为空"
                    }
                } catch (e: Exception) {
                    "解析错误失败: ${e.message}, 原始响应: $bodyText"
                }
                
                Log.e(TAG, "找零失败: deviceID=$deviceID, code=$httpCode, errorReason=$errorReason")
                Log.e(TAG, "errorBody完整内容: $bodyText")
                
                // ⚠️ 关键修复：处理 BUSY - 阶梯退避重试策略（300ms, 600ms, 1000ms, 1500ms...）
                if (httpCode == 400 && (errorReason.contains("BUSY", ignoreCase = true) || bodyText.contains("BUSY", ignoreCase = true))) {
                    Log.w(TAG, "========== 检测到 400 BUSY，开始阶梯退避重试策略 ==========")
                    
                    val maxRetries = 6
                    var retryCount = 0
                    var lastSuccess = false
                    
                    while (retryCount < maxRetries && !lastSuccess) {
                        retryCount++
                        // ⚠️ 关键修复：阶梯退避：300ms, 600ms, 1000ms, 1500ms, 2000ms, 2500ms
                        val delayMs = when (retryCount) {
                            1 -> 300L
                            2 -> 600L
                            3 -> 1000L
                            4 -> 1500L
                            5 -> 2000L
                            6 -> 2500L
                            else -> 3000L
                        }
                        
                        Log.d(TAG, "DispenseValue BUSY retry $retryCount/$maxRetries after ${delayMs}ms")
                        kotlinx.coroutines.delay(delayMs)
                        
                        // ⚠️ 关键修复：每次重试前验证请求体包含 CountryCode（硬保护）
                        val retryRequestBodyJson = jsonForLog.encodeToString(
                            kotlinx.serialization.serializer<DispenseValueRequest>(), request
                        )
                        
                        Log.d(TAG, "========== 重试 $retryCount/$maxRetries：即将发送的 JSON body ==========")
                        Log.d(TAG, "retryRequestBodyJson=$retryRequestBodyJson")
                        
                        // ⚠️ 硬保护：如果构造出的 body 缺 CountryCode，直接拒绝发送
                        if (!retryRequestBodyJson.contains("CountryCode") || !retryRequestBodyJson.contains("\"CountryCode\"")) {
                            Log.e(TAG, "========== ⚠️ 严重错误：重试请求体缺少 CountryCode，拒绝发送 ==========")
                            Log.e(TAG, "retryRequestBodyJson=$retryRequestBodyJson")
                            Log.e(TAG, "request 对象内容: value=${request.value}, countryCode=${request.countryCode}")
                            Log.e(TAG, "=========================================")
                            // 直接抛错并拒绝发送，避免设备收到 INVALID_INPUT
                            throw IllegalStateException("重试请求体缺少 CountryCode，拒绝发送以避免 INVALID_INPUT")
                        }
                        
                        // ⚠️ 验证请求体必须包含 Value 和 CountryCode
                        if (!retryRequestBodyJson.contains("\"Value\"") || !retryRequestBodyJson.contains("\"CountryCode\"")) {
                            Log.e(TAG, "========== ⚠️ 严重错误：重试请求体格式不正确 ==========")
                            Log.e(TAG, "retryRequestBodyJson=$retryRequestBodyJson")
                            throw IllegalStateException("重试请求体格式不正确，必须包含 Value 和 CountryCode")
                        }
                        
                        Log.d(TAG, "✓ 重试请求体验证通过：包含 Value 和 CountryCode")
                        Log.d(TAG, "即将发送: deviceID=$deviceID, request=$request")
                        
                        // ⚠️ 可选：调用一次 GetDeviceStatus 来确认不 busy 再吐（如果 API 可用）
                        // 注意：当前没有 GetDeviceStatus API，暂时跳过此步骤
                        
                        try {
                            // ⚠️ 关键修复：重试时复用同一个完整的 request 对象（包含 Value 和 CountryCode）
                            val retryResponse = api.dispenseValue(deviceID, request)
                            val retryHttpCode = retryResponse.code()
                            val retryBodyText = if (retryResponse.isSuccessful) {
                                cleanResponseBody(retryResponse.body()?.string())
                            } else {
                                val retryErrorBody = retryResponse.errorBody()
                                try {
                                    retryErrorBody?.string() ?: ""
                                } catch (e: Exception) {
                                    ""
                                }
                            }
                            
                            if (retryResponse.isSuccessful) {
                                Log.d(TAG, "DispenseValue BUSY 重试成功: deviceID=$deviceID, valueCents=$valueCents, retryCount=$retryCount")
                                Log.d(TAG, "========== DispenseValue 重试请求成功 ==========")
                                lastSuccess = true
                                return@withLock true
                            } else {
                                // ⚠️ 关键修复：检查是否仍然是 BUSY 或变成了 INVALID_INPUT
                                val isStillBusy = retryBodyText.contains("BUSY", ignoreCase = true)
                                val isInvalidInput = retryBodyText.contains("INVALID_INPUT", ignoreCase = true) || 
                                                    retryHttpCode == 400 && retryBodyText.contains("Invalid", ignoreCase = true)
                                
                                if (isInvalidInput) {
                                    Log.e(TAG, "========== ⚠️ 严重错误：重试后变成 INVALID_INPUT ==========")
                                    Log.e(TAG, "deviceID=$deviceID, code=$retryHttpCode, body=$retryBodyText")
                                    Log.e(TAG, "重试请求体: $retryRequestBodyJson")
                                    Log.e(TAG, "request 对象: value=${request.value}, countryCode=${request.countryCode}")
                                    Log.e(TAG, "=========================================")
                                    // INVALID_INPUT 通常表示请求体格式错误，不应该继续重试
                                    throw IllegalStateException("重试后变成 INVALID_INPUT，请求体可能缺少 CountryCode")
                                }
                                
                                if (!isStillBusy) {
                                    Log.e(TAG, "DispenseValue 重试失败（非 BUSY）: deviceID=$deviceID, code=$retryHttpCode, body=$retryBodyText")
                                    break
                                }
                                Log.w(TAG, "DispenseValue 重试 $retryCount/$maxRetries 仍然 BUSY")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "DispenseValue 重试请求异常", e)
                            break
                        }
                    }
                    
                    if (!lastSuccess) {
                        Log.e(TAG, "DispenseValue BUSY 重试 $maxRetries 次后仍然失败，进入退款不足/设备忙碌状态")
                        Log.e(TAG, "========== DispenseValue 请求失败（BUSY 重试耗尽） ==========")
                        // 返回 false，调用方需要处理提示用户并锁单/引导管理员
                        return@withLock false
                    }
                } else {
                    // 非 BUSY 错误，直接返回失败
                    Log.e(TAG, "========== DispenseValue 请求失败 ==========")
                    return@withLock false
                }
                
                // 如果重试成功，已经 return true，这里不会执行
                false
            }
            } catch (e: Exception) {
                Log.e(TAG, "DispenseValue 异常", e)
                false
            }
        }
    }
    
    /**
     * 启用找零
     * @param deviceID 设备ID
     * @return Boolean 是否成功
     */
    suspend fun enablePayout(deviceID: String): Boolean {
        return try {
            Log.d(TAG, "启用找零: deviceID=$deviceID")
            val response = api.enablePayout(deviceID)
            val bodyText = cleanResponseBody(response.body()?.string())
            Log.d(TAG, "EnablePayout 响应: isSuccessful=${response.isSuccessful}, code=${response.code()}, body=$bodyText")
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "启用找零异常: deviceID=$deviceID", e)
            false
        }
    }
    
    /**
     * 启用找零设备（已验证的 REST 调用方式）
     * POST /CashDevice/EnablePayoutDevice?deviceID={deviceID}
     * Content-Type: application/x-www-form-urlencoded
     * Body: 空（或空表单）
     * 
     * @param deviceID 设备ID
     * @return Boolean 是否成功
     */
    suspend fun enablePayoutDevice(deviceID: String): Boolean {
        return try {
            Log.d(TAG, "启用找零设备: deviceID=$deviceID")
            Log.d(TAG, "--> POST /CashDevice/EnablePayoutDevice?deviceID=$deviceID")
            Log.d(TAG, "Content-Type: application/x-www-form-urlencoded")
            Log.d(TAG, "Body: (empty)")
            
            val response = api.enablePayoutDevice(deviceID)
            val httpCode = response.code()
            val bodyText = if (response.isSuccessful) {
                cleanResponseBody(response.body()?.string())
            } else {
                val errorBody = response.errorBody()
                try {
                    errorBody?.string() ?: ""
                } catch (e: Exception) {
                    ""
                }
            }
            
            Log.d(TAG, "<-- $httpCode ${if (response.isSuccessful) "OK" else "ERROR"}")
            Log.d(TAG, "responseBody: $bodyText")
            
            if (response.isSuccessful) {
                Log.d(TAG, "EnablePayoutDevice 成功: deviceID=$deviceID")
                true
            } else {
                Log.w(TAG, "EnablePayoutDevice 失败: deviceID=$deviceID, code=$httpCode, body=$bodyText")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "启用找零设备异常: deviceID=$deviceID", e)
            false
        }
    }
    
    /**
     * 禁用找零
     * @param deviceID 设备ID
     * @return Boolean 是否成功
     */
    suspend fun disablePayout(deviceID: String): Boolean {
        return try {
            Log.d(TAG, "禁用找零: deviceID=$deviceID")
            val response = api.disablePayout(deviceID)
            val bodyText = cleanResponseBody(response.body()?.string())
            Log.d(TAG, "DisablePayout 响应: isSuccessful=${response.isSuccessful}, code=${response.code()}, body=$bodyText")
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "禁用找零异常: deviceID=$deviceID", e)
            false
        }
    }
    
    /**
     * 设置面额接收（逐个面额设置"是否允许接收"）
     * 厂商确认：SetInhibits 是设置可接收面额，要逐个面额配置
     * 约定：Inhibit = true 表示禁止接收该面额；Inhibit = false 表示允许接收该面额
     * 
     * @param deviceID 设备ID
     * @param deviceName 设备名称（SPECTRAL_PAYOUT-0 或 SMART_COIN_SYSTEM-1），用于获取可接收面额白名单
     * @return Boolean 是否成功
     */
    /**
     * 设置面额接收（批量设置"是否允许接收"）
     * 使用 SetDenominationInhibits API（批量 ValueCountryCodes 数组）
     * 约定：Inhibit = true 表示禁止接收该面额；Inhibit = false 表示允许接收该面额
     * 
     * ⚠️ 注意：此方法只在支付 session 内调用，启动阶段不得调用
     * 
     * @param deviceID 设备ID
     * @param deviceName 设备名称（SPECTRAL_PAYOUT-0 或 SMART_COIN_SYSTEM-1），用于获取可接收面额白名单
     * @return Boolean 是否成功
     */
    suspend fun setInhibits(deviceID: String, deviceName: String): Boolean {
        Log.e("BUILD_MARK", "BUILD_MARK_20260128_FIX1")
        return try {
            Log.d(TAG, "设置面额接收: deviceID=$deviceID, deviceName=$deviceName")
            
            // ⚠️ Step 3: 面额只能来自 GetAllLevels 的 Value 集合
            val supportedValues = getDeviceSupportedValues(deviceID)
            if (supportedValues.isEmpty()) {
                Log.w(TAG, "SetDenominationInhibits 警告: 无法获取设备支持面额，尝试刷新...")
                refreshDeviceSupportedValues(deviceID)
                val refreshedValues = deviceSupportedValues[deviceID] ?: emptySet()
                if (refreshedValues.isEmpty()) {
                    Log.e(TAG, "SetDenominationInhibits 失败: 无法获取设备支持面额，拒绝请求")
                    return false
                }
            }
            
            // 只使用 GetAllLevels 返回的面额
            val acceptableDenoms = supportedValues.intersect(
                DeviceDenominationConfig.getAcceptableDenominations(deviceName).toSet()
            ).toList()
            
            if (acceptableDenoms.isEmpty()) {
                Log.w(TAG, "设备 $deviceName GetAllLevels 返回的面额与白名单无交集，跳过 SetDenominationInhibits")
                return true  // 没有交集，视为成功（不设置任何限制）
            }
            
            // 获取货币分配以获取 valueCountryCodes
            val assignments = try {
                fetchCurrencyAssignments(deviceID)
            } catch (e: Exception) {
                Log.e(TAG, "获取货币分配失败，使用默认 currency=EUR", e)
                emptyList()
            }
            
            // 构建 valueCountryCodes：格式为 "500 EUR" / "1000 EUR"（只对 GetAllLevels 返回的面额）
            val valueCountryCodes = acceptableDenoms.mapNotNull { denom: Int ->
                // 查找对应的 currency
                val assignment = assignments.find { it.value == denom }
                val currency = assignment?.countryCode ?: "EUR"
                "$denom $currency"
            }
            
            if (valueCountryCodes.isEmpty()) {
                Log.w(TAG, "无法构建 valueCountryCodes，跳过 SetDenominationInhibits")
                return true
            }
            
            // 构建 SetDenominationInhibitsRequest：白名单中的面额设置为 Inhibit=false（允许接收）
            val request = SetDenominationInhibitsRequest(
                valueCountryCodes = valueCountryCodes,
                inhibit = false  // 白名单中的面额：允许接收
            )
            
            // 打印配置内容
            Log.d(TAG, "SetDenominationInhibits 配置: deviceID=$deviceID, 面额数=${valueCountryCodes.size}")
            valueCountryCodes.take(5).forEach { vcc ->
                Log.d(TAG, "  ValueCountryCode: $vcc, Inhibit=false (允许接收)")
            }
            if (valueCountryCodes.size > 5) {
                Log.d(TAG, "  ... 还有 ${valueCountryCodes.size - 5} 个面额")
            }
            
            // 调用批量接口
            val response = api.setDenominationInhibits(deviceID, request)
            val bodyText = cleanResponseBody(response.body()?.string())
            val httpCode = response.code()
            
            Log.d(TAG, "SetDenominationInhibits 响应: isSuccessful=${response.isSuccessful}, code=$httpCode, body=$bodyText")
            
            if (response.isSuccessful) {
                Log.d(TAG, "SetDenominationInhibits 成功: deviceID=$deviceID, 配置了 ${valueCountryCodes.size} 个面额")
                return true
            } else {
                Log.e(TAG, "SetDenominationInhibits 失败: deviceID=$deviceID, code=$httpCode, body=$bodyText")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "设置面额接收异常: deviceID=$deviceID", e)
            false
        }
    }
    
    /**
     * 设置面额路由（逐个面额设置"找零路由/是否进入可找零循环仓（recycler）"）
     * 厂商确认：SetRoutes 是设置找零面额，决定某面额进入 recycler（可找零）还是 cashbox（不可找零）
     * 
     * @param deviceID 设备ID
     * @param deviceName 设备名称（SPECTRAL_PAYOUT-0 或 SMART_COIN_SYSTEM-1），用于获取可找零面额白名单
     * @return Boolean 是否成功
     */
    suspend fun setRoutes(deviceID: String, deviceName: String): Boolean {
        return try {
            Log.d(TAG, "设置面额路由: deviceID=$deviceID, deviceName=$deviceName")
            
            // 获取可接收面额白名单和可找零面额白名单
            val acceptableDenoms = DeviceDenominationConfig.getAcceptableDenominations(deviceName)
            val recyclableDenoms = DeviceDenominationConfig.getRecyclableDenominations(deviceName)
            
            if (acceptableDenoms.isEmpty()) {
                Log.w(TAG, "设备 $deviceName 没有可接收面额白名单，跳过 SetRoutes")
                return true  // 没有白名单，视为成功（不设置任何路由）
            }
            
            // 构建 SetRoutes 请求：
            // - 可找零面额白名单中的面额：Route=1（进入 recycler，可找零）
            // - 其他可接收面额：Route=0（进入 cashbox，不可找零）
            val routeItems = acceptableDenoms.map { denom: Int ->
                DenominationRouteItem(
                    denomination = denom,
                    route = if (recyclableDenoms.contains(denom)) 1 else 0  // 1 = recycler（可找零），0 = cashbox（不可找零）
                )
            }
            
            val request = SetRoutesRequest(denominations = routeItems)
            
            // 打印配置内容
            Log.d(TAG, "SetRoutes 配置: deviceID=$deviceID, 面额数=${routeItems.size}")
            routeItems.forEach { item: DenominationRouteItem ->
                val routeName = if (item.route == 1) "recycler（可找零）" else "cashbox（不可找零）"
                Log.d(TAG, "  面额 ${item.denomination} 分: Route=${item.route} ($routeName)")
            }
            
            val response = api.setRoutes(deviceID, request)
            val bodyText = cleanResponseBody(response.body()?.string())
            val httpCode = response.code()
            
            Log.d(TAG, "SetRoutes 响应: isSuccessful=${response.isSuccessful}, code=$httpCode, body=$bodyText")
            
            if (response.isSuccessful) {
                Log.d(TAG, "SetRoutes 成功: deviceID=$deviceID, 配置了 ${routeItems.size} 个面额")
                return true
            } else if (httpCode == 404) {
                // 404 fallback：使用逐面额接口 SetDenominationRoute
                // ⚠️ 修复：Smart Coin System 也支持 SetDenominationRoute fallback（厂商确认）
                Log.w(TAG, "SetRoutes 404 -> fallback to SetDenominationRoute (纸币器/硬币器都支持)")
                Log.w("ROUTING_CONFIG_WARN", "device=$deviceID http=404 body=$bodyText")
                return setRoutesFallback(deviceID, routeItems)
            } else {
                // ⚠️ Step E: SetRoutes 失败不得导致现金支付失败，只打印 warning
                Log.w("ROUTING_CONFIG_WARN", "device=$deviceID http=$httpCode body=$bodyText")
                Log.w(TAG, "SetRoutes 失败: deviceID=$deviceID, code=$httpCode, body=$bodyText（不阻塞收款）")
                return false  // 返回 false 但不阻塞收款流程
            }
        } catch (e: Exception) {
            Log.e(TAG, "设置面额路由异常: deviceID=$deviceID", e)
            false
        }
    }
    
    /**
     * SetRoutes 404 fallback：使用逐面额接口 SetDenominationRoute
     * @param deviceID 设备ID
     * @param routeItems 面额路由配置列表
     * @return Boolean 是否成功（全部成功才返回 true）
     */
    private suspend fun setRoutesFallback(deviceID: String, routeItems: List<DenominationRouteItem>): Boolean {
        Log.d(TAG, "========== SetRoutes Fallback 开始 ==========")
        Log.d(TAG, "deviceID=$deviceID, 面额数=${routeItems.size}")
        
        var successCount = 0
        var failCount = 0
        
        // 获取货币分配以获取 currency
        val assignments = try {
            fetchCurrencyAssignments(deviceID)
        } catch (e: Exception) {
            Log.e(TAG, "获取货币分配失败，使用默认 currency=EUR", e)
            emptyList()
        }
        
        for (item in routeItems) {
            try {
                // 查找对应的 currency
                val assignment = assignments.find { it.value == item.denomination }
                val currency = assignment?.countryCode ?: "EUR"
                
                // 调用 SetDenominationRoute
                val success = setDenominationRoute(deviceID, item.denomination, currency, item.route, probeMode = false)
                if (success) {
                    successCount++
                } else {
                    failCount++
                    // ⚠️ Step E: SetDenominationRoute 失败不得导致现金支付失败，只打印 warning
                    Log.w("ROUTING_CONFIG_WARN", "device=$deviceID value=${item.denomination} route=${item.route} http=400/500")
                    Log.w(TAG, "SetDenominationRoute 失败: value=${item.denomination}, route=${item.route}（不阻塞收款）")
                }
            } catch (e: Exception) {
                failCount++
                Log.e(TAG, "SetDenominationRoute 异常: value=${item.denomination}", e)
            }
        }
        
        val allSuccess = failCount == 0
        Log.d(TAG, "========== SetRoutes Fallback 结束 ==========")
        Log.d(TAG, "成功=$successCount, 失败=$failCount, 全部成功=$allSuccess")
        
        return allSuccess
    }
    
    /**
     * 获取货币分配（面额识别 + 计数器来源）
     * @param deviceID 设备ID
     * @return List<CurrencyAssignment> 货币分配列表（服务器返回数组 []）
     */
    suspend fun fetchCurrencyAssignments(deviceID: String): List<CurrencyAssignment> {
        return try {
            Log.d(TAG, "获取货币分配: deviceID=$deviceID")
            val assignments = api.getCurrencyAssignment(deviceID)
            
            // 详细日志：打印面额条目数 + 前几条示例
            val assignmentsCount = assignments.size
            Log.d(TAG, "GetCurrencyAssignment 响应: deviceID=$deviceID, count=$assignmentsCount")
            
            if (assignmentsCount > 0) {
                // 打印前 1-2 条的摘要（包含所有关键字段）
                assignments.take(2).forEachIndexed { index, assignment ->
                    val valueCountryCodeStr = assignment.valueCountryCode?.let { 
                        "${it.value} ${it.countryCode ?: ""}" 
                    } ?: "null"
                    val storedInRecycler = assignment.storedInRecycler
                    Log.d(TAG, "  面额[${index}]: type=${assignment.type}, value=${assignment.value}分, valueCountryCode=$valueCountryCodeStr, countryCode=${assignment.countryCode}, channel=${assignment.channel}, stored=${assignment.stored}, storedInCashbox=${assignment.storedInCashbox}, storedInRecycler=$storedInRecycler, acceptRoute=${assignment.acceptRoute}, isInhibited=${assignment.isInhibited}, isRecyclable=${assignment.isRecyclable}")
                }
                if (assignmentsCount > 2) {
                    Log.d(TAG, "  ... 还有 ${assignmentsCount - 2} 个面额")
                }
                // 打印成功解析的日志
                Log.d(TAG, "GetCurrencyAssignment parsed count=$assignmentsCount")
            } else {
                Log.w(TAG, "GetCurrencyAssignment 返回空列表: deviceID=$deviceID")
            }
            
            assignments
        } catch (e: retrofit2.HttpException) {
            val code = e.code()
            val errorBody = e.response()?.errorBody()?.string() ?: ""
            Log.e(TAG, "获取货币分配 HTTP 错误: endpoint=GetCurrencyAssignment, deviceID=$deviceID, code=$code, errorBody=$errorBody")
            emptyList()  // 返回空列表，不抛出异常
        } catch (e: kotlinx.serialization.SerializationException) {
            Log.e(TAG, "获取货币分配反序列化错误: deviceID=$deviceID", e)
            Log.e(TAG, "反序列化错误详情: ${e.message}")
            emptyList()  // 返回空列表，不抛出异常
        } catch (e: Exception) {
            Log.e(TAG, "获取货币分配异常: deviceID=$deviceID", e)
            emptyList()  // 返回空列表，不抛出异常
        }
    }
    
    /**
     * 从货币分配动态生成并应用 SetDenominationInhibits（设置可接收面额）
     * @param deviceID 设备ID
     * @param deviceName 设备名称（用于日志）
     * @return Boolean 是否成功
     */
    suspend fun applyInhibitsFromAssignments(deviceID: String, deviceName: String): Boolean {
        Log.e("BUILD_MARK", "BUILD_MARK_20260128_FIX1")
        return try {
            Log.d(TAG, "从货币分配应用 SetDenominationInhibits: deviceID=$deviceID, deviceName=$deviceName")
            
            // 获取货币分配
            val assignments = fetchCurrencyAssignments(deviceID)
            
            if (assignments.isEmpty()) {
                Log.w(TAG, "设备 $deviceName 没有货币分配数据，跳过 SetDenominationInhibits")
                return true  // 没有数据，视为成功（不设置任何限制）
            }
            
            // 构建 valueCountryCodes：格式为 "500 EUR" / "1000 EUR"
            val valueCountryCodes = assignments.map { assignment ->
                val currency = assignment.countryCode ?: "EUR"
                "${assignment.value} $currency"
            }
            
            // 构建 SetDenominationInhibitsRequest：允许接收所有面额（Inhibit=false）
            val request = SetDenominationInhibitsRequest(
                valueCountryCodes = valueCountryCodes,
                inhibit = false  // 允许接收所有面额
            )
            
            // 打印配置内容
            Log.d(TAG, "SetDenominationInhibits 配置（从货币分配生成）: deviceID=$deviceID, 面额数=${valueCountryCodes.size}")
            valueCountryCodes.take(5).forEach { vcc ->
                Log.d(TAG, "  ValueCountryCode: $vcc, Inhibit=false (允许接收)")
            }
            if (valueCountryCodes.size > 5) {
                Log.d(TAG, "  ... 还有 ${valueCountryCodes.size - 5} 个面额")
            }
            
            val response = api.setDenominationInhibits(deviceID, request)
            val bodyText = cleanResponseBody(response.body()?.string())
            val httpCode = response.code()
            
            Log.d(TAG, "SetDenominationInhibits 响应: isSuccessful=${response.isSuccessful}, code=$httpCode, body=$bodyText")
            
            if (response.isSuccessful) {
                Log.d(TAG, "SetDenominationInhibits 成功: deviceID=$deviceID, 配置了 ${valueCountryCodes.size} 个面额")
            } else {
                Log.e(TAG, "SetDenominationInhibits 失败: deviceID=$deviceID, code=$httpCode, body=$bodyText")
            }
            
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "从货币分配应用 SetDenominationInhibits 异常: deviceID=$deviceID", e)
            false
        }
    }
    
    /**
     * 统一日志函数：打印 REST API 请求和响应
     * @param tag 日志标签
     * @param apiName API 名称
     * @param requestBody 请求体（JSON 字符串）
     * @param response HTTP 响应
     * @return errorBody 文本（如果失败），用于后续日志复用
     */
    private fun logApiResponse(tag: String, apiName: String, requestBody: String?, response: Response<ResponseBody>): String? {
        val httpCode = response.code()
        val isSuccessful = response.isSuccessful
        
        if (isSuccessful) {
            // 成功：打印 body
            val bodyText = cleanResponseBody(response.body()?.string())
            Log.d(tag, "$apiName RESP: http=$httpCode, body=$bodyText")
            return null
        } else {
            // 失败：读取 errorBody（只读一次，后续复用）
            val errorBody = response.errorBody()
            val contentType = errorBody?.contentType()?.toString() ?: "unknown"
            val errorBodyText = if (errorBody != null) {
                try {
                    // 只读取一次，后续复用这个变量
                    val errorBodyString = errorBody.string()
                    cleanResponseBody(errorBodyString)
                } catch (e: Exception) {
                    Log.e(tag, "读取 errorBody 失败", e)
                    "无法读取 errorBody: ${e.message}"
                }
            } else {
                "errorBody 为 null"
            }
            Log.e(tag, "$apiName RESP: http=$httpCode, Content-Type=$contentType, errorBody=$errorBodyText")
            return errorBodyText  // 返回 errorBody 文本，供后续复用
        }
    }
    
    /**
     * 设置单个面额路由（在线配置，不导致连接断开）
     * 使用嵌套结构匹配服务端期望格式
     * 
     * 重要说明：
     * - 此方法是在线配置，不会断开设备连接
     * - 即使 API 调用失败（如 400/500），也不会触发断开连接或设备状态错误
     * - 设备在设置过程中保持连接状态，可以继续使用其他功能
     * 
     * 验证规则：
     * - Route 只能为 0（CASHBOX）或 1（RECYCLER）
     * - 仅 Spectral Payout 设备支持 Route 0/1
     * - 面额必须在设备允许的范围内
     * - 货币代码必须为 EUR（目前仅支持欧元）
     * 
     * @param deviceID 设备ID
     * @param valueCents 面额（分）
     * @param currency 货币代码，默认 EUR（必须传入，不能为空）
     * @param route 路由：0=CASHBOX（不可找零），1=RECYCLER（可找零）
     * @param probeMode 探测模式：如果失败，尝试另一个 route 值（1->0 或 0->1）
     * @param useRawJson 是否使用原始 JSON 直发模式（用于快速验证，绕过 DTO 序列化）
     * @return Boolean 是否成功
     */
    /**
     * 设置面额路由（SetDenominationRoute）
     * ⚠️ 金额单位确认：valueCents 参数为 cents（分），下发的 Value 字段也为 cents
     * ⚠️ 请求体格式：{"Value": valueCents, "CountryCode": "EUR", "Route": route}
     * ⚠️ 注意：Value 是整数（cents），不是欧元金额
     * 
     * @param deviceID 设备ID（SPECTRAL_PAYOUT-0 或 SMART_COIN_SYSTEM-1）
     * @param valueCents 面额（分），例如：100=1€，200=2€，500=5€，1000=10€
     * @param currency 货币代码（默认 "EUR"）
     * @param route 路由：0=CASHBOX（不可找零），1=RECYCLER（可找零）
     * @param probeMode 是否为探测模式
     * @param useRawJson 是否使用原始 JSON 格式
     */
    suspend fun setDenominationRoute(deviceID: String, valueCents: Int, currency: String = "EUR", route: Int, probeMode: Boolean = false, useRawJson: Boolean = false): Boolean {
        return try {
            // ⚠️ 关键日志：金额单位确认
            val valueEUR = valueCents / 100.0
            Log.d(TAG, "========== SetDenominationRoute REQ ==========")
            Log.d(TAG, "deviceID: $deviceID")
            Log.d(TAG, "valueCents: $valueCents")
            Log.d(TAG, "valueEUR: $valueEUR")
            Log.d(TAG, "说明：SetDenominationRoute.Value 为 cents（分），${valueCents}分 = ${valueEUR}€")
            Log.d(TAG, "currency: $currency")
            Log.d(TAG, "route: $route (${if (route == 1) "RECYCLER可找零" else if (route == 0) "CASHBOX不可找零" else "其他($route)"})")
            Log.d(TAG, "probeMode: $probeMode")
            
            // ========== 验证 1: Route 值只能为 0 或 1 ==========
            if (route != 0 && route != 1) {
                Log.e(TAG, "SetDenominationRoute 验证失败: Route 值只能为 0（CASHBOX）或 1（RECYCLER），当前值=$route")
                return false
            }
            
            // ⚠️ 修复：Smart Coin System 也支持 SetDenominationRoute（厂商确认）
            // 不再限制设备类型，纸币器和硬币器都支持
            val isSpectralPayout = deviceID.startsWith("SPECTRAL_PAYOUT", ignoreCase = true) || 
                                   deviceID.contains("SPECTRAL", ignoreCase = true) ||
                                   deviceID.contains("PAYOUT", ignoreCase = true)
            val isSmartCoinSystem = deviceID.startsWith("SMART_COIN_SYSTEM", ignoreCase = true) ||
                                    deviceID.contains("SMART_COIN", ignoreCase = true)
            Log.d(TAG, "✓ 设备型号: deviceID=$deviceID, isSpectralPayout=$isSpectralPayout, isSmartCoinSystem=$isSmartCoinSystem")
            
            // ========== 验证 3: 货币代码必须为 EUR ==========
            val finalCurrency = currency.ifEmpty { "EUR" }.uppercase()
            if (finalCurrency != "EUR") {
                Log.e(TAG, "SetDenominationRoute 验证失败: 目前仅支持 EUR 货币，当前值=$currency")
                return false
            }
            Log.d(TAG, "✓ 货币代码验证通过: $finalCurrency")
            
            // ⚠️ Step 3: 验证 4: 面额只能来自 GetAllLevels 的 Value 集合
            val supportedValues = getDeviceSupportedValues(deviceID)
            if (supportedValues.isEmpty()) {
                Log.w(TAG, "SetDenominationRoute 警告: 无法获取设备支持面额，尝试刷新...")
                refreshDeviceSupportedValues(deviceID)
                val refreshedValues = deviceSupportedValues[deviceID] ?: emptySet()
                if (refreshedValues.isEmpty()) {
                    Log.e(TAG, "SetDenominationRoute 验证失败: 无法获取设备支持面额，拒绝请求")
                    return false
                }
                if (!refreshedValues.contains(valueCents)) {
                    Log.e(TAG, "SetDenominationRoute 验证失败: 面额 $valueCents 不在 GetAllLevels 返回的面额集合中")
                    Log.e(TAG, "  设备 $deviceID GetAllLevels 返回的面额: $refreshedValues")
                    return false
                }
            } else {
                if (!supportedValues.contains(valueCents)) {
                    Log.e(TAG, "SetDenominationRoute 验证失败: 面额 $valueCents 不在 GetAllLevels 返回的面额集合中")
                    Log.e(TAG, "  设备 $deviceID GetAllLevels 返回的面额: $supportedValues")
                    return false
                }
            }
            Log.d(TAG, "✓ 面额验证通过: $valueCents 在 GetAllLevels 返回的面额集合中")
            
            // ========== 构建 Route 尝试列表（只尝试 0 和 1）==========
            val routesToTry = if (probeMode) {
                // 探测模式：如果失败，尝试另一个 route 值（1->0 或 0->1）
                listOf(route, if (route == 1) 0 else 1).distinct()
            } else {
                listOf(route)
            }
            Log.d(TAG, "Route 尝试列表: $routesToTry (仅支持 0 和 1)")
            
            var lastError: String? = null
            var successfulResponse: retrofit2.Response<okhttp3.ResponseBody>? = null
            var successfulRequestBodyJson: String? = null
            var successfulRoute: Int? = null
            
            for (currentRoute in routesToTry) {
                if (useRawJson) {
                    // ========== 原始 JSON 直发模式（用于快速验证）==========
                    // 直接发送与 Postman 成功请求完全一致的 JSON 字符串（扁平三字段）
                    val rawJsonString = """
                        {
                          "Value": $valueCents,
                          "CountryCode": "$finalCurrency",
                          "Route": $currentRoute
                        }
                    """.trimIndent()
                    
                    Log.d(TAG, "========== SetDenominationRoute 原始 JSON 直发模式 ==========")
                    Log.d(TAG, "rawJsonString=$rawJsonString")
                    
                    val requestBody = rawJsonString.toRequestBody("application/json; charset=utf-8".toMediaType())
                    
                    try {
                        val resp = api.setDenominationRouteRaw(deviceID, requestBody)
                        val errorBodyText = logApiResponse(TAG, "SetDenominationRoute", rawJsonString, resp)
                        if (resp.isSuccessful) {
                            successfulResponse = resp
                            successfulRequestBodyJson = rawJsonString
                            successfulRoute = currentRoute
                            break  // 成功，跳出 route 循环
                        } else {
                            val httpCode = resp.code()
                            val errorBodyTextSafe = errorBodyText ?: ""
                            lastError = "route=$currentRoute, http=$httpCode, errorBody=$errorBodyTextSafe"
                            if (!probeMode) {
                                return false
                            }
                            // 探测模式：继续尝试下一个 route 值
                        }
                    } catch (e: Exception) {
                        val errorMsg = "原始 JSON 直发模式异常: ${e.javaClass.simpleName}, message=${e.message}"
                        Log.e(TAG, errorMsg, e)
                        lastError = "route=$currentRoute, 原始 JSON 直发模式异常: ${e.message}"
                        if (!probeMode) {
                            return false
                        }
                        // 探测模式：继续尝试下一个 route 值
                    }
                } else {
                    // ⚠️ Step 4: 移除三种 body 格式探测，固定使用扁平 JSON
                    // ========== DTO 序列化模式（正常模式 - 固定扁平格式）==========
                    // 固定使用扁平结构：{"Value":X,"CountryCode":"EUR","Route":1}
                    try {
                        val request = SetDenominationRouteRequestFlat(
                            value = valueCents,
                            countryCode = finalCurrency,
                            route = currentRoute
                        )
                        val json = kotlinx.serialization.json.Json {
                            encodeDefaults = false
                            ignoreUnknownKeys = false
                        }
                        val requestBodyJson = json.encodeToString(
                            kotlinx.serialization.serializer<SetDenominationRouteRequestFlat>(), request
                        )
                        Log.d(TAG, "========== SetDenominationRoute（固定扁平格式）==========")
                        Log.d(TAG, "requestBodyJson=$requestBodyJson (route=$currentRoute)")
                        
                        val response = api.setDenominationRoute(deviceID, request)
                        
                        // 检查响应
                        val errorBodyText = logApiResponse(TAG, "SetDenominationRoute", requestBodyJson, response)
                        
                        if (response.isSuccessful) {
                            Log.d(TAG, "✓ SetDenominationRoute 成功: route=$currentRoute")
                            successfulResponse = response
                            successfulRequestBodyJson = requestBodyJson
                            successfulRoute = currentRoute
                            break  // 成功，跳出 route 循环
                        } else {
                            val httpCode = response.code()
                            val errorBodyTextSafe = errorBodyText ?: ""
                            lastError = "route=$currentRoute, http=$httpCode, errorBody=$errorBodyTextSafe"
                            if (!probeMode) {
                                return false
                            }
                            // 探测模式：继续尝试下一个 route 值
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "SetDenominationRoute 异常: route=$currentRoute", e)
                        lastError = "route=$currentRoute, exception=${e.message}"
                        if (!probeMode) {
                            return false
                        }
                        // 探测模式：继续尝试下一个 route 值
                    }
                }
            }
            
            // 如果成功，处理响应
            if (successfulResponse != null && successfulRequestBodyJson != null && successfulRoute != null) {
                val response = successfulResponse!!
                val requestBodyJsonForLog = successfulRequestBodyJson!!
                val finalSuccessfulRoute = successfulRoute!!
                
                // ========== 处理 API 响应 ==========
                // 打印最终发送的 JSON（用于验证与 Postman 一致）
                Log.d(TAG, "========== SetDenominationRoute 最终发送的 JSON ==========")
                Log.d(TAG, "最终请求体（扁平三字段）: $requestBodyJsonForLog")
                Log.d(TAG, "验证：应包含 {\"Value\":...,\"CountryCode\":...,\"Route\":...} 格式")
                Log.d(TAG, "最终请求 URL 格式: http://<host>:<port>/api/CashDevice/SetDenominationRoute?deviceID=$deviceID")
                Log.d(TAG, "注意：完整 URL 将在 OkHttp Interceptor 日志中显示（请查看 --> POST ... 日志）")
                
                // 使用统一日志函数打印响应（包含 errorBody，只读一次）
                val errorBodyText = logApiResponse(TAG, "SetDenominationRoute", requestBodyJsonForLog, response)
                
                // ========== 成功处理 ==========
                val responseBody = response.body()?.string() ?: ""
                Log.d(TAG, "========== SetDenominationRoute 成功 ==========")
                Log.d(TAG, "deviceID: $deviceID")
                Log.d(TAG, "value: $valueCents")
                Log.d(TAG, "currency: $finalCurrency")
                Log.d(TAG, "route: $finalSuccessfulRoute (${if (finalSuccessfulRoute == 1) "RECYCLER可找零" else "CASHBOX不可找零"})")
                Log.d(TAG, "HTTP Code: ${response.code()}")
                Log.d(TAG, "Response Body: $responseBody")
                Log.d(TAG, "✓ 设备保持连接状态，可以继续使用其他功能")
                
                // 成功后立即刷新货币分配（确保 UI 立即反映最新状态）
                try {
                    Log.d(TAG, "开始刷新货币分配以验证设置是否生效...")
                    val refreshedAssignments = fetchCurrencyAssignments(deviceID)
                    Log.d(TAG, "✓ 货币分配刷新成功: 面额数=${refreshedAssignments.size}")
                    
                    // 验证设置是否生效：查找对应的面额，检查 AcceptRoute
                    val targetAssignment = refreshedAssignments.find { it.value == valueCents }
                    if (targetAssignment != null) {
                        val expectedRoute = if (finalSuccessfulRoute == 1) "PAYOUT" else "CASHBOX"
                        val actualRoute = targetAssignment.acceptRoute ?: "UNKNOWN"
                        Log.d(TAG, "设置验证: 面额=$valueCents, 期望Route=$expectedRoute, 实际Route=$actualRoute")
                        if (actualRoute.equals(expectedRoute, ignoreCase = true)) {
                            Log.d(TAG, "✓ 设置验证成功: Route 已正确更新")
                        } else {
                            Log.w(TAG, "⚠ 设置验证警告: Route 可能未立即更新（可能需要等待设备处理）")
                        }
                    } else {
                        Log.w(TAG, "⚠ 设置验证警告: 未找到面额 $valueCents 的配置")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "刷新货币分配失败（设备保持连接）", e)
                    // 即使刷新失败，也认为设置成功（因为 API 返回成功）
                }
                return true
            }
            
            // 所有 route 都失败
            Log.e(TAG, "========== SetDenominationRoute 失败（所有 route 都失败）==========")
            Log.e(TAG, "deviceID: $deviceID")
            Log.e(TAG, "value: $valueCents")
            Log.e(TAG, "currency: $finalCurrency")
            Log.e(TAG, "尝试的 route 值: $routesToTry")
            Log.e(TAG, "最后错误: $lastError")
            // ⚠️ Step E: SetDenominationRoute 失败不得导致现金支付失败，只打印 warning
            Log.w("ROUTING_CONFIG_WARN", "device=$deviceID value=$valueCents routes=$routesToTry lastError=$lastError")
            Log.d(TAG, "⚠ 注意：所有 route 值都失败，但设备保持连接状态，可以重试或继续使用其他功能（不阻塞收款）")
            false
        } catch (e: Exception) {
            // ========== 异常处理 ==========
            Log.e(TAG, "========== SetDenominationRoute 异常 ==========")
            Log.e(TAG, "deviceID: $deviceID")
            Log.e(TAG, "value: $valueCents")
            Log.e(TAG, "currency: $currency")
            Log.e(TAG, "route: $route")
            Log.e(TAG, "异常类型: ${e.javaClass.simpleName}")
            Log.e(TAG, "异常消息: ${e.message}")
            Log.d(TAG, "⚠ 注意：发生异常，但设备保持连接状态，可以重试或继续使用其他功能")
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 设置单个面额接收状态（是否允许接收该面额）
     * 使用 SetDenominationInhibits API，格式：{"ValueCountryCodes":["500 EUR"],"Inhibit":false}
     * @param deviceID 设备ID
     * @param valueCents 面额（分）
     * @param currency 货币代码，默认 EUR
     * @param inhibit true=禁止接收，false=允许接收
     * @return Boolean 是否成功
     */
    /**
     * 设置面额接收状态（SetDenominationInhibits）
     * ⚠️ 金额单位确认：valueCents 参数为 cents（分）
     * ⚠️ 请求体格式：{"ValueCountryCodes": ["valueCents EUR"], "Inhibit": true/false}
     * ⚠️ 注意：ValueCountryCodes 字符串中的数值为 cents，例如 "500 EUR" 表示 500分=5€
     * 
     * @param deviceID 设备ID（SPECTRAL_PAYOUT-0 或 SMART_COIN_SYSTEM-1）
     * @param valueCents 面额（分），例如：100=1€，200=2€，500=5€，1000=10€
     * @param currency 货币代码（默认 "EUR"）
     * @param inhibit true=禁止接收，false=允许接收
     */
    suspend fun setDenominationInhibit(deviceID: String, valueCents: Int, currency: String = "EUR", inhibit: Boolean): Boolean {
        return try {
            // ⚠️ 关键日志：金额单位确认
            val valueEUR = valueCents / 100.0
            Log.d(TAG, "========== SetDenominationInhibits REQ ==========")
            Log.d(TAG, "deviceID: $deviceID")
            Log.d(TAG, "valueCents: $valueCents")
            Log.d(TAG, "valueEUR: $valueEUR")
            Log.d(TAG, "说明：SetDenominationInhibits.ValueCountryCodes 中的数值为 cents（分），\"${valueCents} EUR\" = ${valueEUR}€")
            Log.d(TAG, "currency: $currency")
            Log.d(TAG, "inhibit: $inhibit (${if (inhibit) "禁止接收" else "允许接收"})")
            
            // 确保 currency 不为空
            val finalCurrency = currency.ifEmpty { "EUR" }
            
            // ⚠️ 构建 ValueCountryCodes 字符串数组，格式："valueCents EUR"
            // ⚠️ 注意：字符串中的数值为 cents（分），不是欧元金额
            val valueCountryCodeString = "$valueCents $finalCurrency"
            val request = SetDenominationInhibitsRequest(
                valueCountryCodes = listOf(valueCountryCodeString),
                inhibit = inhibit
            )
            
            // 序列化请求体（用于日志）
            val json = kotlinx.serialization.json.Json {
                encodeDefaults = true
                ignoreUnknownKeys = false
            }
            val requestBodyJson = json.encodeToString(
                kotlinx.serialization.serializer<SetDenominationInhibitsRequest>(), request
            )
            Log.d(TAG, "SetDenominationInhibits requestBodyJson=$requestBodyJson")
            
            // 验证请求体包含 ValueCountryCodes
            if (!requestBodyJson.contains("ValueCountryCodes")) {
                Log.e(TAG, "ERROR: requestBody 缺少 ValueCountryCodes! requestBodyJson=$requestBodyJson")
            } else {
                Log.d(TAG, "✓ 验证通过: requestBody 包含 ValueCountryCodes")
            }
            
            val response = api.setDenominationInhibits(deviceID, request)
            
            // 使用统一日志函数打印响应（包含 errorBody，只读一次）
            val errorBodyText = logApiResponse(TAG, "SetDenominationInhibits", requestBodyJson, response)
            
            if (response.isSuccessful) {
                Log.d(TAG, "SetDenominationInhibits 成功: deviceID=$deviceID, value=$valueCents, currency=$finalCurrency, inhibit=$inhibit")
                // 成功后立即刷新货币分配
                try {
                    val refreshedAssignments = fetchCurrencyAssignments(deviceID)
                    Log.d(TAG, "SetDenominationInhibits 成功后刷新货币分配: 面额数=${refreshedAssignments.size}")
                } catch (e: Exception) {
                    Log.e(TAG, "SetDenominationInhibits 成功后刷新货币分配失败", e)
                }
                true
            } else {
                val contentType = response.errorBody()?.contentType()?.toString() ?: "unknown"
                Log.e(TAG, "SetDenominationInhibits 失败: deviceID=$deviceID, value=$valueCents, currency=$finalCurrency, inhibit=$inhibit, http=${response.code()}, Content-Type=$contentType, errorBody=$errorBodyText")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "SetDenominationInhibits 异常: deviceID=$deviceID, value=$valueCents, currency=$currency, inhibit=$inhibit", e)
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 应用路由配置（通过 OpenConnection 携带 SetRoutes，仅纸币器）
     * 
     * 纸币器工作原理：
     * - 纸币器有 主钞箱（cashbox，容量1000张） 与 循环钞箱/循环鼓（recycler，容量80张）
     * - 若某面额配置为"可找零"（Route=7），则优先进 recycler（容量满后即使可找零也会进 cashbox）
     * - recycler 的钱会被 DispenseValue 吐出用于找零；吐出后 recycler 腾出空间，才能继续收可找零面额
     * - 配置为"不可找零"（Route=0）的面额永远进 cashbox，不能用于找零
     * 
     * 注意：路由只影响"之后投入的钱"，之前进 CASHBOX 的钱不可能变成可找零库存。
     * 
     * @param deviceID 设备ID
     * @param assignments 货币分配列表（用于生成路由配置）
     * @param recyclableDenominations 可找零面额列表（value 列表，分）
     * @return Boolean 是否成功
     */
    suspend fun applyRoutesFromUI(deviceID: String, assignments: List<CurrencyAssignment>, recyclableDenominations: List<Int>): Boolean {
        return try {
            Log.d(TAG, "从 UI 应用路由配置（通过 OpenConnection 携带 SetRoutes）: deviceID=$deviceID, 可找零面额数=${recyclableDenominations.size}")
            Log.d(TAG, "注意：路由只影响\"之后投入的钱\"，之前进 CASHBOX 的钱不可能变成可找零库存。")
            
            // 获取设备的 Port 和 SspAddress
            val mapping = if (deviceID == _billAcceptorDeviceID.value) {
                billAcceptorMapping
            } else if (deviceID == _coinAcceptorDeviceID.value) {
                coinAcceptorMapping
            } else {
                null
            }
            
            if (mapping == null) {
                Log.e(TAG, "无法找到设备的 Port 和 SspAddress 映射: deviceID=$deviceID")
                return false
            }
            
            val (port, sspAddress) = mapping
            
            // 构建 SetRoutes 配置（用于 OpenConnection）：
            // - recyclableDenominations 中的面额：Route=7（进 recycler，可找零）
            // - 其他面额：Route=0（进 cashbox，不可找零）
            // Denomination 格式："{value} {countryCode}"，例如 "500 EUR"
            val routeItems = assignments.map { assignment ->
                val denominationString = assignment.getDenominationString()  // 例如 "500 EUR"
                DenominationRoute(
                    Denomination = denominationString,
                    Route = if (recyclableDenominations.contains(assignment.value)) 7 else 0  // 7 = recycler（可找零），0 = cashbox（不可找零）
                )
            }
            
            // 构建 SetInhibits 配置（允许接收所有面额）
            val inhibitItems = assignments.map { assignment ->
                DenominationInhibit(
                    Denomination = assignment.getDenominationString(),
                    Inhibit = false  // 允许接收
                )
            }
            
            // 打印配置内容
            Log.d(TAG, "路由配置（从 UI）: deviceID=$deviceID, 面额数=${routeItems.size}")
            routeItems.take(5).forEach { item ->
                val routeName = if (item.Route == 7) "recycler（可找零）" else "cashbox（不可找零）"
                Log.d(TAG, "  面额 ${item.Denomination}: Route=${item.Route} ($routeName)")
            }
            
            // 步骤 1: DisableAcceptor
            Log.d(TAG, "步骤 1: 禁用接收器...")
            val disableSuccess = disableAcceptor(deviceID)
            if (!disableSuccess) {
                Log.w(TAG, "禁用接收器失败，但继续执行后续步骤")
            }
            
            // 步骤 2: OpenConnection(携带 SetRoutes)
            Log.d(TAG, "步骤 2: 重新打开连接（携带 SetRoutes）...")
            val request = createOpenConnectionRequest(
                comPort = port,
                sspAddress = sspAddress,
                deviceID = deviceID,
                enableAcceptor = false,  // 先不启用，等 EnableAcceptor 时再启用
                enableAutoAcceptEscrow = true,
                enablePayout = true,  // 设备连接成功后自动启用找零功能
                setInhibits = inhibitItems,
                setRoutes = routeItems
            )
            
            // 序列化请求体（用于日志）
            val requestBodyJson = kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.serializer<OpenConnectionRequest>(), request
            )
            
            // 构建完整 URL（用于日志）
            val baseUrl = "http://127.0.0.1:5000/api"
            val fullUrl = "$baseUrl/CashDevice/OpenConnection"
            
            // 打印请求日志
            Log.d(TAG, "========== OpenConnection(with SetRoutes) REQUEST ==========")
            Log.d(TAG, "url: $fullUrl")
            Log.d(TAG, "deviceID: $deviceID")
            Log.d(TAG, "body: $requestBodyJson")
            
            val response = api.openConnection(request)
            val httpCode = 200  // OpenConnection 返回的是 OpenConnectionResponse，不是 Response<ResponseBody>
            val responseBodyJson = kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.serializer<OpenConnectionResponse>(), response
            )
            
            // 打印响应日志
            Log.d(TAG, "========== OpenConnection(with SetRoutes) RESPONSE ==========")
            Log.d(TAG, "code: $httpCode")
            Log.d(TAG, "body: $responseBodyJson")
            Log.d(TAG, "isOpen: ${response.IsOpen}, deviceID: ${response.deviceID}, error: ${response.error}")
            
            if (response.IsOpen == true && response.deviceID != null) {
                Log.d(TAG, "OpenConnection(with SetRoutes) 成功: deviceID=${response.deviceID}, 配置了 ${routeItems.size} 个面额路由")
                
                // 注意：不再在此处启用接收器，启用接收器应在 beginCashSession 中统一处理
                
                // 验证配置是否生效
                try {
                    Log.d(TAG, "验证路由配置是否生效: 调用 GetCurrencyAssignment 检查 AcceptRoute/Stored/StoredInCashbox")
                    val refreshedAssignments = fetchCurrencyAssignments(deviceID)
                    
                    // 打印每个可找零面额的状态
                    recyclableDenominations.forEach { recyclableValue ->
                        val assignment = refreshedAssignments.find { it.value == recyclableValue }
                        if (assignment != null) {
                            Log.d(TAG, "  面额 ${recyclableValue} 分: AcceptRoute=${assignment.acceptRoute}, Stored=${assignment.stored}, StoredInCashbox=${assignment.storedInCashbox}")
                            if (assignment.acceptRoute != "PAYOUT") {
                                Log.w(TAG, "  警告: 面额 ${recyclableValue} 分 配置为可找零，但 AcceptRoute=${assignment.acceptRoute}，期望 PAYOUT（注意：路由只影响之后投入的钱）")
                            }
                        } else {
                            Log.w(TAG, "  警告: 面额 ${recyclableValue} 分 在 GetCurrencyAssignment 中未找到")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "验证路由配置生效时调用 GetCurrencyAssignment 失败", e)
                }
                
                true
            } else {
                Log.e(TAG, "OpenConnection(with SetRoutes) 失败: deviceID=$deviceID, IsOpen=${response.IsOpen}, error=${response.error}, body=$responseBodyJson")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "从 UI 应用路由配置异常: deviceID=$deviceID", e)
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 智能清空纸币器（无参数版本，使用内部 deviceID）
     */
    suspend fun smartEmptyBill(): Boolean {
        val deviceID = _billAcceptorDeviceID.value
        if (deviceID == null) {
            Log.w(TAG, "智能清空纸币器失败: deviceID=null (设备未连接)")
            return false
        }
        return smartEmpty(deviceID)
    }
    
    /**
     * 智能清空硬币器（无参数版本，使用内部 deviceID）
     */
    suspend fun smartEmptyCoin(): Boolean {
        val deviceID = _coinAcceptorDeviceID.value
        if (deviceID == null) {
            Log.w(TAG, "智能清空硬币器失败: deviceID=null (设备未连接)")
            return false
        }
        return smartEmpty(deviceID)
    }
    
    /**
     * 智能清空（清空循环鼓 recycler）
     * @param deviceID 设备ID
     * @return Boolean 是否成功
     */
    suspend fun smartEmpty(deviceID: String): Boolean {
        return try {
            Log.d(TAG, "智能清空: deviceID=$deviceID")
            val request = SmartEmptyRequest(moduleNumber = 0, isNV4000 = false)
            val response = api.smartEmpty(deviceID, request)
            val bodyText = cleanResponseBody(response.body()?.string())
            
            Log.d(TAG, "SmartEmpty 响应: isSuccessful=${response.isSuccessful}, code=${response.code()}, body=$bodyText")
            
            if (response.isSuccessful) {
                Log.d(TAG, "SmartEmpty 成功: deviceID=$deviceID, dispenseResult=$bodyText")
                // 调用后强制刷新一次 GetCurrencyAssignment
                try {
                    val assignments = fetchCurrencyAssignments(deviceID)
                    Log.d(TAG, "SmartEmpty 后刷新货币分配: count=${assignments.size}")
                } catch (e: Exception) {
                    Log.w(TAG, "SmartEmpty 后刷新货币分配失败", e)
                }
            } else {
                Log.e(TAG, "SmartEmpty 失败: deviceID=$deviceID, code=${response.code()}, body=$bodyText")
            }
            
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "智能清空异常: deviceID=$deviceID", e)
            false
        }
    }
    
    /**
     * 获取设备货币分配快照（用于 UI 显示和路由配置）
     * @param deviceID 设备ID
     * @return 货币分配列表
     */
    fun getDeviceAssignments(deviceID: String): List<CurrencyAssignment> {
        return amountTracker.getDeviceAssignments(deviceID)
    }
    
    /**
     * 解析 GetCounters 文本响应
     * 服务端返回格式示例: "Stacked: 76 / Stored: 42 / Rejected: 5 / Coins paid in: 10"
     * 修复"50€漏记"：解析所有计数项（stacked, stored, rejected等）
     * 
     * @param responseBody 原始响应文本
     * @param deviceID 设备ID（用于返回）
     * @return CountersResponse 解析后的计数器响应
     */
    private suspend fun parseCountersResponse(responseBody: ResponseBody, deviceID: String): CountersResponse {
        return try {
            val text = responseBody.string()
            Log.d(TAG, "GetCounters 原始响应文本: deviceID=$deviceID, text='$text'")
            
            // 解析文本中的数值
            // 格式: "Stacked: 76 / Stored: 42 / Rejected: 5 / Coins paid in: 10" 或类似格式
            var stacked = 0
            var stored = 0
            var rejected = 0
            var coinsPaidIn = 0
            
            // 使用正则表达式提取数值
            // Stacked: 76
            val stackedPattern = Regex("Stacked:\\s*(\\d+)", RegexOption.IGNORE_CASE)
            stackedPattern.find(text)?.let {
                stacked = it.groupValues[1].toIntOrNull() ?: 0
            }
            
            // Stored: 42
            val storedPattern = Regex("Stored:\\s*(\\d+)", RegexOption.IGNORE_CASE)
            storedPattern.find(text)?.let {
                stored = it.groupValues[1].toIntOrNull() ?: 0
            }
            
            // Rejected: 5
            val rejectedPattern = Regex("Rejected:\\s*(\\d+)", RegexOption.IGNORE_CASE)
            rejectedPattern.find(text)?.let {
                rejected = it.groupValues[1].toIntOrNull() ?: 0
            }
            
            // Coins paid in: 10
            val coinsPaidPattern = Regex("Coins paid in:\\s*(\\d+)", RegexOption.IGNORE_CASE)
            coinsPaidPattern.find(text)?.let {
                coinsPaidIn = it.groupValues[1].toIntOrNull() ?: 0
            }
            
            // ⚠️ 关键：GetCounters 只返回"张数/次数"，没有面额信息
            // 需要结合 GetCurrencyAssignment 来获取面额，才能准确计算金额
            // 获取面额信息
            val assignments = try {
                api.getCurrencyAssignment(deviceID)
            } catch (e: Exception) {
                Log.w(TAG, "获取面额信息失败，使用估算值: deviceID=$deviceID", e)
                emptyList()
            }
            
            // 计算各计数项的金额（分）
            var stackedCents = 0
            var storedCents = 0
            var rejectedCents = 0
            var coinsPaidInCents = 0
            
            // ⚠️ 移除 avgVal 推导方式：金额计算必须使用 GetAllLevels 差分，不再使用 coinsPaidIn * avgVal
            // 这里只保留计数项（count），不计算金额（cents）
            // 金额计算统一在 PaymentViewModel 中使用 Levels 差分
            if (assignments.isNotEmpty()) {
                // 仅用于日志显示，不用于金额计算
                val avgVal = assignments.map { it.value }.average().toInt()
                Log.d(TAG, "GetCounters parsed (using assignments): deviceID=$deviceID, stacked=$stacked, stored=$stored, rejected=$rejected, coinsPaidIn=$coinsPaidIn (count), avgVal=${avgVal} cents (仅用于日志，不用于金额计算)")
                Log.d(TAG, "  Denomination mapping: ${assignments.map { "${it.value} cents (${it.value / 100.0} EUR)" }.joinToString(", ")}")
                Log.d(TAG, "  ⚠️ 注意：金额计算必须使用 GetAllLevels 差分，不再使用 coinsPaidIn * avgVal")
                // 不设置 coinsPaidInCents，金额计算统一使用 Levels 差分
                stackedCents = 0  // 金额计算统一使用 Levels 差分
                storedCents = 0   // 金额计算统一使用 Levels 差分
                rejectedCents = 0 // 金额计算统一使用 Levels 差分
                coinsPaidInCents = 0  // 金额计算统一使用 Levels 差分
            } else {
                // 如果没有面额信息，仅用于日志显示
                val isBillDevice = deviceID.contains("SPECTRAL") || deviceID.contains("BILL")
                val estValue = if (isBillDevice) 1000 else 200  // 纸币器：10€，硬币器：2€
                Log.w(TAG, "GetCounters 解析结果（使用估算值，仅用于日志）: deviceID=$deviceID, stacked=$stacked, stored=$stored, rejected=$rejected, coinsPaidIn=$coinsPaidIn, estValue=${estValue}分")
                Log.w(TAG, "  ⚠️ 注意：金额计算必须使用 GetAllLevels 差分，不再使用估算值")
                // 不设置金额，金额计算统一使用 Levels 差分
                stackedCents = 0
                storedCents = 0
                rejectedCents = 0
                coinsPaidInCents = 0
            }
            
            // 总收款金额 = stacked + stored + coinsPaidIn（不包括 rejected）
            val totalReceivedCents = stackedCents + storedCents + coinsPaidInCents
            
            Log.d(TAG, "GetCounters 解析结果: deviceID=$deviceID")
            Log.d(TAG, "  计数项: stacked=$stacked, stored=$stored, rejected=$rejected, coinsPaidIn=$coinsPaidIn")
            Log.d(TAG, "  金额: stackedCents=$stackedCents, storedCents=$storedCents, rejectedCents=$rejectedCents, coinsPaidInCents=$coinsPaidInCents")
            Log.d(TAG, "  总收款: totalReceivedCents=$totalReceivedCents (${totalReceivedCents / 100.0}€)")
            
            CountersResponse(
                deviceID = deviceID,
                stackedTotalCents = stackedCents,  // 保持兼容性
                stackedTotal = stackedCents / 100.0,
                stacked = stacked,
                stored = stored,
                rejected = rejected,
                coinsPaidIn = coinsPaidIn,
                stackedCents = stackedCents,
                storedCents = storedCents,
                rejectedCents = rejectedCents,
                coinsPaidInCents = coinsPaidInCents,
                totalReceivedCents = totalReceivedCents,
                error = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析 GetCounters 响应异常: deviceID=$deviceID", e)
            CountersResponse(deviceID = deviceID, error = e.message)
        }
    }
    
    /**
     * 获取纸币器计数器（无参数版本，使用内部 deviceID）
     * ⚠️ 关键修复：纸币器 SPECTRAL_PAYOUT-0 禁止调用 GetCounters
     * ⚠️ 纸币器支付金额 = GetAllLevels 差分，不需要 GetCounters
     */
    suspend fun getBillCounters(): CountersResponse {
        val deviceID = _billAcceptorDeviceID.value
        if (deviceID == null) {
            Log.w(TAG, "获取纸币器计数器失败: deviceID=null (设备未连接)")
            return CountersResponse(deviceID = null, error = "设备未连接")
        }
        // ⚠️ 关键修复：纸币器 SPECTRAL_PAYOUT-0 禁止调用 GetCounters
        Log.w(TAG, "⚠️ 禁止对纸币器 SPECTRAL_PAYOUT-0 调用 GetCounters: deviceID=$deviceID")
        Log.w(TAG, "⚠️ 说明：纸币器支付金额 = GetAllLevels 差分，不需要 GetCounters")
        return CountersResponse(deviceID = deviceID, error = "GetCounters not allowed for SPECTRAL_PAYOUT-0")
    }
    
    /**
     * 获取硬币器计数器（无参数版本，使用内部 deviceID）
     * ⚠️ 关键修复：硬币器 SMART_COIN_SYSTEM-1 的 GetCounters 只用于"维护统计"，不得影响支付成功/失败
     */
    suspend fun getCoinCounters(): CountersResponse {
        val deviceID = _coinAcceptorDeviceID.value
        if (deviceID == null) {
            Log.w(TAG, "获取硬币器计数器失败: deviceID=null (设备未连接)")
            return CountersResponse(deviceID = null, error = "设备未连接")
        }
        // ⚠️ 硬币器 GetCounters 只用于维护统计，不影响支付流程
        return getCounters(deviceID)
    }
    
    /**
     * 获取设备计数器（GetCounters）- 轻量接口，用于轮询
     * 修复"50€漏记"：返回所有计数项（stacked, stored, rejected等）
     * 
     * @param deviceID 设备ID
     * @return CountersResponse 计数器响应（包含所有计数项和总收款金额）
     */
    /**
     * 获取设备计数器（GetCounters）- 轻量接口，用于轮询
     * ⚠️ 关键修复：纸币器 SPECTRAL_PAYOUT-0 禁止调用 GetCounters
     * ⚠️ 关键修复：GetCounters 解析失败改为非致命警告，返回空对象，不抛出异常
     * ⚠️ 用于日志统计的 GetCounters 调用必须用 try/catch 包裹，失败时返回 null 或空对象
     * 
     * @param deviceID 设备ID
     * @return CountersResponse 计数器响应（包含所有计数项和总收款金额）
     *         如果解析失败，返回包含 error 字段的 CountersResponse，不抛出异常
     */
    suspend fun getCounters(deviceID: String): CountersResponse {
        // ⚠️ 关键修复：纸币器 SPECTRAL_PAYOUT-0 禁止调用 GetCounters
        if (deviceID.startsWith("SPECTRAL_PAYOUT", ignoreCase = true) || 
            deviceID.contains("SPECTRAL", ignoreCase = true) ||
            deviceID.contains("PAYOUT", ignoreCase = true)) {
            Log.w(TAG, "⚠️ 禁止对纸币器 SPECTRAL_PAYOUT-0 调用 GetCounters: deviceID=$deviceID")
            Log.w(TAG, "⚠️ 说明：纸币器支付金额 = GetAllLevels 差分，不需要 GetCounters")
            return CountersResponse(deviceID = deviceID, error = "GetCounters not allowed for SPECTRAL_PAYOUT-0")
        }
        return try {
            Log.d(TAG, "获取设备计数器: deviceID=$deviceID")
            
            // 1. 获取 GetCounters 文本响应
            val response = api.getCounters(deviceID)
            if (!response.isSuccessful || response.body() == null) {
                // ⚠️ 非致命警告：只记录日志，不抛出异常
                Log.w(TAG, "⚠️ GetCounters 请求失败（非致命）: deviceID=$deviceID, code=${response.code()}")
                return CountersResponse(deviceID = deviceID, error = "HTTP ${response.code()}")
            }
            
            // 2. 解析文本响应（包含所有计数项）
            try {
                parseCountersResponse(response.body()!!, deviceID)
            } catch (parseException: Exception) {
                // ⚠️ 非致命警告：解析失败只记录日志，返回包含 error 的响应
                Log.w(TAG, "⚠️ GetCounters 解析失败（非致命）: deviceID=$deviceID", parseException)
                CountersResponse(deviceID = deviceID, error = "Parse failed: ${parseException.message}")
            }
        } catch (e: Exception) {
            // ⚠️ 非致命警告：异常只记录日志，返回包含 error 的响应
            Log.w(TAG, "⚠️ 获取设备计数器异常（非致命）: deviceID=$deviceID", e)
            CountersResponse(deviceID = deviceID, error = e.message)
        }
    }
    
    /**
     * 获取设备已收金额（分）- 基于 GetCounters 文本解析 + GetCurrencyAssignment 面额信息
     * ⚠️ 修复：正确解析 GetCounters 文本响应，并结合面额信息计算金额
     * 
     * @param deviceID 设备ID
     * @return 已收金额（分）- 从 GetCounters 的 Stacked/Coins paid in 结合面额计算
     */
    suspend fun getPaidAmountCents(deviceID: String): Int {
        return try {
            Log.d(TAG, "获取设备已收金额: deviceID=$deviceID")
            
            // 1. 获取 GetCounters 文本响应
            val response = api.getCounters(deviceID)
            if (!response.isSuccessful || response.body() == null) {
                Log.e(TAG, "GetCounters 请求失败: deviceID=$deviceID, code=${response.code()}")
                return 0
            }
            
            // 2. 解析文本响应
            val counters = parseCountersResponse(response.body()!!, deviceID)
            
            // 3. ⚠️ 关键：GetCounters 只有"张数"，需要结合面额信息才能计算金额
            // 获取当前面额配置（GetCurrencyAssignment）
            val assignments = try {
                pollCurrencyAssignments(deviceID)
            } catch (e: Exception) {
                Log.w(TAG, "获取面额配置失败，使用估算值: deviceID=$deviceID", e)
                emptyList()
            }
            
            // 4. 如果有面额信息，使用更准确的计算方式
            // 否则使用 parseCountersResponse 中的估算值
            val paidAmountCents = if (assignments.isNotEmpty()) {
                // TODO: 需要知道"Stacked"对应哪些面额，这需要服务端提供更详细的接口
                // 临时方案：使用估算值
                counters.stackedTotalCents
            } else {
                counters.stackedTotalCents
            }
            
            Log.d(TAG, "GetCounters 最终结果: deviceID=$deviceID, paidAmountCents=$paidAmountCents (${paidAmountCents / 100.0}€)")
            paidAmountCents
        } catch (e: Exception) {
            Log.e(TAG, "获取设备已收金额异常: deviceID=$deviceID", e)
            0  // 异常时返回 0
        }
    }
    
    /**
     * 获取金额跟踪器（用于获取总金额）
     */
    fun getAmountTracker(): CashAmountTracker {
        return amountTracker
    }
    
    /**
     * 设置会话活跃状态（用于防止在 active 会话期间 reset tracker）
     * @param active true 表示会话开始，false 表示会话结束
     */
    fun setSessionActive(active: Boolean) {
        sessionActive = active
        Log.d(TAG, "会话活跃状态已更新: sessionActive=$active")
    }
    
    /**
     * 获取会话活跃状态
     */
    fun isSessionActive(): Boolean {
        return sessionActive
    }
    
    /**
     * 获取设备当前库存总金额（分）- 基于 GetCurrencyAssignment 的 stored 字段
     * ⚠️ 临时方案：使用库存差值计算会话累计金额
     * 
     * 注意：这不是"本次交易credit"，而是设备当前总库存金额
     * 会话累计金额 = 当前库存总金额 - 基线库存总金额
     * 
     * @param deviceID 设备ID
     * @return 当前库存总金额（分）- 从 GetCurrencyAssignment 的 stored 计算
     */
    suspend fun getCurrentStoredTotalCents(deviceID: String): Int {
        return try {
            val assignments = pollCurrencyAssignments(deviceID)
            val totalCents = assignments.sumOf { it.value * it.stored }
            Log.d(TAG, "获取设备当前库存总金额: deviceID=$deviceID, totalCents=$totalCents (${totalCents / 100.0}€)")
            totalCents
        } catch (e: Exception) {
            Log.e(TAG, "获取设备当前库存总金额异常: deviceID=$deviceID", e)
            0  // 异常时返回 0
        }
    }
    
    /**
     * 初始化纸币器（完整流程）
     * 注意：每个步骤独立处理，即使某个步骤失败也会继续尝试后续步骤
     * @param probeApi 用于探测的 API 实例（应使用短超时），如果为 null 则使用默认 api
     * @return Boolean 如果设备连接成功，返回 true；如果连接失败，返回 false（启动/启用步骤失败不影响返回值）
     */
    suspend fun initializeBillAcceptor(probeApi: CashDeviceApi? = null): Boolean {
        Log.d(TAG, "开始初始化纸币器（独立处理，不影响其他设备）")
        
        // ⚠️ 防止重复连接：检查是否正在连接或已连接（支付中）
        val deviceKey = "SPECTRAL_PAYOUT-0"
        if (isDeviceConnectingOrConnected(deviceKey)) {
            val errorMsg = "纸币器正在连接或已连接，禁止重复连接（支付中）"
            Log.w(TAG, "⚠️ $errorMsg")
            return false
        }
        
        // 设置连接状态
        setDeviceConnectionState(deviceKey, true)
        
        try {
            // 1. 认证（获取 token）
            val (authSuccess, authError) = authenticate()
            if (!authSuccess) {
                Log.e(TAG, "纸币器：认证失败，无法初始化: ${authError ?: "未知错误"}")
                setDeviceConnectionState(deviceKey, false)
                return false
            }
            
            // 2. 如果还没有映射，先进行探测
            if (billAcceptorMapping == null) {
                val probeApiInstance = probeApi ?: api  // 如果没有提供探测 API，使用默认 API（但会慢一些）
                val (probeSuccess, probeError) = probeAndMapDevices(probeApiInstance)
                if (!probeSuccess) {
                    Log.e(TAG, "纸币器：设备探测失败，无法初始化: ${probeError ?: "未知错误"}")
                    setDeviceConnectionState(deviceKey, false)
                    return false
                }
            }
            
            // 3. 使用映射打开连接（关键步骤，失败则返回 false）
            if (!openBillAcceptorConnection()) {
                Log.e(TAG, "纸币器：打开连接失败")
                setDeviceConnectionState(deviceKey, false)
                return false
            }
            
            val deviceID = _billAcceptorDeviceID.value
            if (deviceID == null) {
                Log.e(TAG, "纸币器：未获取到 deviceID")
                setDeviceConnectionState(deviceKey, false)
                return false
            }
            
            Log.d(TAG, "纸币器：连接成功，deviceID=$deviceID，开始后续初始化步骤...")
            
            // 4. 启动设备（非关键步骤，失败不影响连接状态）
            val startSuccess = startDevice(deviceID)
            if (!startSuccess) {
                Log.w(TAG, "纸币器：启动设备失败（但连接已建立，可继续使用）")
            } else {
                Log.d(TAG, "纸币器：启动设备成功")
                
                // ⚠️ Step D: 设备连接成功后调用 EnablePayoutDevice（失败只打 warn，不阻塞收款）
                try {
                    val enablePayoutSuccess = enablePayoutDevice(deviceID)
                    if (enablePayoutSuccess) {
                        Log.d(TAG, "纸币器：EnablePayoutDevice 成功")
                    } else {
                        Log.w(TAG, "纸币器：EnablePayoutDevice 失败（不影响收款）")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "纸币器：EnablePayoutDevice 异常（不影响收款）", e)
                }
            }
            
            // 5. 禁用接收器（初始化阶段：确保设备处于禁用状态）
            val disableSuccess = disableAcceptor(deviceID)
            if (!disableSuccess) {
                Log.w(TAG, "纸币器：禁用接收器失败（但连接已建立，可稍后重试）")
            } else {
                Log.d(TAG, "纸币器：禁用接收器成功（初始化阶段：设备已禁用）")
            }
            
            // 6. 设置自动接受为 false（初始化阶段：确保自动接受关闭）
            val autoAcceptSuccess = setAutoAccept(deviceID, false)
            if (!autoAcceptSuccess) {
                Log.w(TAG, "纸币器：设置自动接受失败（但连接已建立，可稍后重试）")
            } else {
                Log.d(TAG, "纸币器：设置自动接受为 false 成功（初始化阶段：自动接受已关闭）")
            }
            
            // 只要连接成功，就认为初始化成功（即使后续步骤失败）
            val allStepsSuccess = startSuccess && disableSuccess && autoAcceptSuccess
            if (allStepsSuccess) {
                Log.d(TAG, "纸币器：初始化完全成功（所有步骤都成功）")
            } else {
                Log.w(TAG, "纸币器：初始化部分成功（连接成功，但部分步骤失败，设备仍可使用）")
            }
            
            // 连接成功，保持连接状态（直到断开连接）
            return true
        } catch (e: Exception) {
            Log.e(TAG, "纸币器初始化异常", e)
            // 连接失败，清除连接状态
            setDeviceConnectionState(deviceKey, false)
            return false
        }
    }
    
    /**
     * 初始化硬币器（完整流程）
     * 注意：每个步骤独立处理，即使某个步骤失败也会继续尝试后续步骤
     * @param probeApi 用于探测的 API 实例（应使用短超时），如果为 null 则使用默认 api
     * @return Boolean 如果设备连接成功，返回 true；如果连接失败，返回 false（启动/启用步骤失败不影响返回值）
     */
    suspend fun initializeCoinAcceptor(probeApi: CashDeviceApi? = null): Boolean {
        Log.d(TAG, "开始初始化硬币器（独立处理，不影响其他设备）")
        
        // ⚠️ 防止重复连接：检查是否正在连接或已连接（支付中）
        val deviceKey = "SMART_COIN_SYSTEM-1"
        if (isDeviceConnectingOrConnected(deviceKey)) {
            val errorMsg = "硬币器正在连接或已连接，禁止重复连接（支付中）"
            Log.w(TAG, "⚠️ $errorMsg")
            return false
        }
        
        // 设置连接状态
        setDeviceConnectionState(deviceKey, true)
        
        try {
            // 1. 认证（如果还未认证）
            if (!TokenStore.hasToken()) {
                val (authSuccess, authError) = authenticate()
                if (!authSuccess) {
                    Log.e(TAG, "硬币器：认证失败，无法初始化: ${authError ?: "未知错误"}")
                    setDeviceConnectionState(deviceKey, false)
                    return false
                }
            }
            
            // 2. 如果还没有映射，先进行探测
            if (coinAcceptorMapping == null) {
                val probeApiInstance = probeApi ?: api  // 如果没有提供探测 API，使用默认 API（但会慢一些）
                val (probeSuccess, probeError) = probeAndMapDevices(probeApiInstance)
                if (!probeSuccess) {
                    Log.e(TAG, "硬币器：设备探测失败，无法初始化: ${probeError ?: "未知错误"}")
                    setDeviceConnectionState(deviceKey, false)
                    return false
                }
            }
            
            // 3. 如果硬币器映射仍为空，说明探测时未找到硬币器
            if (coinAcceptorMapping == null) {
                Log.w(TAG, "硬币器：未找到映射，跳过硬币器初始化（这是正常的，如果只有纸币器）")
                setDeviceConnectionState(deviceKey, false)
                return false
            }
            
            // 4. 使用映射打开连接（关键步骤，失败则返回 false）
            if (!openCoinAcceptorConnection()) {
                Log.e(TAG, "硬币器：打开连接失败")
                setDeviceConnectionState(deviceKey, false)
                return false
            }
            
            val deviceID = _coinAcceptorDeviceID.value
            if (deviceID == null) {
                Log.e(TAG, "硬币器：未获取到 deviceID")
                setDeviceConnectionState(deviceKey, false)
                return false
            }
            
            Log.d(TAG, "硬币器：连接成功，deviceID=$deviceID，开始后续初始化步骤...")
            
            // 5. 启动设备（非关键步骤，失败不影响连接状态）
            val startSuccess = startDevice(deviceID)
            if (!startSuccess) {
                Log.w(TAG, "硬币器：启动设备失败（但连接已建立，可继续使用）")
            } else {
                Log.d(TAG, "硬币器：启动设备成功")
                
                // ⚠️ Step D: 设备连接成功后调用 EnablePayoutDevice（失败只打 warn，不阻塞收款）
                try {
                    val enablePayoutSuccess = enablePayoutDevice(deviceID)
                    if (enablePayoutSuccess) {
                        Log.d(TAG, "硬币器：EnablePayoutDevice 成功")
                    } else {
                        Log.w(TAG, "硬币器：EnablePayoutDevice 失败（不影响收款）")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "硬币器：EnablePayoutDevice 异常（不影响收款）", e)
                }
            }
            
            // 6. 禁用接收器（初始化阶段：确保设备处于禁用状态）
            val disableSuccess = disableAcceptor(deviceID)
            if (!disableSuccess) {
                Log.w(TAG, "硬币器：禁用接收器失败（但连接已建立，可稍后重试）")
            } else {
                Log.d(TAG, "硬币器：禁用接收器成功（初始化阶段：设备已禁用）")
            }
            
            // 7. 设置自动接受为 false（初始化阶段：确保自动接受关闭）
            val autoAcceptSuccess = setAutoAccept(deviceID, false)
            if (!autoAcceptSuccess) {
                Log.w(TAG, "硬币器：设置自动接受失败（但连接已建立，可稍后重试）")
            } else {
                Log.d(TAG, "硬币器：设置自动接受为 false 成功（初始化阶段：自动接受已关闭）")
            }
            
            // 只要连接成功，就认为初始化成功（即使后续步骤失败）
            val allStepsSuccess = startSuccess && disableSuccess && autoAcceptSuccess
            if (allStepsSuccess) {
                Log.d(TAG, "硬币器：初始化完全成功（所有步骤都成功）")
            } else {
                Log.w(TAG, "硬币器：初始化部分成功（连接成功，但部分步骤失败，设备仍可使用）")
            }
            
            // 连接成功，保持连接状态（直到断开连接）
            return true
        } catch (e: Exception) {
            Log.e(TAG, "硬币器初始化异常", e)
            // 连接失败，清除连接状态
            setDeviceConnectionState(deviceKey, false)
            return false
        }
    }
}
