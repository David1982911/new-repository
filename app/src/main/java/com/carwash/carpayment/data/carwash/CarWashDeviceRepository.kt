package com.carwash.carpayment.data.carwash

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 洗车机设备仓库（业务逻辑封装）
 */
class CarWashDeviceRepository(
    val api: CarWashDeviceApi  // 改为 public，供 Controller 访问
) {
    
    companion object {
        private const val TAG = "CarWashDeviceRepository"
    }
    
    // 连接状态
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    // 故障状态
    private val _hasFault = MutableStateFlow<Boolean?>(null)
    val hasFault: StateFlow<Boolean?> = _hasFault.asStateFlow()
    
    // 前车状态
    private val _previousCarPresent = MutableStateFlow<Boolean?>(null)
    val previousCarPresent: StateFlow<Boolean?> = _previousCarPresent.asStateFlow()
    
    // 可再次洗车状态
    private val _canWashAgain = MutableStateFlow<Boolean?>(null)
    val canWashAgain: StateFlow<Boolean?> = _canWashAgain.asStateFlow()
    
    // 车位状态
    private val _carInPosition = MutableStateFlow<Boolean?>(null)
    val carInPosition: StateFlow<Boolean?> = _carInPosition.asStateFlow()
    
    // 启动洗车状态
    private val _washStartReady = MutableStateFlow<Boolean?>(null)
    val washStartReady: StateFlow<Boolean?> = _washStartReady.asStateFlow()
    
    /**
     * 连接洗车机
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            val repoId = System.identityHashCode(this@CarWashDeviceRepository)
            val apiId = System.identityHashCode(api)
            val beforeConnected = api.isConnected()
            Log.d(TAG, "[CarWash] Repository.connect repoId=${repoId} apiId=${apiId} beforeConnected=${beforeConnected}")
            
            Log.d(TAG, "========== 连接洗车机 ==========")
            val success = api.connect()
            _isConnected.value = success
            if (success) {
                Log.d(TAG, "========== 洗车机连接成功 ==========")
            } else {
                Log.e(TAG, "========== 洗车机连接失败 ==========")
            }
            return@withContext success
        } catch (e: Exception) {
            Log.e(TAG, "连接洗车机异常", e)
            _isConnected.value = false
            return@withContext false
        }
    }
    
    /**
     * 断开连接
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "断开洗车机连接...")
            api.disconnect()
            _isConnected.value = false
            Log.d(TAG, "洗车机连接已断开")
        } catch (e: Exception) {
            Log.e(TAG, "断开连接异常", e)
        }
    }
    
    /**
     * 确保已连接（如果未连接则尝试连接）
     * @return 是否已连接（连接成功或已经连接）
     */
    suspend fun ensureConnected(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "[CarWash] ensureConnected: 检查连接状态...")
            val success = api.ensureConnected()
            _isConnected.value = success
            if (success) {
                Log.d(TAG, "[CarWash] ensureConnected: 已连接（连接成功或已经连接）")
            } else {
                Log.e(TAG, "[CarWash] ensureConnected: 连接失败")
            }
            return@withContext success
        } catch (e: Exception) {
            Log.e(TAG, "[CarWash] ensureConnected 异常", e)
            _isConnected.value = false
            return@withContext false
        }
    }
    
    /**
     * 检查洗车机状态（综合检查）
     * @return CarWashStatusCheckResult 状态检查结果
     */
    suspend fun checkStatus(): CarWashStatusCheckResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "========== 检查洗车机状态 ==========")
            
            // 1. 检查故障状态
            val faultStatus = api.readFaultStatus()
            _hasFault.value = faultStatus
            if (faultStatus == true) {
                Log.w(TAG, "⚠️ 洗车机有故障")
                return@withContext CarWashStatusCheckResult(
                    isAvailable = false,
                    errorMessage = "洗车机故障，请检查设备"
                )
            }
            
            // 2. 检查前车状态
            // ⚠️ 4️⃣ 752（前车是否离开）判定规则：
            // 752只有在"洗车完成+车辆驶离洗车区域"后才会从1变为0
            // 如果车辆洗完但未驶离，752将一直保持1，不会因时间自动清零
            // 因此必须保留轮询+超时退款策略
            val previousCar = api.readPreviousCarStatus()
            _previousCarPresent.value = previousCar
            if (previousCar == true) {
                Log.w(TAG, "[CarWash] ⚠️ 前车还在(752=1)，无法开始新的洗车")
                Log.w(TAG, "[CarWash] 注意: 752只有车辆驶离后才会从1变为0，不会因时间自动清零")
                Log.w(TAG, "[CarWash] 建议: 保留轮询+超时退款策略")
                return@withContext CarWashStatusCheckResult(
                    isAvailable = false,
                    errorMessage = "前车还在洗车中，请等待"
                )
            }
            
            // 3. 检查可再次洗车状态
            // ⚠️ 1️⃣ 240（可再次洗车）作为硬门禁
            // 当240=0时，禁止发送任何Mode启动指令（PLC会忽略）
            // 只有在240=1时，才允许发送Mode指令
            val canWash = api.readCanWashAgainStatus()
            _canWashAgain.value = canWash
            if (canWash != true) {
                Log.w(TAG, "[CarWash] ⚠️ 不可再次洗车(240=${if (canWash == false) "0" else "null"})")
                Log.w(TAG, "[CarWash] 240作为硬门禁: 当240=0时，禁止发送任何Mode启动指令（PLC会忽略）")
                return@withContext CarWashStatusCheckResult(
                    isAvailable = false,
                    errorMessage = "洗车机不可用，请稍后再试"
                )
            }
            
            // 4. 检查车位状态（连续两次读取一致才判定有效）
            // ⚠️ 5️⃣ 102（车到位）判定规则：连续两次读取一致即可判定有效（用于防止串口偶发误读）
            val carPos = if (api is CarWashDeviceClient) {
                api.readCarPositionStatusStable()
            } else {
                api.readCarPositionStatus()
            }
            _carInPosition.value = carPos
            
            // 5. 检查启动洗车状态
            val washStart = api.readWashStartStatus()
            _washStartReady.value = washStart
            
            Log.d(TAG, "========== 洗车机状态检查完成 ==========")
            Log.d(TAG, "故障状态: $faultStatus")
            Log.d(TAG, "前车状态: $previousCar")
            Log.d(TAG, "可再次洗车: $canWash")
            Log.d(TAG, "车位状态: $carPos")
            Log.d(TAG, "启动状态: $washStart")
            
            return@withContext CarWashStatusCheckResult(
                isAvailable = true,
                errorMessage = null,
                carInPosition = carPos,
                washStartReady = washStart
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "检查洗车机状态异常", e)
            return@withContext CarWashStatusCheckResult(
                isAvailable = false,
                errorMessage = "检查状态失败: ${e.message}"
            )
        }
    }
    
    /**
     * 发送洗车模式指令（带240硬门禁检查）
     * ⚠️ 240作为硬门禁：当240=0时，禁止发送任何Mode启动指令（PLC会忽略）
     * ⚠️ 只有在240=1时，才允许发送Mode指令
     * @param mode 洗车模式（1-4）
     * @return 是否成功
     */
    suspend fun sendWashMode(mode: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "[CarWash] ========== 发送洗车模式指令（带240硬门禁检查） ==========")
            Log.d(TAG, "[CarWash] 模式: Mode $mode")
            
            // ⚠️ 1️⃣ 240（可再次洗车）作为硬门禁
            val canWash = api.readCanWashAgainStatus()
            _canWashAgain.value = canWash
            if (canWash != true) {
                Log.e(TAG, "[CarWash] ❌ 240硬门禁检查失败: 240=${if (canWash == false) "0" else "null"}，禁止发送Mode指令")
                Log.e(TAG, "[CarWash] 当240=0时，PLC会忽略Mode指令，禁止发送")
                return@withContext false
            }
            Log.d(TAG, "[CarWash] ✅ 240硬门禁检查通过: 240=1，允许发送Mode指令")
            
            // 发送Mode指令（边沿触发，只发送一次）
            val success = api.sendWashMode(mode)
            if (success) {
                Log.d(TAG, "[CarWash] ========== Mode指令发送成功 ==========")
                Log.d(TAG, "[CarWash] 注意: Mode指令为边沿触发，已发送一次，不回写0清除")
                Log.d(TAG, "[CarWash] 启动成功判定需通过读取214（自动状态）确认")
            } else {
                Log.e(TAG, "[CarWash] ========== Mode指令发送失败 ==========")
            }
            return@withContext success
        } catch (e: Exception) {
            Log.e(TAG, "[CarWash] 发送洗车模式指令异常", e)
            return@withContext false
        }
    }
    
    /**
     * 启动确认（发送Mode后检查214状态）
     * ⚠️ 3️⃣ 启动成功判定统一使用214（自动状态）
     * ⚠️ 发送Mode后进入"启动确认窗口"（建议10秒，1秒轮询）
     * ⚠️ 只有当214读取为"自动状态"时，才判定洗车已成功启动
     * @param timeoutSeconds 超时时间（秒），默认10秒
     * @param pollIntervalMs 轮询间隔（毫秒），默认1000ms（1秒）
     * @return true表示启动成功（214=自动状态），false表示启动失败（超时或214=非自动状态）
     */
    suspend fun confirmWashStart(timeoutSeconds: Int = 10, pollIntervalMs: Long = 1000L): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "[CarWash] ========== 启动确认窗口开始 ==========")
            Log.d(TAG, "[CarWash] 超时时间: ${timeoutSeconds}秒, 轮询间隔: ${pollIntervalMs}ms")
            Log.d(TAG, "[CarWash] 判定规则: 只有当214读取为'自动状态'时，才判定洗车已成功启动")
            
            val startTime = System.currentTimeMillis()
            val timeoutMs = timeoutSeconds * 1000L
            
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val isAutoStatus = api.readWashStartStatus()
                if (isAutoStatus == true) {
                    Log.d(TAG, "[CarWash] ========== 启动确认成功 ==========")
                    Log.d(TAG, "[CarWash] 214=自动状态，洗车已成功启动")
                    _washStartReady.value = true
                    return@withContext true
                } else if (isAutoStatus == false) {
                    Log.d(TAG, "[CarWash] 214=非自动状态，继续等待... (已等待${(System.currentTimeMillis() - startTime) / 1000}秒)")
                } else {
                    Log.w(TAG, "[CarWash] 读取214状态失败，继续等待... (已等待${(System.currentTimeMillis() - startTime) / 1000}秒)")
                }
                
                kotlinx.coroutines.delay(pollIntervalMs)
            }
            
            // 超时
            Log.e(TAG, "[CarWash] ========== 启动确认超时 ==========")
            Log.e(TAG, "[CarWash] 超时时间: ${timeoutSeconds}秒，214仍未变为自动状态")
            Log.e(TAG, "[CarWash] 判定启动失败，可重试或进入退款/回退逻辑")
            _washStartReady.value = false
            return@withContext false
            
        } catch (e: Exception) {
            Log.e(TAG, "[CarWash] 启动确认异常", e)
            return@withContext false
        }
    }
    
    /**
     * 发送取消指令
     */
    suspend fun sendCancel(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "发送取消指令...")
            return@withContext api.sendCancel()
        } catch (e: Exception) {
            Log.e(TAG, "发送取消指令异常", e)
            return@withContext false
        }
    }
    
    /**
     * 发送复位指令
     */
    suspend fun sendReset(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "发送复位指令...")
            return@withContext api.sendReset()
        } catch (e: Exception) {
            Log.e(TAG, "发送复位指令异常", e)
            return@withContext false
        }
    }
    
    /**
     * 读取统计数据
     */
    suspend fun readStatistics(): CarWashStatistics? = withContext(Dispatchers.IO) {
        try {
            val totalCount = api.readTotalWashCount()
            val todayCount = api.readTodayWashCount()
            val yesterdayCount = api.readYesterdayWashCount()
            
            return@withContext CarWashStatistics(
                totalCount = totalCount,
                todayCount = todayCount,
                yesterdayCount = yesterdayCount
            )
        } catch (e: Exception) {
            Log.e(TAG, "读取统计数据异常", e)
            return@withContext null
        }
    }
}

/**
 * 洗车机状态检查结果
 */
data class CarWashStatusCheckResult(
    val isAvailable: Boolean,
    val errorMessage: String? = null,
    val carInPosition: Boolean? = null,
    val washStartReady: Boolean? = null
)

/**
 * 洗车机统计数据
 */
data class CarWashStatistics(
    val totalCount: Int?,
    val todayCount: Int?,
    val yesterdayCount: Int?
)
