package com.carwash.carpayment.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carwash.carpayment.CarPaymentApplication
import com.carwash.carpayment.data.PaymentMethod
import com.carwash.carpayment.data.WashProgram
import com.carwash.carpayment.data.carwash.CarWashDeviceRepository
import com.carwash.carpayment.data.carwash.CarWashGateCheck
import com.carwash.carpayment.data.carwash.CarWashGateCheckResult
import com.carwash.carpayment.data.cashdevice.CashDeviceClient
import com.carwash.carpayment.data.cashdevice.CashDeviceRepository
import com.carwash.carpayment.data.pos.PosPaymentService
import com.carwash.carpayment.data.pos.PaymentResult as PosPaymentResult
import com.carwash.carpayment.data.pos.UsdkPosPaymentService
import com.carwash.carpayment.data.washflow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.UUID

/**
 * 洗车流程统一状态机 ViewModel
 * 
 * 单入口：startFlow(order)
 * 内部串联真实流程：支付成功 → GateCheck → 写 Mode 脉冲 → 等 214 → 监控 102 → 完成
 */
class WashFlowViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "WashFlowViewModel"
        
        init {
            Log.d("BUILD_MARK", "FIX_billDeviceId_ref_OK_20260128")
        }
        
        // 寄存器地址映射（根据PDF文档）
        private const val REG_FAULT = 217          // 故障状态
        private const val REG_PREVIOUS_CAR = 752   // 前车状态
        private const val REG_CAN_WASH_AGAIN = 240 // 可再次洗车
        private const val REG_CAR_POSITION = 102   // 车位状态
        private const val REG_WASH_START = 214     // 启动洗车状态
        
        // 洗车模式寄存器地址（根据PDF文档，ModbusAddr = M + 2049）
        // M262 = 262 + 2049 = 2311
        private const val REG_MODE_1 = 2310 // M261 = 261 + 2049
        private const val REG_MODE_2 = 2311 // M262 = 262 + 2049
        private const val REG_MODE_3 = 2309 // M260 = 260 + 2049
        private const val REG_MODE_4 = 2313 // M264 = 264 + 2049
    }
    
    // 流程状态
    private val _flowState = MutableStateFlow<WashFlowState>(WashFlowState.Idle)
    val flowState: StateFlow<WashFlowState> = _flowState.asStateFlow()
    
    // 当前交易ID（用于日志追踪）
    private var currentTxId: String? = null
    
    // 洗车机设备（使用单例）
    private val carWashRepository: CarWashDeviceRepository by lazy {
        val singleton = CarPaymentApplication.carWashRepository
        if (singleton == null) {
            Log.e(TAG, "[WashFlow] ❌ 错误：Application 单例未初始化！")
            throw IllegalStateException("CarWashDeviceRepository 单例未初始化")
        }
        Log.d(TAG, "[WashFlow] ✅ 使用 Application 单例: repoId=${System.identityHashCode(singleton)}")
        singleton
    }
    
    // 现金设备
    private val cashDeviceApi = CashDeviceClient.create(context = getApplication())
    private val cashDeviceRepository = CashDeviceRepository(cashDeviceApi)
    
    // POS 支付服务
    private val posPaymentService by lazy { 
        UsdkPosPaymentService(getApplication(), commId = null, baudrate = 9600)
    }
    
    /**
     * 启动洗车流程（单入口）
     * 
     * @param order 订单信息（程序 + 支付方式）
     */
    fun startFlow(order: WashOrder) {
        val txId = UUID.randomUUID().toString().take(8)
        currentTxId = txId
        
        Log.d(TAG, "[WashFlow] ========== 启动洗车流程 ==========")
        Log.d(TAG, "[WashFlow] txId=$txId, model=${order.program.id}, program=${order.program.name}, paymentMethod=${order.paymentMethod}")
        
        viewModelScope.launch {
            try {
                // 1. 进入支付状态
                _flowState.value = WashFlowState.Paying(
                    program = order.program,
                    paymentMethod = order.paymentMethod,
                    paidAmountCents = 0,
                    targetAmountCents = (order.program.price * 100).toInt()
                )
                
                // 2. 处理支付
                val paymentResult = processPayment(order)
                
                if (paymentResult !is PaymentResult.Success) {
                    // 支付失败
                    _flowState.value = WashFlowState.Failed(
                        reason = when (paymentResult) {
                            is PaymentResult.Failure -> paymentResult.errorMessage
                            is PaymentResult.Cancelled -> "支付已取消: ${paymentResult.reason ?: "用户取消"}"
                            else -> "支付失败"
                        },
                        program = order.program,
                        paymentMethod = order.paymentMethod
                    )
                    return@launch
                }
                
                // 3. 支付成功，进入 GateCheck
                _flowState.value = WashFlowState.PaymentSuccess(
                    program = order.program,
                    paymentMethod = order.paymentMethod,
                    paymentResult = paymentResult
                )
                
                // 4. 执行 GateCheck
                val gateCheckResult = performGateCheck(order, txId)
                
                if (gateCheckResult != null) {
                    // GateCheck 失败，等待条件满足
                    _flowState.value = WashFlowState.WaitingForGateCheck(
                        program = order.program,
                        paymentMethod = order.paymentMethod,
                        failureReason = gateCheckResult,
                        registerSnapshot = readRegisterSnapshot(txId)
                    )
                    
                    // 等待 GateCheck 条件满足（带超时）
                    val waitResult = waitForGateCheck(order, txId)
                    if (!waitResult) {
                        // 超时，进入退款
                        _flowState.value = WashFlowState.Refunding(
                            program = order.program,
                            paymentMethod = order.paymentMethod,
                            reason = RefundReason.GATE_CHECK_TIMEOUT,
                            registerSnapshot = readRegisterSnapshot(txId)
                        )
                        return@launch
                    }
                }
                
                // 5. GateCheck 通过，启动洗车
                val washMode = determineWashMode(order.program)
                _flowState.value = WashFlowState.Starting(
                    program = order.program,
                    paymentMethod = order.paymentMethod,
                    washMode = washMode,
                    registerSnapshot = readRegisterSnapshot(txId)
                )
                
                // 6. 写 Mode 脉冲（根据 washMode 选择对应的寄存器）
                val startResult = startWashing(washMode, txId)
                if (!startResult) {
                    // 启动失败，进入退款
                    _flowState.value = WashFlowState.Refunding(
                        program = order.program,
                        paymentMethod = order.paymentMethod,
                        reason = RefundReason.COMMUNICATION_FAILED,
                        registerSnapshot = readRegisterSnapshot(txId)
                    )
                    return@launch
                }
                
                // 7. 等待 214 进入自动状态
                val runningResult = waitForRunning(washMode, txId, order.program)
                if (!runningResult) {
                    // 启动超时，进入退款
                    _flowState.value = WashFlowState.Refunding(
                        program = order.program,
                        paymentMethod = order.paymentMethod,
                        reason = RefundReason.START_TIMEOUT,
                        registerSnapshot = readRegisterSnapshot(txId)
                    )
                    return@launch
                }
                
                // 8. 进入运行状态，监控 102
                _flowState.value = WashFlowState.Running(
                    program = order.program,
                    paymentMethod = order.paymentMethod,
                    washMode = washMode,
                    registerSnapshot = readRegisterSnapshot(txId)
                )
                
                // 9. 监控 102 直到结束
                val completed = monitorUntilComplete(washMode, txId, order.program)
                if (completed) {
                    // 完成
                    _flowState.value = WashFlowState.Completed(
                        program = order.program,
                        paymentMethod = order.paymentMethod,
                        washMode = washMode,
                        registerSnapshot = readRegisterSnapshot(txId)
                    )
                    Log.d(TAG, "[WashFlow] ========== 洗车流程完成 ==========")
                } else {
                    // 监控超时，进入退款
                    _flowState.value = WashFlowState.Refunding(
                        program = order.program,
                        paymentMethod = order.paymentMethod,
                        reason = RefundReason.MONITOR_TIMEOUT,
                        registerSnapshot = readRegisterSnapshot(txId)
                    )
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "[WashFlow] 流程异常", e)
                _flowState.value = WashFlowState.Failed(
                    reason = "流程异常: ${e.message}",
                    program = order.program,
                    paymentMethod = order.paymentMethod
                )
            }
        }
    }
    
    /**
     * 处理支付（统一接口，返回 PaymentResult）
     */
    private suspend fun processPayment(order: WashOrder): PaymentResult = withContext(Dispatchers.IO) {
        val targetAmountCents = (order.program.price * 100).toInt()
        
        when (order.paymentMethod) {
            PaymentMethod.CASH -> processCashPayment(order, targetAmountCents)
            PaymentMethod.CARD -> processCardPayment(order, targetAmountCents)
        }
    }
    
    /**
     * 处理现金支付（真实实现：使用 ITL Cash Device REST API）
     */
    private suspend fun processCashPayment(order: WashOrder, targetAmountCents: Int): PaymentResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "[WashFlow] 现金支付开始：目标金额=${targetAmountCents}分 (${order.program.price}€)")
            
            // 1. 启动现金设备会话
            val devices = try {
                cashDeviceRepository.startCashSession(caller = "WASHFLOW_CASH_PAYMENT")
            } catch (e: Exception) {
                Log.e(TAG, "[WashFlow] 现金支付：启动设备会话失败", e)
                return@withContext PaymentResult.Failure("设备连接失败: ${e.message}")
            }
            
            if (devices.isEmpty()) {
                return@withContext PaymentResult.Failure("未找到可用设备")
            }
            
            // 2. 设置 baseline（基于 GetCounters 的 stackedTotalCents）
            var billBaselineCents = 0
            var coinBaselineCents = 0
            
            try {
                val baselineCounters = cashDeviceRepository.getBillCounters()
                billBaselineCents = baselineCounters.stackedTotalCents
            } catch (e: Exception) {
                Log.w(TAG, "[WashFlow] 纸币器设置 baseline 失败", e)
            }
            
            try {
                val baselineCounters = cashDeviceRepository.getCoinCounters()
                coinBaselineCents = baselineCounters.stackedTotalCents
            } catch (e: Exception) {
                Log.w(TAG, "[WashFlow] 硬币器设置 baseline 失败", e)
            }
            
            // 3. 启动设备
            cashDeviceRepository.startBillDevice()
            cashDeviceRepository.startCoinDevice()
            
            // 4. 开始现金支付会话（启用接收器并开启自动接受）
            val billSessionSuccess = cashDeviceRepository.beginBillCashSession()
            if (!billSessionSuccess) {
                Log.e(TAG, "[WashFlow] 纸币器开始支付会话失败")
                return@withContext PaymentResult.Failure("纸币器启动失败")
            }
            
            val coinSessionSuccess = cashDeviceRepository.beginCoinCashSession()
            if (!coinSessionSuccess) {
                Log.e(TAG, "[WashFlow] 硬币器开始支付会话失败")
                cashDeviceRepository.endBillCashSession()
                return@withContext PaymentResult.Failure("硬币器启动失败")
            }
            
            // 5. 轮询等待收款
            val paymentStartTime = System.currentTimeMillis()
            val CASH_PAYMENT_TIMEOUT_MS = 60000L // 60秒超时
            
            while (isActive) {
                val elapsed = System.currentTimeMillis() - paymentStartTime
                
                // 检查超时
                if (elapsed > CASH_PAYMENT_TIMEOUT_MS) {
                    // 结束现金支付会话
                    cashDeviceRepository.endBillCashSession()
                    cashDeviceRepository.endCoinCashSession()
                    cashDeviceRepository.stopCashSession("TIMEOUT")
                    return@withContext PaymentResult.Failure("支付超时")
                }
                
                // 读取当前金额
                var billCurrentCents = 0
                var coinCurrentCents = 0
                
                try {
                    val counters = cashDeviceRepository.getBillCounters()
                    billCurrentCents = counters.stackedTotalCents
                } catch (e: Exception) {
                    Log.w(TAG, "[WashFlow] 轮询纸币器失败", e)
                }
                
                try {
                    val counters = cashDeviceRepository.getCoinCounters()
                    coinCurrentCents = counters.stackedTotalCents
                } catch (e: Exception) {
                    Log.w(TAG, "[WashFlow] 轮询硬币器失败", e)
                }
                
                val totalPaidCents = (billCurrentCents - billBaselineCents) + (coinCurrentCents - coinBaselineCents)
                
                // 更新 UI 状态
                _flowState.value = (_flowState.value as? WashFlowState.Paying)?.copy(
                    paidAmountCents = totalPaidCents
                ) ?: _flowState.value
                
                // 判断是否收款完成
                if (totalPaidCents >= targetAmountCents) {
                    // 结束现金支付会话
                    cashDeviceRepository.endBillCashSession()
                    cashDeviceRepository.endCoinCashSession()
                    cashDeviceRepository.stopCashSession("SUCCESS")
                    
                    Log.d(TAG, "[WashFlow] 现金支付成功：${totalPaidCents}分")
                    return@withContext PaymentResult.Success(amountCents = totalPaidCents)
                }
                
                delay(300) // 每 300ms 轮询一次
            }
            
            // 结束现金支付会话
            cashDeviceRepository.endBillCashSession()
            cashDeviceRepository.endCoinCashSession()
            cashDeviceRepository.stopCashSession("CANCELLED")
            return@withContext PaymentResult.Cancelled("用户取消")
            
        } catch (e: Exception) {
            Log.e(TAG, "[WashFlow] 现金支付异常", e)
            try {
                // 异常时也结束现金支付会话
                cashDeviceRepository.endBillCashSession()
                cashDeviceRepository.endCoinCashSession()
                cashDeviceRepository.stopCashSession("EXCEPTION: ${e.message}")
            } catch (stopException: Exception) {
                Log.e(TAG, "[WashFlow] 停止现金会话异常", stopException)
            }
            return@withContext PaymentResult.Failure("支付异常: ${e.message}")
        }
    }
    
    /**
     * 处理卡支付
     */
    private suspend fun processCardPayment(order: WashOrder, targetAmountCents: Int): PaymentResult {
        return try {
            // 初始化 POS 设备
            val initialized = posPaymentService.initialize()
            if (!initialized) {
                return PaymentResult.Failure("POS 设备初始化失败")
            }
            
            // 发起支付（使用 suspendCancellableCoroutine 等待回调）
            val posResult = suspendCancellableCoroutine<PosPaymentResult> { continuation ->
                // 注册取消回调：取消时调用 POS 取消
                continuation.invokeOnCancellation {
                    viewModelScope.launch {
                        try {
                            posPaymentService.cancelPayment()
                        } catch (e: Exception) {
                            Log.w(TAG, "[WashFlow] 取消 POS 支付异常", e)
                        }
                    }
                }
                
                // 在协程中调用 suspend 函数
                viewModelScope.launch {
                    try {
                        posPaymentService.initiatePayment(targetAmountCents) { result ->
                            if (continuation.isActive) {
                                continuation.resume(result)
                            }
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(e)
                        }
                    }
                }
            }
            
            // 转换为统一 PaymentResult
            when (posResult) {
                is PosPaymentResult.Success -> PaymentResult.Success(
                    amountCents = posResult.amountCents,
                    transactionId = null // POS PaymentResult.Success 没有 transactionId
                )
                is PosPaymentResult.Failure -> PaymentResult.Failure(
                    errorMessage = posResult.errorMessage,
                    errorCode = posResult.errorCode?.toString()
                )
                is PosPaymentResult.Cancelled -> PaymentResult.Cancelled(
                    reason = posResult.reason
                )
            }
        } catch (e: Exception) {
            PaymentResult.Failure("支付异常: ${e.message}")
        }
    }
    
    /**
     * 执行 GateCheck
     */
    private suspend fun performGateCheck(order: WashOrder, txId: String): GateCheckFailureReason? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "[WashFlow] txId=$txId, 执行 GateCheck...")
            
            val gateCheck = CarWashGateCheck(carWashRepository)
            val result = gateCheck.check()
            
            when (result) {
                is CarWashGateCheckResult.Passed -> {
                    Log.d(TAG, "[WashFlow] txId=$txId, GateCheck PASSED")
                    return@withContext null
                }
                is CarWashGateCheckResult.Failed -> {
                    Log.e(TAG, "[WashFlow] txId=$txId, GateCheck FAILED reason=${result.reason}")
                    return@withContext when (result.reason) {
                        com.carwash.carpayment.data.carwash.CarWashGateCheckFailureReason.NOT_CONNECTED -> GateCheckFailureReason.NOT_CONNECTED
                        com.carwash.carpayment.data.carwash.CarWashGateCheckFailureReason.COMMUNICATION_FAILED -> GateCheckFailureReason.COMMUNICATION_FAILED
                        com.carwash.carpayment.data.carwash.CarWashGateCheckFailureReason.DEVICE_FAULT -> GateCheckFailureReason.DEVICE_FAULT
                        com.carwash.carpayment.data.carwash.CarWashGateCheckFailureReason.PREVIOUS_CAR_PRESENT -> GateCheckFailureReason.PREVIOUS_CAR_PRESENT
                        com.carwash.carpayment.data.carwash.CarWashGateCheckFailureReason.DEVICE_NOT_READY -> GateCheckFailureReason.DEVICE_NOT_READY
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[WashFlow] txId=$txId, GateCheck 异常", e)
            return@withContext GateCheckFailureReason.COMMUNICATION_FAILED
        }
    }
    
    /**
     * 等待 GateCheck 条件满足（带超时）
     */
    private suspend fun waitForGateCheck(order: WashOrder, txId: String): Boolean = withContext(Dispatchers.IO) {
        val model = order.program.id.toIntOrNull() ?: 1
        val policy = TimeoutPolicy.getDefaultPolicy(model)
        val config = policy.phases[TimeoutPhase.GATE_CHECK_752] ?: return@withContext false
        
        val startTime = System.currentTimeMillis()
        var consecutivePasses = 0
        val requiredPasses = 2
        
        while (isActive) {
            val elapsed = System.currentTimeMillis() - startTime
            val elapsedSec = elapsed / 1000
            
            // 检查硬超时
            if (elapsedSec >= config.hardTimeoutSec) {
                Log.e(TAG, "[WashFlow] txId=$txId, GateCheck 硬超时: ${config.hardTimeoutSec}秒")
                return@withContext false
            }
            
            // 检查软超时
            if (elapsedSec >= config.softTimeoutSec) {
                Log.w(TAG, "[WashFlow] txId=$txId, GateCheck 软超时: ${config.softTimeoutSec}秒")
                // 软超时：只提示/告警，继续等待
            }
            
            // 执行 GateCheck
            val failureReason = performGateCheck(order, txId)
            if (failureReason == null) {
                consecutivePasses++
                if (consecutivePasses >= requiredPasses) {
                    Log.d(TAG, "[WashFlow] txId=$txId, GateCheck 条件满足（连续${requiredPasses}次通过）")
                    return@withContext true
                }
            } else {
                consecutivePasses = 0
            }
            
            delay(config.pollIntervalMs)
        }
        
        false
    }
    
    /**
     * 启动洗车（写 Mode 脉冲）
     * V2：Mode1→M261, Mode2→M262, Mode3→M260, Mode4→M264
     * 防呆：214==1（洗车中）时禁止再次写 Mode
     */
    private suspend fun startWashing(washMode: Int, txId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // 防呆：检查 214==1（洗车中）时禁止再次写 Mode
            val isWashing = try {
                carWashRepository.api.readWashStartStatus()
            } catch (e: Exception) {
                Log.w(TAG, "[WashFlow] txId=$txId, 读取 214 状态失败，继续执行", e)
                null
            }
            
            if (isWashing == true) {
                Log.e(TAG, "[WashFlow] txId=$txId, 启动失败: 214=1 (洗车中)，禁止再次写 Mode")
                return@withContext false
            }
            
            // 根据 washMode 选择对应的寄存器（V2 映射）
            val registerAddress = when (washMode) {
                1 -> REG_MODE_1  // Mode1 → M261
                2 -> REG_MODE_2  // Mode2 → M262
                3 -> REG_MODE_3  // Mode3 → M260
                4 -> REG_MODE_4  // Mode4 → M264
                else -> REG_MODE_1
            }
            
            Log.d(TAG, "[WashFlow] txId=$txId, 发送 Mode $washMode 脉冲（寄存器=$registerAddress）")
            
            // 使用 writeModePulse 方法（V2）
            val success = carWashRepository.api.writeModePulse(washMode)
            
            if (success) {
                Log.d(TAG, "[WashFlow] txId=$txId, Mode $washMode 脉冲发送成功")
                return@withContext true
            } else {
                Log.e(TAG, "[WashFlow] txId=$txId, Mode $washMode 脉冲发送失败")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "[WashFlow] txId=$txId, 启动洗车异常", e)
            return@withContext false
        }
    }
    
    /**
     * 等待 214 进入自动状态
     */
    private suspend fun waitForRunning(washMode: Int, txId: String, program: WashProgram): Boolean = withContext(Dispatchers.IO) {
        val model = program.id.toIntOrNull() ?: 1
        val policy = TimeoutPolicy.getDefaultPolicy(model)
        val config = policy.phases[TimeoutPhase.START_214] ?: return@withContext false
        
        val startTime = System.currentTimeMillis()
        var consecutivePasses = 0
        val requiredPasses = 2
        
        while (isActive) {
            val elapsed = System.currentTimeMillis() - startTime
            val elapsedSec = elapsed / 1000
            
            // 检查硬超时
            if (elapsedSec >= config.hardTimeoutSec) {
                Log.e(TAG, "[WashFlow] txId=$txId, 启动确认硬超时: ${config.hardTimeoutSec}秒")
                return@withContext false
            }
            
            // 检查软超时
            if (elapsedSec >= config.softTimeoutSec) {
                Log.w(TAG, "[WashFlow] txId=$txId, 启动确认软超时: ${config.softTimeoutSec}秒")
            }
            
            // 读取 214
            val autoStatus = carWashRepository.api.readWashStartStatus()
            if (autoStatus == true) {
                consecutivePasses++
                if (consecutivePasses >= requiredPasses) {
                    Log.d(TAG, "[WashFlow] txId=$txId, 启动确认成功（214=自动状态，连续${requiredPasses}次）")
                    return@withContext true
                }
            } else {
                consecutivePasses = 0
            }
            
            delay(config.pollIntervalMs)
        }
        
        false
    }
    
    /**
     * 监控 102 直到结束
     */
    private suspend fun monitorUntilComplete(washMode: Int, txId: String, program: WashProgram): Boolean = withContext(Dispatchers.IO) {
        val model = program.id.toIntOrNull() ?: 1
        val policy = TimeoutPolicy.getDefaultPolicy(model)
        val config = policy.phases[TimeoutPhase.MONITOR_102] ?: return@withContext false
        
        val startTime = System.currentTimeMillis()
        var consecutivePasses = 0
        val requiredPasses = 2
        
        while (isActive) {
            val elapsed = System.currentTimeMillis() - startTime
            val elapsedSec = elapsed / 1000
            
            // 检查硬超时
            if (elapsedSec >= config.hardTimeoutSec) {
                Log.e(TAG, "[WashFlow] txId=$txId, 监控硬超时: ${config.hardTimeoutSec}秒")
                return@withContext false
            }
            
            // 检查软超时
            if (elapsedSec >= config.softTimeoutSec) {
                Log.w(TAG, "[WashFlow] txId=$txId, 监控软超时: ${config.softTimeoutSec}秒")
            }
            
            // 读取 102（车到位状态）
            val carPosition = carWashRepository.api.readCarPositionStatus()
            if (carPosition == false) {
                // 102=0 表示车已离开，洗车完成
                consecutivePasses++
                if (consecutivePasses >= requiredPasses) {
                    Log.d(TAG, "[WashFlow] txId=$txId, 监控完成（102=0，连续${requiredPasses}次）")
                    return@withContext true
                }
            } else {
                consecutivePasses = 0
            }
            
            delay(config.pollIntervalMs)
        }
        
        false
    }
    
    /**
     * 读取寄存器快照
     */
    private suspend fun readRegisterSnapshot(txId: String): RegisterSnapshot = withContext(Dispatchers.IO) {
        try {
            val reg217 = carWashRepository.api.readFaultStatus()?.let { if (it) 1 else 0 }
            val reg752 = carWashRepository.api.readPreviousCarStatus()?.let { if (it) 1 else 0 }
            val reg240 = carWashRepository.api.readCanWashAgainStatus()?.let { if (it) 1 else 0 }
            val reg102 = carWashRepository.api.readCarPositionStatus()?.let { if (it) 1 else 0 }
            val reg214 = carWashRepository.api.readWashStartStatus()?.let { if (it) 1 else 0 }
            
            RegisterSnapshot(
                reg217 = reg217,
                reg752 = reg752,
                reg240 = reg240,
                reg102 = reg102,
                reg214 = reg214
            )
        } catch (e: Exception) {
            Log.e(TAG, "[WashFlow] txId=$txId, 读取寄存器快照异常", e)
            RegisterSnapshot()
        }
    }
    
    /**
     * 根据程序确定洗车模式（1-4）
     */
    private fun determineWashMode(program: WashProgram): Int {
        val programName = program.name.lowercase()
        return when {
            programName.contains("mode 1") || programName.contains("basic") || programName.contains("基础") -> 1
            programName.contains("mode 2") || programName.contains("standard") || programName.contains("标准") -> 2
            programName.contains("mode 3") || programName.contains("premium") || programName.contains("高级") -> 3
            programName.contains("mode 4") || programName.contains("luxury") || programName.contains("豪华") -> 4
            else -> {
                when (program.id) {
                    "1" -> 1
                    "2" -> 2
                    "3" -> 3
                    "4" -> 4
                    else -> 1
                }
            }
        }
    }
}

/**
 * 订单信息
 */
data class WashOrder(
    val program: WashProgram,
    val paymentMethod: PaymentMethod
)
