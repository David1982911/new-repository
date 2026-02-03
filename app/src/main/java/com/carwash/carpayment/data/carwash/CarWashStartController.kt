package com.carwash.carpayment.data.carwash

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * 洗车机启动控制器（状态机实现）
 * 
 * 阶段 8 + 9：支付后洗车机门禁检查 + 启动流程
 * 
 * S8.1 等待 752==0：轮询 12s，最大 5min，连续确认2次通过；超时触发退款（原因：前车未离开）
 * S8.2 等待 102==1：轮询 6s，最大 3min，连续确认2次通过；超时退款（原因：车辆未到位）
 * S8.3 等待 240==1：轮询 4s，最大 1min，连续确认2次通过；240=0 时禁止发送 Mode；超时退款（原因：设备未就绪）
 * S9.1 发送 Mode：仅发送一次；通信失败/无回显可重试最多3次（间隔1s）；仍失败退款（原因：发送命令失败）
 * S9.2 启动确认：轮询 214（每1s，最多10s），只有 214=自动状态（连续确认2次）才算启动成功；若超时未进入自动则按"启动未成功"处理，可回到 S9.1 再尝试（最多3轮），最终失败退款（原因：未进入自动状态）
 * S8.9 退款：按支付方式退款，并在 UI 显示具体失败原因（752/102/240/214/发送失败）
 */
class CarWashStartController(
    private val repository: CarWashDeviceRepository,
    private val washMode: Int
) {
    
    companion object {
        private const val TAG = "CarWashStartController"
        
        // S8.1 等待 752==0 配置
        private const val S8_1_POLL_INTERVAL_MS = 12000L  // 12秒
        private const val S8_1_MAX_TIMEOUT_MS = 5 * 60 * 1000L  // 5分钟
        private const val S8_1_REQUIRED_CONSECUTIVE = 2  // 连续确认2次
        
        // S8.2 等待 102==1 配置
        private const val S8_2_POLL_INTERVAL_MS = 6000L  // 6秒
        private const val S8_2_MAX_TIMEOUT_MS = 3 * 60 * 1000L  // 3分钟
        private const val S8_2_REQUIRED_CONSECUTIVE = 2  // 连续确认2次
        
        // S8.3 等待 240==1 配置
        private const val S8_3_POLL_INTERVAL_MS = 4000L  // 4秒
        private const val S8_3_MAX_TIMEOUT_MS = 60 * 1000L  // 1分钟
        private const val S8_3_REQUIRED_CONSECUTIVE = 2  // 连续确认2次
        
        // S9.1 发送 Mode 配置
        private const val S9_1_MAX_RETRIES = 3  // 最多重试3次
        private const val S9_1_RETRY_INTERVAL_MS = 1000L  // 重试间隔1秒
        
        // S9.2 启动确认配置
        private const val S9_2_POLL_INTERVAL_MS = 1000L  // 1秒
        private const val S9_2_MAX_TIMEOUT_MS = 10 * 1000L  // 10秒
        private const val S9_2_REQUIRED_CONSECUTIVE = 2  // 连续确认2次
        private const val S9_2_MAX_MODE_RETRY_ROUNDS = 3  // 最多3轮（S9.1 -> S9.2 -> S9.1）
    }
    
    private var currentState: CarWashStartState = CarWashStartState.WaitingPreviousCarLeave()
    private var stateChangeCallback: ((CarWashStartState) -> Unit)? = null
    
    /**
     * 设置状态变化回调
     */
    fun setStateChangeCallback(callback: (CarWashStartState) -> Unit) {
        this.stateChangeCallback = callback
    }
    
    /**
     * 更新状态并触发回调
     */
    private fun updateState(newState: CarWashStartState) {
        currentState = newState
        stateChangeCallback?.invoke(newState)
    }
    
    /**
     * 执行状态机（单一协程）
     * @return 最终状态（Success 或 Refunding）
     */
    suspend fun execute(): CarWashStartState = withContext(Dispatchers.IO) {
        Log.d(TAG, "[CarWash] ========== 洗车机启动状态机开始 ==========")
        Log.d(TAG, "[CarWash] 洗车模式: Mode $washMode")
        
        try {
            // S8.1 等待 752==0（前车离开）
            val s8_1_result = executeS8_1_WaitingPreviousCarLeave()
            if (s8_1_result is CarWashStartState.Refunding) {
                return@withContext s8_1_result
            }
            
            // S8.2 等待 102==1（车辆到位）
            val s8_2_result = executeS8_2_WaitingCarInPosition()
            if (s8_2_result is CarWashStartState.Refunding) {
                return@withContext s8_2_result
            }
            
            // S8.3 等待 240==1（设备就绪）
            val s8_3_result = executeS8_3_WaitingDeviceReady()
            if (s8_3_result is CarWashStartState.Refunding) {
                return@withContext s8_3_result
            }
            
            // S9.1 + S9.2 循环（最多3轮）
            var modeRetryRound = 1
            while (modeRetryRound <= S9_2_MAX_MODE_RETRY_ROUNDS) {
                Log.d(TAG, "[CarWash] ========== Mode发送+启动确认 第 $modeRetryRound 轮 ==========")
                
                // S9.1 发送 Mode
                val s9_1_result = executeS9_1_SendingMode()
                if (s9_1_result is CarWashStartState.Refunding) {
                    return@withContext s9_1_result
                }
                
                // S9.2 启动确认
                val s9_2_result = executeS9_2_ConfirmingStart(modeRetryRound)
                if (s9_2_result is CarWashStartState.Success) {
                    return@withContext s9_2_result
                } else if (s9_2_result is CarWashStartState.Refunding) {
                    return@withContext s9_2_result
                }
                
                // 如果未成功且未退款，继续下一轮
                modeRetryRound++
                if (modeRetryRound <= S9_2_MAX_MODE_RETRY_ROUNDS) {
                    Log.d(TAG, "[CarWash] 启动未成功，准备重试第 $modeRetryRound 轮...")
                    delay(1000) // 重试前等待1秒
                } else {
                    // 已达到最大重试轮次，退出循环
                    break
                }
            }
            
            // 所有轮次都失败，退款
            Log.e(TAG, "[CarWash] ========== 所有轮次都失败，触发退款 ==========")
            val refundState = CarWashStartState.Refunding(CarWashStartFailureReason.NOT_ENTERED_AUTO_STATUS)
            updateState(refundState)
            return@withContext refundState
            
        } catch (e: Exception) {
            Log.e(TAG, "[CarWash] 状态机执行异常", e)
            val refundState = CarWashStartState.Refunding(CarWashStartFailureReason.SEND_MODE_FAILED)
            updateState(refundState)
            return@withContext refundState
        }
    }
    
    /**
     * S8.1 等待 752==0（前车离开）
     * 轮询 12s，最大 5min，连续确认2次通过；超时触发退款（原因：前车未离开）
     */
    private suspend fun executeS8_1_WaitingPreviousCarLeave(): CarWashStartState {
        Log.d(TAG, "[CarWash] ========== S8.1 等待 752==0（前车离开） ==========")
        Log.d(TAG, "[CarWash] 轮询间隔: ${S8_1_POLL_INTERVAL_MS}ms, 最大超时: ${S8_1_MAX_TIMEOUT_MS}ms, 连续确认: ${S8_1_REQUIRED_CONSECUTIVE}次")
        
        val startTime = System.currentTimeMillis()
        var consecutiveConfirmations = 0
        
        while (System.currentTimeMillis() - startTime < S8_1_MAX_TIMEOUT_MS) {
            val elapsed = System.currentTimeMillis() - startTime
            val timeoutRemaining = S8_1_MAX_TIMEOUT_MS - elapsed
            
            val previousCar = repository.api.readPreviousCarStatus()
            val value = if (previousCar == false) "0" else if (previousCar == true) "1" else "null"
            
            Log.d(TAG, "[CarWash] state=S8.1, register=752, value=$value, elapsed=${elapsed}ms, timeoutRemaining=${timeoutRemaining}ms")
            
            if (previousCar == false) {
                consecutiveConfirmations++
                Log.d(TAG, "[CarWash] 752==0 连续确认: $consecutiveConfirmations/${S8_1_REQUIRED_CONSECUTIVE}")
                
                if (consecutiveConfirmations >= S8_1_REQUIRED_CONSECUTIVE) {
                    Log.d(TAG, "[CarWash] ✅ S8.1 完成: 752==0 连续确认${S8_1_REQUIRED_CONSECUTIVE}次通过")
                    updateState(CarWashStartState.WaitingCarInPosition())
                    return CarWashStartState.WaitingCarInPosition()
                }
            } else {
                consecutiveConfirmations = 0
            }
            
            delay(S8_1_POLL_INTERVAL_MS)
        }
        
        // 超时
        Log.e(TAG, "[CarWash] ❌ S8.1 超时: 752 在 ${S8_1_MAX_TIMEOUT_MS}ms 内未变为0")
        val refundState = CarWashStartState.Refunding(CarWashStartFailureReason.PREVIOUS_CAR_NOT_LEFT)
        updateState(refundState)
        return refundState
    }
    
    /**
     * S8.2 等待 102==1（车辆到位）
     * 轮询 6s，最大 3min，连续确认2次通过；超时退款（原因：车辆未到位）
     */
    private suspend fun executeS8_2_WaitingCarInPosition(): CarWashStartState {
        Log.d(TAG, "[CarWash] ========== S8.2 等待 102==1（车辆到位） ==========")
        Log.d(TAG, "[CarWash] 轮询间隔: ${S8_2_POLL_INTERVAL_MS}ms, 最大超时: ${S8_2_MAX_TIMEOUT_MS}ms, 连续确认: ${S8_2_REQUIRED_CONSECUTIVE}次")
        
        val startTime = System.currentTimeMillis()
        var consecutiveConfirmations = 0
        
        while (System.currentTimeMillis() - startTime < S8_2_MAX_TIMEOUT_MS) {
            val elapsed = System.currentTimeMillis() - startTime
            val timeoutRemaining = S8_2_MAX_TIMEOUT_MS - elapsed
            
            // 使用稳定读取方法（连续两次读取一致）
            val carInPosition = try {
                val client = repository.api as? com.carwash.carpayment.data.carwash.CarWashDeviceClient
                client?.readCarPositionStatusStable() ?: repository.api.readCarPositionStatus()
            } catch (e: Exception) {
                Log.w(TAG, "[CarWash] 使用稳定读取方法失败，回退到普通读取", e)
                repository.api.readCarPositionStatus()
            }
            val value = if (carInPosition == true) "1" else if (carInPosition == false) "0" else "null"
            
            Log.d(TAG, "[CarWash] state=S8.2, register=102, value=$value, elapsed=${elapsed}ms, timeoutRemaining=${timeoutRemaining}ms")
            
            if (carInPosition == true) {
                consecutiveConfirmations++
                Log.d(TAG, "[CarWash] 102==1 连续确认: $consecutiveConfirmations/${S8_2_REQUIRED_CONSECUTIVE}")
                
                if (consecutiveConfirmations >= S8_2_REQUIRED_CONSECUTIVE) {
                    Log.d(TAG, "[CarWash] ✅ S8.2 完成: 102==1 连续确认${S8_2_REQUIRED_CONSECUTIVE}次通过")
                    updateState(CarWashStartState.WaitingDeviceReady())
                    return CarWashStartState.WaitingDeviceReady()
                }
            } else {
                consecutiveConfirmations = 0
            }
            
            delay(S8_2_POLL_INTERVAL_MS)
        }
        
        // 超时
        Log.e(TAG, "[CarWash] ❌ S8.2 超时: 102 在 ${S8_2_MAX_TIMEOUT_MS}ms 内未变为1")
        val refundState = CarWashStartState.Refunding(CarWashStartFailureReason.CAR_NOT_IN_POSITION)
        updateState(refundState)
        return refundState
    }
    
    /**
     * S8.3 等待 240==1（设备就绪）
     * 轮询 4s，最大 1min，连续确认2次通过；240=0 时禁止发送 Mode；超时退款（原因：设备未就绪）
     */
    private suspend fun executeS8_3_WaitingDeviceReady(): CarWashStartState {
        Log.d(TAG, "[CarWash] ========== S8.3 等待 240==1（设备就绪） ==========")
        Log.d(TAG, "[CarWash] 轮询间隔: ${S8_3_POLL_INTERVAL_MS}ms, 最大超时: ${S8_3_MAX_TIMEOUT_MS}ms, 连续确认: ${S8_3_REQUIRED_CONSECUTIVE}次")
        
        val startTime = System.currentTimeMillis()
        var consecutiveConfirmations = 0
        
        while (System.currentTimeMillis() - startTime < S8_3_MAX_TIMEOUT_MS) {
            val elapsed = System.currentTimeMillis() - startTime
            val timeoutRemaining = S8_3_MAX_TIMEOUT_MS - elapsed
            
            val canWash = repository.api.readCanWashAgainStatus()
            val value = if (canWash == true) "1" else if (canWash == false) "0" else "null"
            
            Log.d(TAG, "[CarWash] state=S8.3, register=240, value=$value, elapsed=${elapsed}ms, timeoutRemaining=${timeoutRemaining}ms")
            
            if (canWash == true) {
                consecutiveConfirmations++
                Log.d(TAG, "[CarWash] 240==1 连续确认: $consecutiveConfirmations/${S8_3_REQUIRED_CONSECUTIVE}")
                
                if (consecutiveConfirmations >= S8_3_REQUIRED_CONSECUTIVE) {
                    Log.d(TAG, "[CarWash] ✅ S8.3 完成: 240==1 连续确认${S8_3_REQUIRED_CONSECUTIVE}次通过")
                    updateState(CarWashStartState.SendingMode(washMode))
                    return CarWashStartState.SendingMode(washMode)
                }
            } else {
                consecutiveConfirmations = 0
                if (canWash == false) {
                    Log.w(TAG, "[CarWash] ⚠️ 240=0，禁止发送Mode指令（硬门禁）")
                }
            }
            
            delay(S8_3_POLL_INTERVAL_MS)
        }
        
        // 超时
        Log.e(TAG, "[CarWash] ❌ S8.3 超时: 240 在 ${S8_3_MAX_TIMEOUT_MS}ms 内未变为1")
        val refundState = CarWashStartState.Refunding(CarWashStartFailureReason.DEVICE_NOT_READY)
        updateState(refundState)
        return refundState
    }
    
    /**
     * S9.1 发送 Mode 指令
     * 仅发送一次；通信失败/无回显可重试最多3次（间隔1s）；仍失败退款（原因：发送命令失败）
     */
    private suspend fun executeS9_1_SendingMode(): CarWashStartState {
        Log.d(TAG, "[CarWash] ========== S9.1 发送 Mode 指令 ==========")
        Log.d(TAG, "[CarWash] 模式: Mode $washMode, 最多重试: ${S9_1_MAX_RETRIES}次, 重试间隔: ${S9_1_RETRY_INTERVAL_MS}ms")
        
        var retryCount = 0
        
        while (retryCount <= S9_1_MAX_RETRIES) {
            updateState(CarWashStartState.SendingMode(washMode, retryCount, S9_1_MAX_RETRIES))
            
            if (retryCount > 0) {
                Log.d(TAG, "[CarWash] 第 $retryCount 次重试发送 Mode 指令...")
                delay(S9_1_RETRY_INTERVAL_MS)
            }
            
            // 发送 Mode 指令（边沿触发，只发送一次）
            // 注意：这里不检查240，因为S8.3已经确保240==1
            val success = try {
                repository.api.sendWashMode(washMode)
            } catch (e: Exception) {
                Log.e(TAG, "[CarWash] 发送 Mode 指令异常", e)
                false
            }
            
            if (success) {
                Log.d(TAG, "[CarWash] ✅ S9.1 完成: Mode指令发送成功")
                updateState(CarWashStartState.ConfirmingStart(modeRetryRound = 1))
                return CarWashStartState.ConfirmingStart(modeRetryRound = 1)
            } else {
                Log.w(TAG, "[CarWash] ⚠️ Mode指令发送失败 (重试 $retryCount/${S9_1_MAX_RETRIES})")
                retryCount++
            }
        }
        
        // 所有重试都失败
        Log.e(TAG, "[CarWash] ❌ S9.1 失败: Mode指令发送失败，已重试${S9_1_MAX_RETRIES}次")
        val refundState = CarWashStartState.Refunding(CarWashStartFailureReason.SEND_MODE_FAILED)
        updateState(refundState)
        return refundState
    }
    
    /**
     * S9.2 启动确认（等待 214=自动状态）
     * 轮询 214（每1s，最多10s），只有 214=自动状态（连续确认2次）才算启动成功；若超时未进入自动则按"启动未成功"处理
     */
    private suspend fun executeS9_2_ConfirmingStart(modeRetryRound: Int): CarWashStartState {
        Log.d(TAG, "[CarWash] ========== S9.2 启动确认（等待 214=自动状态） ==========")
        Log.d(TAG, "[CarWash] 轮询间隔: ${S9_2_POLL_INTERVAL_MS}ms, 最大超时: ${S9_2_MAX_TIMEOUT_MS}ms, 连续确认: ${S9_2_REQUIRED_CONSECUTIVE}次, 当前轮次: $modeRetryRound")
        
        val startTime = System.currentTimeMillis()
        var consecutiveConfirmations = 0
        
        while (System.currentTimeMillis() - startTime < S9_2_MAX_TIMEOUT_MS) {
            val elapsed = System.currentTimeMillis() - startTime
            val timeoutRemaining = S9_2_MAX_TIMEOUT_MS - elapsed
            
            val isAutoStatus = repository.api.readWashStartStatus()
            val value = if (isAutoStatus == true) "自动状态" else if (isAutoStatus == false) "非自动状态" else "null"
            
            Log.d(TAG, "[CarWash] state=S9.2, register=214, value=$value, elapsed=${elapsed}ms, timeoutRemaining=${timeoutRemaining}ms")
            
            updateState(CarWashStartState.ConfirmingStart(elapsed, consecutiveConfirmations, modeRetryRound, S9_2_MAX_MODE_RETRY_ROUNDS))
            
            if (isAutoStatus == true) {
                consecutiveConfirmations++
                Log.d(TAG, "[CarWash] 214=自动状态 连续确认: $consecutiveConfirmations/${S9_2_REQUIRED_CONSECUTIVE}")
                
                if (consecutiveConfirmations >= S9_2_REQUIRED_CONSECUTIVE) {
                    Log.d(TAG, "[CarWash] ✅ S9.2 完成: 214=自动状态 连续确认${S9_2_REQUIRED_CONSECUTIVE}次通过，洗车程序已启动")
                    updateState(CarWashStartState.Success)
                    return CarWashStartState.Success
                }
            } else {
                consecutiveConfirmations = 0
            }
            
            delay(S9_2_POLL_INTERVAL_MS)
        }
        
        // 超时（但不算失败，可以重试）
        Log.w(TAG, "[CarWash] ⚠️ S9.2 超时: 214 在 ${S9_2_MAX_TIMEOUT_MS}ms 内未变为自动状态")
        Log.w(TAG, "[CarWash] 启动未成功，可回到 S9.1 再尝试（当前轮次: $modeRetryRound/${S9_2_MAX_MODE_RETRY_ROUNDS}）")
        
        // 返回一个特殊状态，表示需要重试（不是最终失败）
        // 这里返回当前状态，让外层循环处理重试
        return CarWashStartState.ConfirmingStart(
            elapsedMs = S9_2_MAX_TIMEOUT_MS,
            consecutiveConfirmations = consecutiveConfirmations,
            modeRetryRound = modeRetryRound,
            maxModeRetryRounds = S9_2_MAX_MODE_RETRY_ROUNDS
        )
    }
}
