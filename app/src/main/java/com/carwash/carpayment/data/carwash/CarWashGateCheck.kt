package com.carwash.carpayment.data.carwash

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 洗车机门禁检查结果
 */
sealed class CarWashGateCheckResult {
    /**
     * 检查通过，可以进入支付界面
     */
    object Passed : CarWashGateCheckResult()
    
    /**
     * 检查失败，禁止进入支付界面
     */
    data class Failed(
        val reason: CarWashGateCheckFailureReason,
        val message: String
    ) : CarWashGateCheckResult()
}

/**
 * 洗车机门禁检查失败原因
 */
enum class CarWashGateCheckFailureReason {
    NOT_CONNECTED,      // 洗车机未连接
    COMMUNICATION_FAILED,  // 通讯失败（读取寄存器失败）
    DEVICE_FAULT,       // 设备故障（217=有故障）
    PREVIOUS_CAR_PRESENT,  // 前车还在（752=1）
    DEVICE_NOT_READY    // 设备未就绪（240=0）
}

/**
 * 洗车机门禁检查（GateCheck）
 * 
 * 阶段 3.1/3.2：套餐确认→进入支付的入口检查
 * 
 * 硬门禁：
 * - 217（故障）：必须读取成功，若读取失败/异常/null，判定 GateCheck=FAILED
 * - 752（前车状态）：读取失败也应导致 GateCheck=FAILED
 * - 240（可再次洗车）：读取失败也应导致 GateCheck=FAILED
 * 
 * 禁止"失败时默认放行"的逻辑
 */
class CarWashGateCheck(
    private val repository: CarWashDeviceRepository
) {
    
    companion object {
        private const val TAG = "CarWashGateCheck"
    }
    
    /**
     * 执行门禁检查（带退避重试）
     * @return GateCheckResult 检查结果
     */
    suspend fun check(): CarWashGateCheckResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "[CarWash] ========== GateCheck 开始 ==========")
            
            // 打印结构化日志：实例信息
            val repoId = System.identityHashCode(repository)
            val apiId = System.identityHashCode(repository.api)
            val apiConnected = repository.api.isConnected()
            val repoFlowConnected = repository.isConnected.value
            
            Log.d(TAG, "[CarWash] GateCheck repoId=${repoId} apiId=${apiId} apiConnected=${apiConnected} repoFlowConnected=${repoFlowConnected}")
            
            // 1. 检查连接状态，如果未连接则尝试连接（先补连）
            if (!repository.api.isConnected()) {
                Log.w(TAG, "[CarWash] GateCheck: api not connected, try repository.connect() ...")
                val ok = repository.connect()
                Log.w(TAG, "[CarWash] GateCheck: repository.connect() result=$ok apiConnected=${repository.api.isConnected()} repoFlowConnected=${repository.isConnected.value}")
                if (!ok || !repository.api.isConnected()) {
                    Log.e(TAG, "[CarWash] GateCheck FAILED reason=NOT_CONNECTED (连接失败)")
                    return@withContext CarWashGateCheckResult.Failed(
                        reason = CarWashGateCheckFailureReason.NOT_CONNECTED,
                        message = ""  // 不再传递硬编码消息，UI 层根据 reason 映射到 stringResource
                    )
                }
                Log.d(TAG, "[CarWash] GateCheck: 自动连接成功，继续执行检查")
            }
            
            // 2. 硬门禁：读取 217（故障状态）- 带退避重试
            val faultStatus = readRegisterWithBackoff(217, "故障状态") { registerAddress ->
                repository.api.readFaultStatus()
            } ?: return@withContext CarWashGateCheckResult.Failed(
                reason = CarWashGateCheckFailureReason.COMMUNICATION_FAILED,
                message = ""
            )
            
            if (faultStatus == true) {
                Log.e(TAG, "[CarWash] GateCheck FAILED reason=DEVICE_FAULT reg=217 (有故障)")
                return@withContext CarWashGateCheckResult.Failed(
                    reason = CarWashGateCheckFailureReason.DEVICE_FAULT,
                    message = ""
                )
            }
            
            Log.d(TAG, "[CarWash] 217（故障）检查通过: 无故障")
            
            // 3. V2：752 作为可选等待/提示，不作为唯一硬阻断
            val previousCar = try {
                repository.api.readPreviousCarStatus()
            } catch (e: Exception) {
                Log.w(TAG, "[CarWash] 读取 752（前车状态）失败，继续执行（不作为硬阻断）", e)
                null
            }
            
            if (previousCar == true) {
                Log.w(TAG, "[CarWash] GateCheck 提示: 752=1 (前车还在)，但继续执行（不作为硬阻断）")
                // V2：752 不作为硬阻断，只记录日志，继续执行
            } else {
                Log.d(TAG, "[CarWash] 752（前车状态）: 前车已离开")
            }
            
            // 4. V2 硬门禁：读取 240（可再次洗车）- 带退避重试
            val canWash = readRegisterWithBackoff(240, "可再次洗车") { registerAddress ->
                repository.api.readCanWashAgainStatus()
            } ?: return@withContext CarWashGateCheckResult.Failed(
                reason = CarWashGateCheckFailureReason.COMMUNICATION_FAILED,
                message = ""
            )
            
            if (canWash == false) {
                Log.w(TAG, "[CarWash] GateCheck FAILED reason=DEVICE_NOT_READY reg=240 (设备未就绪)")
                return@withContext CarWashGateCheckResult.Failed(
                    reason = CarWashGateCheckFailureReason.DEVICE_NOT_READY,
                    message = ""
                )
            }
            
            Log.d(TAG, "[CarWash] 240（可再次洗车）检查通过: 可以洗车")
            
            // 所有检查通过
            Log.d(TAG, "[CarWash] ========== GateCheck PASSED ==========")
            return@withContext CarWashGateCheckResult.Passed
            
        } catch (e: Exception) {
            Log.e(TAG, "[CarWash] GateCheck FAILED reason=COMMUNICATION_FAILED (异常)", e)
            return@withContext CarWashGateCheckResult.Failed(
                reason = CarWashGateCheckFailureReason.COMMUNICATION_FAILED,
                message = ""
            )
        }
    }
    
    /**
     * 读取寄存器（带退避重试）
     * @param registerAddress 寄存器地址
     * @param description 寄存器描述（用于日志）
     * @param readFunction 读取函数（返回 Boolean?）
     * @return 读取结果，失败返回 null
     */
    private suspend fun readRegisterWithBackoff(
        registerAddress: Int,
        description: String,
        readFunction: suspend (Int) -> Boolean?
    ): Boolean? {
        var attempt = 1
        var delayMs = 0L
        val maxAttempts = 4  // 最多重试4次（初始1次 + 3次重试）
        val maxDelayMs = 10000L  // 最大延迟10秒
        
        while (attempt <= maxAttempts) {
            // 计算本次延迟（退避策略：1s, 2s, 4s, 上限10s）
            if (attempt > 1) {
                delayMs = minOf(1000L * (1 shl (attempt - 2)), maxDelayMs)
                Log.d("CARWASH_POLL", "poll reg=$registerAddress attempt=$attempt nextDelayMs=$delayMs")
                kotlinx.coroutines.delay(delayMs)
            } else {
                Log.d("CARWASH_POLL", "poll reg=$registerAddress attempt=$attempt nextDelayMs=0")
            }
            
            try {
                val result = readFunction(registerAddress)
                if (result != null) {
                    Log.d("CARWASH_POLL", "rx ok reg=$registerAddress value=$result")
                    return result
                } else {
                    Log.w(TAG, "[CarWash] 读取 $description (reg=$registerAddress) 失败: 返回 null, attempt=$attempt")
                }
            } catch (e: Exception) {
                Log.w(TAG, "[CarWash] 读取 $description (reg=$registerAddress) 异常, attempt=$attempt", e)
            }
            
            attempt++
        }
        
        // 所有重试都失败
        Log.e(TAG, "[CarWash] 读取 $description (reg=$registerAddress) 失败: 所有重试都失败")
        return null
    }
}
