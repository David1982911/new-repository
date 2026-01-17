package com.carwash.carpayment.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carwash.carpayment.data.PaymentMethod
import com.carwash.carpayment.data.WashProgram
import com.carwash.carpayment.data.cashdevice.CashDeviceClient
import com.carwash.carpayment.data.cashdevice.CashDeviceRepository
import com.carwash.carpayment.data.payment.PaymentFlowState
import com.carwash.carpayment.data.payment.PaymentFlowStateMachine
import com.carwash.carpayment.data.payment.PaymentFlowStatus
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * PaymentViewModel - 管理支付流程状态（使用状态机）
 */
class PaymentViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "PaymentViewModel"
        private const val CASH_PAYMENT_TIMEOUT_MS = 10000L // 现金支付超时 10 秒
        private const val DEVICE_INIT_TIMEOUT_MS = 5000L    // 设备初始化超时 5 秒
    }
    
    private val stateMachine = PaymentFlowStateMachine()
    private val cashDeviceApi = CashDeviceClient.create(context = getApplication())
    private val cashDeviceRepository = CashDeviceRepository(cashDeviceApi)
    
    // 支付流程状态（使用状态机）
    private val _flowState = MutableStateFlow<PaymentFlowState>(
        PaymentFlowState(status = PaymentFlowStatus.NOT_STARTED)
    )
    val flowState: StateFlow<PaymentFlowState> = _flowState.asStateFlow()
    
    // 兼容旧的状态接口（用于现有 UI）
    private val _state = MutableStateFlow(PaymentState())
    val state: StateFlow<PaymentState> = _state.asStateFlow()
    
    init {
        // 同步流程状态到兼容状态
        viewModelScope.launch {
            _flowState.collect { flowState ->
                _state.value = PaymentState(
                    selectedProgram = flowState.selectedProgram,
                    selectedPaymentMethod = flowState.selectedPaymentMethod,
                    paymentConfirmed = flowState.paymentConfirmed,
                    isPaymentProcessing = flowState.status == PaymentFlowStatus.PAYING,
                    paymentSuccess = flowState.status == PaymentFlowStatus.SUCCESS ||
                                    flowState.status == PaymentFlowStatus.STARTING_WASH
                )
            }
        }
    }
    
    /**
     * 选择洗车程序（开始支付流程）
     */
    fun selectProgram(program: WashProgram) {
        Log.d(TAG, "选择洗车程序: ${program.id}, 价格: ${program.price}€")
        val newState = stateMachine.startPaymentFlow(program)
        _flowState.value = newState
    }
    
    /**
     * 选择支付方式
     */
    fun selectPaymentMethod(method: PaymentMethod) {
        Log.d(TAG, "选择支付方式: $method")
        val currentState = _flowState.value
        val newState = stateMachine.selectPaymentMethod(currentState, method)
        _flowState.value = newState
    }
    
    /**
     * 设置支付确认状态
     */
    fun setPaymentConfirmed(confirmed: Boolean) {
        Log.d(TAG, "支付确认状态: $confirmed")
        val currentState = _flowState.value
        _flowState.value = currentState.copy(
            paymentConfirmed = confirmed,
            lastUpdated = System.currentTimeMillis()
        )
    }
    
    /**
     * 处理支付（真实实现：现金/卡支付）
     */
    fun processPayment() {
        val currentState = _flowState.value
        val newState = stateMachine.confirmPayment(currentState)
        
        if (newState == null) {
            Log.w(TAG, "无法开始支付，条件不满足")
            return
        }
        
        _flowState.value = newState
        
        viewModelScope.launch {
            Log.d(TAG, "开始处理支付... 支付方式: ${currentState.selectedPaymentMethod}")
            
            when (currentState.selectedPaymentMethod) {
                PaymentMethod.CASH -> {
                    processCashPayment(currentState)
                }
                PaymentMethod.CARD -> {
                    // 卡支付：模拟处理（后续接入真实 POS）
                    Log.d(TAG, "卡支付：模拟处理中...")
                    delay(1500)
                    val successState = stateMachine.paymentSuccess(_flowState.value)
                    if (successState != null) {
                        _flowState.value = successState
                        Log.d(TAG, "卡支付处理完成，状态: SUCCESS")
                    }
                }
                null -> {
                    Log.e(TAG, "支付方式为空，无法处理")
                    handlePaymentFailure("支付方式未选择")
                }
            }
        }
    }
    
    /**
     * 处理现金支付（真实实现：使用 ITL Cash Device REST API）
     */
    private suspend fun processCashPayment(currentState: PaymentFlowState) {
        val program = currentState.selectedProgram
        if (program == null) {
            Log.e(TAG, "现金支付：程序信息为空")
            handlePaymentFailure("程序信息缺失")
            return
        }
        
        val targetAmount = program.price
        val targetAmountCents = (targetAmount * 100).toInt()
        
        Log.d(TAG, "现金支付开始：目标金额=${targetAmount}€ (${targetAmountCents}分)")
        
        try {
            // 1. 启动现金设备会话（认证 + 打开双设备连接）
            val sessionStartTime = System.currentTimeMillis()
            Log.d(TAG, "现金支付：启动设备会话...")
            val devices = try {
                cashDeviceRepository.startCashSession()
            } catch (e: Exception) {
                Log.e(TAG, "现金支付：启动设备会话失败", e)
                handlePaymentFailure("设备连接失败: ${e.message}")
                return
            }
            
            if (devices.isEmpty()) {
                Log.e(TAG, "现金支付：未找到可用设备")
                handlePaymentFailure("未找到可用设备，请检查设备连接")
                return
            }
            
            val sessionDuration = System.currentTimeMillis() - sessionStartTime
            Log.d(TAG, "现金支付：设备会话启动成功（耗时${sessionDuration}ms），已注册设备: ${devices.keys.joinToString("、")}")
            
            // 2. 轮询计数器，获取实时已收金额
            Log.d(TAG, "现金支付：开始等待收款，目标金额=${targetAmount}€ (${targetAmountCents}分)")
            val paymentStartTime = System.currentTimeMillis()
            val tracker = cashDeviceRepository.getAmountTracker()
            
            while (currentCoroutineContext().isActive && tracker.getTotalCents() < targetAmountCents) {
                // 检查超时
                val elapsed = System.currentTimeMillis() - paymentStartTime
                if (elapsed > CASH_PAYMENT_TIMEOUT_MS) {
                    val collectedCents = tracker.getTotalCents()
                    val collectedAmount = tracker.getTotalAmount()
                    
                    // 超时日志：打印 baselineTotal / currentTotal / sessionDelta / levels条目数
                    val billDeviceID = devices["SPECTRAL_PAYOUT-0"]
                    val coinDeviceID = devices["SMART_COIN_SYSTEM-1"]
                    var totalLevelsCount = 0
                    var billBaselineTotal = 0
                    var billCurrentTotal = 0
                    var billSessionDelta = 0
                    var coinBaselineTotal = 0
                    var coinCurrentTotal = 0
                    var coinSessionDelta = 0
                    
                    if (billDeviceID != null) {
                        billBaselineTotal = tracker.getDeviceBaselineCents(billDeviceID)
                        billCurrentTotal = tracker.getDeviceCurrentCents(billDeviceID)
                        billSessionDelta = tracker.getDeviceSessionCents(billDeviceID)
                        val billLevels = tracker.getDeviceCurrentLevels(billDeviceID)
                        totalLevelsCount += billLevels.size
                    }
                    
                    if (coinDeviceID != null) {
                        coinBaselineTotal = tracker.getDeviceBaselineCents(coinDeviceID)
                        coinCurrentTotal = tracker.getDeviceCurrentCents(coinDeviceID)
                        coinSessionDelta = tracker.getDeviceSessionCents(coinDeviceID)
                        val coinLevels = tracker.getDeviceCurrentLevels(coinDeviceID)
                        totalLevelsCount += coinLevels.size
                    }
                    
                    Log.e(TAG, "现金支付：超时（${elapsed}ms），已收款=${collectedAmount}€ (${collectedCents}分)，目标=${targetAmount}€ (${targetAmountCents}分)")
                    Log.e(TAG, "现金支付：超时详情 - 纸币器: baselineTotal=$billBaselineTotal, currentTotal=$billCurrentTotal, sessionDelta=$billSessionDelta; 硬币器: baselineTotal=$coinBaselineTotal, currentTotal=$coinCurrentTotal, sessionDelta=$coinSessionDelta; levels条目数=$totalLevelsCount")
                    handlePaymentFailure("支付超时（${elapsed/1000}秒内未达到目标金额），请重试")
                    return
                }
                
                // 轮询所有设备的库存（基于库存差值计算本次会话累计金额）
                for ((deviceName, deviceID) in devices) {
                    try {
                        val levelsResponse = cashDeviceRepository.pollLevels(deviceID)
                        // amountTracker 已在 pollLevels 中更新
                        // 如果读取失败（error != null），保留上一次成功值，不中断支付流程
                    } catch (e: Exception) {
                        Log.w(TAG, "现金支付：轮询库存异常: deviceName=$deviceName, deviceID=$deviceID", e)
                        // 继续轮询其他设备，不中断支付流程
                    }
                }
                
                val currentTotalCents = tracker.getTotalCents()
                val currentTotalAmount = tracker.getTotalAmount()
                
                // 超时逻辑保留，但日志里要打印：baselineTotal / currentTotal / sessionDelta / levels条目数
                val billDeviceID = devices["SPECTRAL_PAYOUT-0"]
                val coinDeviceID = devices["SMART_COIN_SYSTEM-1"]
                var totalLevelsCount = 0
                var billBaselineTotal = 0
                var billCurrentTotal = 0
                var billSessionDelta = 0
                var coinBaselineTotal = 0
                var coinCurrentTotal = 0
                var coinSessionDelta = 0
                
                if (billDeviceID != null) {
                    billBaselineTotal = tracker.getDeviceBaselineCents(billDeviceID)
                    billCurrentTotal = tracker.getDeviceCurrentCents(billDeviceID)
                    billSessionDelta = tracker.getDeviceSessionCents(billDeviceID)
                    val billLevels = tracker.getDeviceCurrentLevels(billDeviceID)
                    totalLevelsCount += billLevels.size
                }
                
                if (coinDeviceID != null) {
                    coinBaselineTotal = tracker.getDeviceBaselineCents(coinDeviceID)
                    coinCurrentTotal = tracker.getDeviceCurrentCents(coinDeviceID)
                    coinSessionDelta = tracker.getDeviceSessionCents(coinDeviceID)
                    val coinLevels = tracker.getDeviceCurrentLevels(coinDeviceID)
                    totalLevelsCount += coinLevels.size
                }
                
                Log.d(TAG, "现金支付：等待收款中...（已等待${elapsed}ms，已收款=${currentTotalAmount}€ (${currentTotalCents}分)，目标=${targetAmount}€ (${targetAmountCents}分)）")
                Log.d(TAG, "现金支付：金额详情 - 纸币器: baselineTotal=$billBaselineTotal, currentTotal=$billCurrentTotal, sessionDelta=$billSessionDelta; 硬币器: baselineTotal=$coinBaselineTotal, currentTotal=$coinCurrentTotal, sessionDelta=$coinSessionDelta; levels条目数=$totalLevelsCount")
                
                delay(500)  // 每 500ms 轮询一次（300~800ms 范围内）
            }
            
            // 3. 收款完成
            val collectedCents = tracker.getTotalCents()
            val collectedAmount = tracker.getTotalAmount()
            val paymentDuration = System.currentTimeMillis() - paymentStartTime
            
            if (collectedCents >= targetAmountCents) {
                Log.d(TAG, "现金支付：收款完成（耗时${paymentDuration}ms），已收款=${collectedAmount}€ (${collectedCents}分)，目标=${targetAmount}€ (${targetAmountCents}分)")
                
                // 支付成功
                val successState = stateMachine.paymentSuccess(_flowState.value)
                if (successState != null) {
                    _flowState.value = successState
                    Log.d(TAG, "现金支付处理完成，状态: SUCCESS")
                } else {
                    Log.e(TAG, "现金支付：状态机转换失败（paymentSuccess 返回 null）")
                    handlePaymentFailure("状态机转换失败")
                }
            } else {
                Log.e(TAG, "现金支付：收款未完成，已收款=${collectedAmount}€ (${collectedCents}分)，目标=${targetAmount}€ (${targetAmountCents}分)")
                handlePaymentFailure("收款未完成")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "现金支付异常", e)
            handlePaymentFailure("支付异常: ${e.message}")
        }
    }
    
    /**
     * 支付失败（供外部调用）
     */
    fun handlePaymentFailure(errorMessage: String) {
        val currentState = _flowState.value
        val newState = stateMachine.paymentFailed(currentState, errorMessage)
        if (newState != null) {
            _flowState.value = newState
            Log.d(TAG, "支付失败: $errorMessage")
        }
    }
    
    /**
     * 启动洗车（支付成功后）
     */
    fun startWashing(): Boolean {
        val currentState = _flowState.value
        val newState = stateMachine.startWashing(currentState)
        return if (newState != null) {
            _flowState.value = newState
            Log.d(TAG, "启动洗车")
            true
        } else {
            Log.w(TAG, "无法启动洗车，当前状态: ${currentState.status}")
            false
        }
    }
    
    /**
     * 等待洗车机（洗车机不空闲）
     */
    fun waitForMachine(): Boolean {
        val currentState = _flowState.value
        val newState = stateMachine.waitForMachine(currentState)
        return if (newState != null) {
            _flowState.value = newState
            Log.d(TAG, "等待洗车机空闲")
            true
        } else {
            Log.w(TAG, "无法进入等待状态，当前状态: ${currentState.status}")
            false
        }
    }
    
    /**
     * 从等待继续（洗车机已空闲）
     */
    fun proceedFromWaiting(): Boolean {
        val currentState = _flowState.value
        val newState = stateMachine.proceedFromWaiting(currentState)
        return if (newState != null) {
            _flowState.value = newState
            Log.d(TAG, "从等待继续，启动洗车")
            true
        } else {
            Log.w(TAG, "无法从等待继续，当前状态: ${currentState.status}")
            false
        }
    }
    
    /**
     * 完成流程
     */
    fun completeFlow(): Boolean {
        val currentState = _flowState.value
        val newState = stateMachine.completeFlow(currentState)
        return if (newState != null) {
            _flowState.value = newState
            Log.d(TAG, "支付流程已完成")
            true
        } else {
            Log.w(TAG, "无法完成流程，当前状态: ${currentState.status}")
            false
        }
    }
    
    /**
     * 取消支付
     */
    fun cancelPayment(): Boolean {
        val currentState = _flowState.value
        val newState = stateMachine.cancelPayment(currentState)
        return if (newState != null) {
            _flowState.value = newState
            Log.d(TAG, "支付已取消")
            true
        } else {
            Log.w(TAG, "无法取消支付，当前状态: ${currentState.status}")
            false
        }
    }
    
    /**
     * 重试支付
     */
    fun retryPayment(): Boolean {
        val currentState = _flowState.value
        val newState = stateMachine.retryPayment(currentState)
        return if (newState != null) {
            _flowState.value = newState
            Log.d(TAG, "重试支付")
            true
        } else {
            Log.w(TAG, "无法重试支付，当前状态: ${currentState.status}")
            false
        }
    }
    
    /**
     * 重置状态（用于重新开始流程）
     */
    fun reset() {
        Log.d(TAG, "重置支付状态")
        _flowState.value = PaymentFlowState(status = PaymentFlowStatus.NOT_STARTED)
    }
    
    /**
     * 获取当前流程状态
     */
    fun getCurrentFlowStatus(): PaymentFlowStatus = _flowState.value.status
}

/**
 * 支付流程状态（兼容旧接口）
 */
data class PaymentState(
    val selectedProgram: WashProgram? = null,
    val selectedPaymentMethod: PaymentMethod? = null,
    val paymentConfirmed: Boolean = false,
    val isPaymentProcessing: Boolean = false,
    val paymentSuccess: Boolean = false,
    val collectedAmount: Double = 0.0,      // 已收款金额（欧元）
    val targetAmount: Double = 0.0,           // 目标金额（欧元）
    val isPaymentComplete: Boolean = false    // 是否已收齐
)
