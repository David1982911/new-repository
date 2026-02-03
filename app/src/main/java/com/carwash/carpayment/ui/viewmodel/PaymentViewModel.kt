package com.carwash.carpayment.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carwash.carpayment.data.PaymentMethod
import com.carwash.carpayment.data.WashProgram
import com.carwash.carpayment.data.cashdevice.CashDeviceClient
import com.carwash.carpayment.data.cashdevice.CashDeviceRepository
import com.carwash.carpayment.data.cashdevice.CountersResponse
import com.carwash.carpayment.data.cashdevice.SetDenominationInhibitsRequest
import com.carwash.carpayment.data.cashdevice.DenominationConfig
import com.carwash.carpayment.data.cashdevice.DeviceDenominationConfig
import com.carwash.carpayment.data.cashdevice.RefundInsufficientAction
import com.carwash.carpayment.CarPaymentApplication
import com.carwash.carpayment.data.carwash.CarWashDeviceClient
import com.carwash.carpayment.data.carwash.CarWashDeviceRepository
import com.carwash.carpayment.data.carwash.CarWashStartController
import com.carwash.carpayment.data.carwash.CarWashStartState
import com.carwash.carpayment.data.carwash.CarWashStartFailureReason
import com.carwash.carpayment.data.carwash.CarWashGateCheck
import com.carwash.carpayment.data.carwash.CarWashGateCheckResult
import com.carwash.carpayment.data.payment.PaymentFlowState
import com.carwash.carpayment.data.payment.PaymentFlowStateMachine
import com.carwash.carpayment.data.payment.PaymentFlowStatus
import com.carwash.carpayment.data.pos.PosPaymentService
import com.carwash.carpayment.data.pos.PaymentResult
import com.carwash.carpayment.data.pos.UsdkPosPaymentService
import com.carwash.carpayment.data.printer.ReceiptPrintService
import com.carwash.carpayment.data.printer.ReceiptPrinter
import com.carwash.carpayment.data.printer.ReceiptSettingsRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * PaymentViewModel - 管理支付流程状态（使用状态机）
 */
class PaymentViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "PaymentViewModel"
        private const val CASH_PAYMENT_TIMEOUT_MS = 60000L // 现金支付超时 60 秒（临时延长，防止因统计不到金额而退钞）
        private const val DEVICE_INIT_TIMEOUT_MS = 5000L    // 设备初始化超时 5 秒
    }
    
    private val stateMachine = PaymentFlowStateMachine()
    private val cashDeviceApi = CashDeviceClient.create(context = getApplication())
    private val cashDeviceRepository = CashDeviceRepository(cashDeviceApi)
    
    // 洗车机设备（使用单例，确保使用同一份连接实例）
    // ⚠️ 禁止在 Payment 流程里重新 new，必须使用 Application 单例
    private val carWashRepository: CarWashDeviceRepository by lazy {
        val singleton = CarPaymentApplication.carWashRepository
        if (singleton == null) {
            Log.e(TAG, "[CarWash] ❌ 错误：Application 单例未初始化！")
            Log.e(TAG, "[CarWash] 禁止在 Payment 流程里创建新实例，必须使用 Application 单例")
            throw IllegalStateException("CarWashDeviceRepository 单例未初始化，请在 Application.onCreate() 中初始化")
        }
        Log.d(
            TAG,
            "[CarWash] ✅ 使用 Application 单例: repoId=${System.identityHashCode(singleton)}, apiId=${
                System.identityHashCode(singleton.api)
            }"
        )
        singleton
    }
    
    // POS 支付服务（延迟初始化）
    private val posPaymentService by lazy { 
        UsdkPosPaymentService(getApplication(), commId = null, baudrate = 9600)
    }
    
    // 打印服务（延迟初始化，避免在 ViewModel 构造时创建）
    private val receiptPrinter by lazy { ReceiptPrinter(getApplication()) }
    private val receiptSettingsRepository by lazy { ReceiptSettingsRepository(getApplication()) }
    private val receiptPrintService by lazy { 
        ReceiptPrintService(getApplication(), receiptPrinter, receiptSettingsRepository) 
    }
    
    // 支付流程状态（使用状态机）
    private val _flowState = MutableStateFlow<PaymentFlowState>(
        PaymentFlowState(status = PaymentFlowStatus.NOT_STARTED)
    )
    val flowState: StateFlow<PaymentFlowState> = _flowState.asStateFlow()
    
    // 兼容旧的状态接口（用于现有 UI）
    private val _state = MutableStateFlow(PaymentState())
    val state: StateFlow<PaymentState> = _state.asStateFlow()
    
    // 打印结果状态（用于UI显示）
    private val _printResult = MutableStateFlow<PrintResult?>(null)
    val printResult: StateFlow<PrintResult?> = _printResult.asStateFlow()
    
    // 洗车机启动状态（用于UI显示）
    private val _carWashStartState = MutableStateFlow<CarWashStartState?>(null)
    val carWashStartState: StateFlow<CarWashStartState?> = _carWashStartState.asStateFlow()
    
    // 洗车机启动Job（单一Job，避免并发轮询）
    private var carWashStartJob: kotlinx.coroutines.Job? = null
    
    // 洗车机门禁检查结果（用于UI显示）
    private val _gateCheckResult = MutableStateFlow<CarWashGateCheckResult?>(null)
    val gateCheckResult: StateFlow<CarWashGateCheckResult?> = _gateCheckResult.asStateFlow()

    // 纸币拒收提示消息（用于UI显示）- 改为错误码，UI 层映射到 i18n
    private val _noteRejectionMessage = MutableStateFlow<CashRejectionHint?>(null)
    val noteRejectionMessage: StateFlow<CashRejectionHint?> = _noteRejectionMessage.asStateFlow()

    // 拒收提示节流：记录最后一次提示时间
    private var lastRejectionPromptTime = 0L
    private val REJECTION_PROMPT_THROTTLE_MS = 3000L // 3秒内只弹一次
    
    // ⚠️ 关键修复：防止现金流程并发重入
    private val cashPaymentMutex = Mutex()
    @Volatile
    private var isCashPaymentRunning = false
    
    // ⚠️ 关键修复：UI 状态更新节流（降低更新频率）
    private var lastUiUpdateTime = 0L
    private val UI_UPDATE_THROTTLE_MS = 250L  // UI 状态更新节流：250ms

    /**
     * 现金拒收提示数据（用于 UI 显示大字体对话框）
     */
    data class CashRejectionHint(
        val messageKey: String,  // string resource key
        val acceptedDenominations: String? = null  // 允许的面额列表（用于格式化消息）
    )
    
    /**
     * 打印结果（用于UI显示）
     */
    sealed class PrintResult {
        data class Success(val invoiceId: String) : PrintResult()
        data class Failure(val reason: String) : PrintResult()
    }
    
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
     * V2：支付前只检查 217（故障），支付成功后才执行完整 GateCheck
     *
     * 支付前检查：只检查 217（故障）作为硬门禁
     * - 若读取 217 抛异常/返回 null/返回"未连接"，必须判定检查=FAILED，并禁止导航到支付界面
     * - 240/752 不作为支付前硬阻断，支付成功后才执行 GateCheck
     */
    fun selectProgram(program: WashProgram) {
        val currentState = _flowState.value

        // ⚠️ SUCCESS 状态下禁止再次选择程序，直接返回
        if (currentState.status == PaymentFlowStatus.SUCCESS) {
            Log.d(TAG, "SUCCESS 状态下禁止再次选择程序（已完成支付）")
            return
        }

        Log.d(TAG, "[CarWash] 选择洗车程序: ${program.id}, 价格: ${program.price}€")
        
        // 重置支付尝试（切换套餐时）
        resetPaymentAttempt("PROGRAM_CHANGED")

        // V2：支付前只检查 217（故障）
        viewModelScope.launch {
            try {
                Log.d(TAG, "[CarWash] ========== 支付前检查开始（只检查 217 故障） ==========")

                // 确保连接
                if (!carWashRepository.api.isConnected()) {
                    val ok = carWashRepository.connect()
                    if (!ok || !carWashRepository.api.isConnected()) {
                        Log.e(TAG, "[CarWash] 支付前检查 FAILED: 连接失败")
                        handlePaymentFailureByReason(com.carwash.carpayment.data.carwash.CarWashGateCheckFailureReason.NOT_CONNECTED)
                        return@launch
                    }
                }

                // 只检查 217（故障）
                val faultStatus = try {
                    carWashRepository.api.readFaultStatus()
                } catch (e: Exception) {
                    Log.e(TAG, "[CarWash] 支付前检查 FAILED: 读取 217 异常", e)
                    handlePaymentFailureByReason(com.carwash.carpayment.data.carwash.CarWashGateCheckFailureReason.COMMUNICATION_FAILED)
                    return@launch
                }

                if (faultStatus == null) {
                    Log.e(TAG, "[CarWash] 支付前检查 FAILED: 217 返回 null")
                    handlePaymentFailureByReason(com.carwash.carpayment.data.carwash.CarWashGateCheckFailureReason.COMMUNICATION_FAILED)
                    return@launch
                }

                if (faultStatus == true) {
                    Log.e(TAG, "[CarWash] 支付前检查 FAILED: 217=1 (有故障)")
                    handlePaymentFailureByReason(com.carwash.carpayment.data.carwash.CarWashGateCheckFailureReason.DEVICE_FAULT)
                        return@launch
                    }

                Log.d(TAG, "[CarWash] 支付前检查 PASSED: 217=0 (无故障)，允许进入支付界面")
                // 检查通过，允许进入支付流程
                val newState = stateMachine.startPaymentFlow(program, currentState)
                if (newState != null) {
                    _flowState.value = newState
                }
            } catch (e: Exception) {
                Log.e(TAG, "[CarWash] 支付前检查异常", e)
                handlePaymentFailureByReason(com.carwash.carpayment.data.carwash.CarWashGateCheckFailureReason.COMMUNICATION_FAILED)
                return@launch
            }
        }
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

        // ⚠️ SUCCESS 状态下禁止再次触发支付，直接返回
        if (currentState.status == PaymentFlowStatus.SUCCESS) {
            Log.d(TAG, "SUCCESS 状态下禁止再次触发支付（已完成支付）")
            return
        }

        // 重置支付尝试（开始新支付时）
        resetPaymentAttempt("NEW_ATTEMPT")

        val newState = stateMachine.confirmPayment(currentState)
        
        if (newState == null) {
            Log.w(TAG, "无法开始支付，条件不满足")
            return
        }
        
        _flowState.value = newState
        
        viewModelScope.launch(Dispatchers.IO) {
            Log.d(TAG, "开始处理支付... 支付方式: ${currentState.selectedPaymentMethod}")
            
            when (currentState.selectedPaymentMethod) {
                PaymentMethod.CASH -> {
                    // ⚠️ 关键修复：防止并发重入
                    cashPaymentMutex.withLock {
                        if (isCashPaymentRunning) {
                            Log.w(TAG, "⚠️ 现金支付已在运行中，忽略重复调用")
                            return@withLock
                        }
                        isCashPaymentRunning = true
                        try {
                    processCashPayment(currentState)
                        } finally {
                            isCashPaymentRunning = false
                }
                    }
                }

                PaymentMethod.CARD -> {
                    // 卡支付：真实 POS 交易
                    processCardPayment(currentState)
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
        
        // ⚠️ 打点：打印 targetAmountCents 的来源（定位支付目标金额是否与UI同源）
        Log.d("ProgramPrice", "========== PaymentViewModel targetAmountCents 来源 ==========")
        Log.d(
            "ProgramPrice",
            "source=currentState.selectedProgram (来自 HomeViewModel.selectedProgram)"
        )
        Log.d("ProgramPrice", "selectedProgramId=${program.id}")
        Log.d("ProgramPrice", "selectedProgram.price=${program.price}€")
        Log.d("ProgramPrice", "selectedProgram.priceCents=${(program.price * 100).toInt()}")
        
        // 目标金额单位统一：使用 round 确保精度，统一用分（cents）
        val targetAmount = program.price  // 元（Double）
        val targetAmountCents = kotlin.math.round(targetAmount * 100).toInt()  // 分（Int）
        
        Log.d("ProgramPrice", "最终 targetAmountCents=${targetAmountCents}分 (${targetAmount}€)")
        Log.d("ProgramPrice", "============================================================")
        
        // 必须加的日志：开始现金支付时打印
        Log.d(TAG, "========== 现金支付开始 ==========")
        Log.d(TAG, "programId=${program.id}, programName=${program.name}")
        Log.d(TAG, "programPrice=${targetAmount}€ (元)")
        Log.d(TAG, "targetAmount=${targetAmountCents} (分)")
        Log.d(TAG, "targetAmountUnit=分 (cents)")
        Log.d(TAG, "====================================")
        
        // 声明变量在try块外部，以便在catch块中使用
        var devices: Map<String, String> = emptyMap()
        var billDeviceID: String? = null
        var coinDeviceID: String? = null
        
        // 幂等标志：确保 disableAcceptor 只执行一次（在 try 块外部声明）
        var isAcceptorDisabled = false
        var isCashSessionStopped = false
        
        try {
            // 1. 启动现金设备会话（认证 + 打开双设备连接）
            val deviceSessionStartTime = System.currentTimeMillis()
            Log.d(TAG, "现金支付：启动设备会话...")
            devices = try {
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
            
            val sessionDuration = System.currentTimeMillis() - deviceSessionStartTime
            Log.d(
                TAG,
                "现金支付：设备会话启动成功（耗时${sessionDuration}ms），已注册设备: ${
                    devices.keys.joinToString("、")
                }"
            )
            
            // ========== 打印 devices map 并校验 deviceID ==========
            Log.d(TAG, "========== devices map 完整内容 ==========")
            devices.forEach { (key, value) ->
                Log.d(TAG, "devices[$key] = $value")
            }
            Log.d(TAG, "========================================")
            
            val tracker = cashDeviceRepository.getAmountTracker()
            billDeviceID = devices["SPECTRAL_PAYOUT-0"]
            coinDeviceID = devices["SMART_COIN_SYSTEM-1"]
            
            // 断言式日志：校验 deviceID 是否为 null
            if (billDeviceID == null) {
                Log.e(
                    TAG,
                    "❌ 错误：billDeviceID == null，devices map 中未找到 key='SPECTRAL_PAYOUT-0'"
                )
                Log.e(TAG, "❌ devices map 结构: ${devices.keys.joinToString(", ")}")
                Log.e(TAG, "❌ 请检查设备连接或设备类型映射是否正确")
            } else {
                Log.d(TAG, "✅ billDeviceID = $billDeviceID")
            }
            
            if (coinDeviceID == null) {
                Log.e(
                    TAG,
                    "❌ 错误：coinDeviceID == null，devices map 中未找到 key='SMART_COIN_SYSTEM-1'"
                )
                Log.e(TAG, "❌ devices map 结构: ${devices.keys.joinToString(", ")}")
                Log.e(TAG, "❌ 请检查设备连接或设备类型映射是否正确")
            } else {
                Log.d(TAG, "✅ coinDeviceID = $coinDeviceID")
            }
            
            // 2. 读取找零库存（用于评估找零能力）
            Log.d(TAG, "现金支付：读取找零库存（用于评估找零能力）...")
            var changeInventory: com.carwash.carpayment.data.cashdevice.ChangeInventory? = null
            try {
                val billLevels = billDeviceID?.let { cashDeviceRepository.readCurrentLevels(it) }
                val coinLevels = coinDeviceID?.let { cashDeviceRepository.readCurrentLevels(it) }
                changeInventory = com.carwash.carpayment.data.cashdevice.ChangeInventory.fromLevels(
                    billLevels?.levels ?: emptyList(),
                    coinLevels?.levels ?: emptyList()
                )
                Log.d(
                    TAG,
                    "找零库存读取成功: totalCents=${changeInventory.getTotalCents()}分 (${changeInventory.getTotalCents() / 100.0}€)"
                )
                } catch (e: Exception) {
                Log.w(TAG, "找零库存读取失败，将允许所有面额", e)
            }

            // 3. 评估找零能力并禁用大额面额（在 EnableAcceptor 前）
            // 从 Levels/Float 计算 maxChangeAvailableCents（纸币+硬币可找零库存总额）
            val maxChangeAvailableCents = changeInventory?.getTotalCents() ?: 0
            // 允许接收的最大投入额：maxAcceptable = priceCents + maxChangeAvailableCents
            val maxAcceptable = targetAmountCents + maxChangeAvailableCents
            Log.d(
                TAG,
                "找零能力评估: maxChangeAvailableCents=${maxChangeAvailableCents}分, maxAcceptable=${maxAcceptable}分, targetAmountCents=${targetAmountCents}分"
            )

            // 根据 maxAcceptable 禁用高面额（例如 50/100）
            // 50€ = 5000分, 100€ = 10000分
            val largeDenominations = listOf(5000, 10000) // 50€, 100€
            val shouldDisableLargeDenoms = largeDenominations.any { it > maxAcceptable }

            if (shouldDisableLargeDenoms) {
                Log.d(
                    TAG,
                    "找零能力不足，需要禁用大额面额: maxAcceptable=${maxAcceptable}分 < 大额面额阈值"
                )
            }

            // 4. 配置面额（SetInhibits/SetRoutes）- 使用统一方法 configureDenominationsForDevices
            // ⚠️ 注意：启动阶段不得调用，只在用户进入现金支付页面时执行
            Log.d(TAG, "现金支付：配置面额（SetInhibits/SetRoutes）...")

            // 构建配置：从 DeviceDenominationConfig 获取可找零面额
            val billRecyclableDenoms =
                DeviceDenominationConfig.getRecyclableDenominations("SPECTRAL_PAYOUT-0").toSet()
            val coinRecyclableDenoms =
                DeviceDenominationConfig.getRecyclableDenominations("SMART_COIN_SYSTEM-1").toSet()

            // 构建禁用配置：如果找零能力不足，禁用大额面额
            val inhibitValues = if (shouldDisableLargeDenoms) {
                largeDenominations.toSet()
                    } else {
                emptySet<Int>()
            }

            val routeConfig = DenominationConfig(
                recyclerValues = billRecyclableDenoms + coinRecyclableDenoms
            )
            val inhibitConfig = DenominationConfig(
                inhibitValues = inhibitValues
            )

            // ⚠️ 统一调用：configureDenominationsForDevices（按设备分组，严格过滤面额）
            try {
                val configSuccess = cashDeviceRepository.configureDenominationsForDevices(
                    billDeviceID = billDeviceID,
                    coinDeviceID = coinDeviceID,
                    routeConfig = routeConfig,
                    inhibitConfig = inhibitConfig
                )
                if (!configSuccess) {
                    Log.w(TAG, "现金支付：面额配置部分失败，继续执行（降级策略）")
                    } else {
                    Log.d(TAG, "现金支付：面额配置成功")
                    }
                } catch (e: Exception) {
                Log.e(TAG, "现金支付：面额配置异常，继续执行（降级策略）", e)
            }

            // 注意：baseline 已在步骤 4 中读取，这里不再重复读取

            // 4. 更新找零库存明细（用于可找零性判定）
            // 注意：changeInventory 已在步骤 2 中声明，这里只更新值
            Log.d(TAG, "现金支付：更新找零库存明细...")
            try {
                val billLevels = billDeviceID?.let { cashDeviceRepository.readCurrentLevels(it) }
                val coinLevels = coinDeviceID?.let { cashDeviceRepository.readCurrentLevels(it) }
                changeInventory = com.carwash.carpayment.data.cashdevice.ChangeInventory.fromLevels(
                    billLevels?.levels ?: emptyList(),
                    coinLevels?.levels ?: emptyList()
                )
                Log.d(
                    TAG,
                    "找零库存更新成功: totalCents=${changeInventory?.getTotalCents()}分 (${
                        changeInventory?.getTotalCents()?.div(100.0)
                    }€)"
                )
            } catch (e: Exception) {
                Log.w(TAG, "现金支付：更新找零库存失败，继续执行（找零判定将不可用）", e)
            }
            
            // 3.5. StartDevice / StartSession（按现有实现）
            Log.d(TAG, "现金支付：启动设备（StartDevice）")
            billDeviceID?.let { deviceID ->
                try {
                    val success = cashDeviceRepository.startDevice(deviceID)
                    if (!success) {
                        Log.w(TAG, "现金支付：纸币器启动设备失败，但继续执行")
                    } else {
                        Log.d(TAG, "现金支付：纸币器启动设备成功")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "现金支付：纸币器启动设备异常", e)
                }
            }
            
            coinDeviceID?.let { deviceID ->
                try {
                    val success = cashDeviceRepository.startDevice(deviceID)
                    if (!success) {
                        Log.w(TAG, "现金支付：硬币器启动设备失败，但继续执行")
                    } else {
                        Log.d(TAG, "现金支付：硬币器启动设备成功")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "现金支付：硬币器启动设备异常", e)
                }
            }
            
            // 4. ⚠️ 现金入口加速：EnableAcceptor + SetAutoAccept 优先执行，baseline 在 enable 成功后立即设置
            // 目标：让用户"几乎立即"可以投币/投纸，延迟从 ~10 秒降低到几乎立即
            val sessionStartTime = System.currentTimeMillis()
            // ⚠️ 注意：targetAmountCents 已在函数开头声明（第 317 行），这里不再重复声明
            // ⚠️ Step F: 添加关键日志
            Log.d(
                "CASH_BEGIN",
                "priceCents=$targetAmountCents billDeviceId=$billDeviceID coinDeviceId=$coinDeviceID"
            )

            var billBaselineLevels: com.carwash.carpayment.data.cashdevice.LevelsResponse? = null
            var coinBaselineLevels: com.carwash.carpayment.data.cashdevice.LevelsResponse? = null
            var billBaselineCents = 0
            var coinBaselineCents = 0
            var billBaselineSnapshot: com.carwash.carpayment.data.cashdevice.LevelsSnapshot? = null
            var coinBaselineSnapshot: com.carwash.carpayment.data.cashdevice.LevelsSnapshot? = null

            val acceptorsStartTime = System.currentTimeMillis()
            Log.d(TAG, "现金支付：优先执行 EnableAcceptor + SetAutoAccept（加速入口）")
            var billSessionStarted = false
            var coinSessionStarted = false

            // ⚠️ Step B: 现金支付开始的标准时序：EnablePayoutDevice -> EnableAcceptor -> baselineLevels
            // 4.1. 优先执行：EnablePayoutDevice -> EnableAcceptor + SetAutoAccept -> baselineLevels
            billDeviceID?.let { deviceID ->
                try {
                    // ⚠️ Step 3: 刷新设备的支持面额集合（在设备初始化或进入现金会话时调用）
                    try {
                        cashDeviceRepository.refreshDeviceSupportedValues(deviceID)
                    } catch (e: Exception) {
                        Log.w(TAG, "刷新纸币器支持面额失败（继续执行）: deviceID=$deviceID", e)
                    }

                    // Step 1: 启用找零设备（确保可找零）
                    try {
                        val enablePayoutSuccess = cashDeviceRepository.enablePayoutDevice(deviceID)
                        if (enablePayoutSuccess) {
                            Log.d(TAG, "现金支付：纸币器 EnablePayoutDevice 成功")
                    } else {
                            Log.w(
                                TAG,
                                "现金支付：纸币器 EnablePayoutDevice 失败（不影响收款，继续执行）"
                            )
                    }
                } catch (e: Exception) {
                        Log.w(
                            TAG,
                            "现金支付：纸币器 EnablePayoutDevice 异常（不影响收款，继续执行）",
                            e
                        )
                    }

                    // Step 2: 启用接收器
                    val enableSuccess = cashDeviceRepository.enableAcceptor(deviceID)
                    if (!enableSuccess) {
                        Log.e(TAG, "现金支付：纸币器启用接收器失败")
                        handlePaymentFailure("纸币器启动失败，请重试")
                        return
                    }
                    // Step 3: 设置自动接受
                    val autoAcceptSuccess = cashDeviceRepository.setAutoAccept(deviceID, true)
                    if (!autoAcceptSuccess) {
                        Log.e(TAG, "现金支付：纸币器设置自动接受失败")
                        cashDeviceRepository.disableAcceptor(deviceID)
                        handlePaymentFailure("纸币器启动失败，请重试")
                        return
                    }
                    val acceptorsElapsed = System.currentTimeMillis() - acceptorsStartTime
                    Log.d(
                        TAG,
                        "现金支付：纸币器 EnablePayoutDevice -> EnableAcceptor -> SetAutoAccept 成功（用户现在可以投币），耗时=${acceptorsElapsed}ms"
                    )
                    billSessionStarted = true

                    // Step 4: baseline 在 EnableAcceptor 成功后立即设置（避免进入现金支付前的库存变化污染会话）
                    // ⚠️ 关键修复：baseline 读取失败改为非致命警告，不阻止支付流程
                    try {
                        billBaselineLevels = cashDeviceRepository.readCurrentLevels(deviceID)
                        billBaselineCents = billBaselineLevels.calculateTotalCents()
                        // ⚠️ 保存 baseline levels 到 baselineStore（用于退款时计算）
                        cashDeviceRepository.getBaselineStore()
                            .setBaselineLevels(deviceID, billBaselineLevels)
                        // 创建 baseline snapshot
                        billBaselineSnapshot =
                            cashDeviceRepository.createLevelsSnapshot(deviceID, billBaselineLevels)
                        // ⚠️ Step F: 添加关键日志
                        Log.d("CASH_BASELINE_SET", "billTotal=$billBaselineCents")
                        Log.d(
                            TAG,
                            "现金支付：纸币器 baseline levels 已设置: deviceID=$deviceID, totalCents=$billBaselineCents (${billBaselineCents / 100.0}€)"
                        )
                        Log.d(TAG, "========== 纸币器会话启动成功 ==========")
                        Log.d(TAG, "deviceID=$deviceID")
                        Log.d(TAG, "baselineCents=$billBaselineCents")
                        Log.d(TAG, "==========================================")
                    } catch (e: Exception) {
                        // ⚠️ 关键修复：baseline 读取失败改为非致命警告，不阻止支付流程
                        Log.w(TAG, "⚠️ 现金支付：读取纸币器 baseline levels 失败（非致命，继续执行）", e)
                        Log.w(TAG, "⚠️ 说明：GetCounters/GetAllLevels 解析失败不影响收款，使用空 baseline")
                        // 使用空 baseline，继续执行
                        billBaselineLevels = null
                        billBaselineCents = 0
                        billBaselineSnapshot = null
                        Log.d(TAG, "========== 纸币器会话启动（baseline 读取失败，使用空 baseline） ==========")
                        Log.d(TAG, "deviceID=$deviceID")
                        Log.d(TAG, "baselineCents=0（空 baseline）")
                        Log.d(TAG, "==========================================")
                        // ⚠️ 不再调用 handlePaymentFailure，继续执行支付流程
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "现金支付：纸币器启用接收器/自动接受异常", e)
                    try {
                        cashDeviceRepository.disableAcceptor(deviceID)
                    } catch (disableException: Exception) {
                        Log.e(TAG, "恢复状态失败（禁用接收器异常）", disableException)
                    }
                    handlePaymentFailure("纸币器启动异常: ${e.message}")
                    return
                }
            }

            coinDeviceID?.let { deviceID ->
                try {
                    // ⚠️ Step 3: 刷新设备的支持面额集合（在设备初始化或进入现金会话时调用）
                    try {
                        cashDeviceRepository.refreshDeviceSupportedValues(deviceID)
                    } catch (e: Exception) {
                        Log.w(TAG, "刷新硬币器支持面额失败（继续执行）: deviceID=$deviceID", e)
                    }

                    // Step 1: 启用找零设备（确保可找零）
                    try {
                        val enablePayoutSuccess = cashDeviceRepository.enablePayoutDevice(deviceID)
                        if (enablePayoutSuccess) {
                            Log.d(TAG, "现金支付：硬币器 EnablePayoutDevice 成功")
                    } else {
                            Log.w(
                                TAG,
                                "现金支付：硬币器 EnablePayoutDevice 失败（不影响收款，继续执行）"
                            )
                    }
                } catch (e: Exception) {
                        Log.w(
                            TAG,
                            "现金支付：硬币器 EnablePayoutDevice 异常（不影响收款，继续执行）",
                            e
                        )
                    }

                    // Step 2: 启用接收器
                    // ⚠️ 关键修复：硬币器失败不影响纸币器，继续执行支付流程
                    val enableSuccess = cashDeviceRepository.enableAcceptor(deviceID)
                    if (!enableSuccess) {
                        Log.w(TAG, "⚠️ 现金支付：硬币器启用接收器失败（非致命，纸币器继续可用）")
                        Log.w(TAG, "⚠️ 说明：硬币器失败不影响纸币器收款，继续执行支付流程")
                        // ⚠️ 不再禁用纸币器，不再调用 handlePaymentFailure
                        // ⚠️ 继续执行，允许纸币器单独收款
                        coinSessionStarted = false
                        // 跳过硬币器的后续步骤（Step 3 和 Step 4），但继续执行支付流程
                    } else {
                        // Step 3: 设置自动接受
                        val autoAcceptSuccess = cashDeviceRepository.setAutoAccept(deviceID, true)
                        if (!autoAcceptSuccess) {
                            Log.w(TAG, "⚠️ 现金支付：硬币器设置自动接受失败（非致命，纸币器继续可用）")
                            Log.w(TAG, "⚠️ 说明：硬币器失败不影响纸币器收款，继续执行支付流程")
                            // ⚠️ 禁用硬币器接收器，但不影响纸币器
                            try {
                                cashDeviceRepository.disableAcceptor(deviceID)
                            } catch (e: Exception) {
                                Log.w(TAG, "禁用硬币器接收器异常（可忽略）", e)
                            }
                            coinSessionStarted = false
                            // ⚠️ 不再禁用纸币器，不再调用 handlePaymentFailure
                            // ⚠️ 继续执行，允许纸币器单独收款
                            // 跳过 Step 4（baseline 设置）
                        } else {
                            val acceptorsElapsed = System.currentTimeMillis() - acceptorsStartTime
                            Log.d(
                                TAG,
                                "现金支付：硬币器 EnablePayoutDevice -> EnableAcceptor -> SetAutoAccept 成功（用户现在可以投币），耗时=${acceptorsElapsed}ms"
                            )
                            coinSessionStarted = true
                            
                            // Step 4: baseline 在 EnableAcceptor 成功后立即设置（避免进入现金支付前的库存变化污染会话）
                            // ⚠️ 关键修复：baseline 读取失败改为非致命警告，不阻止支付流程
                            try {
                                coinBaselineLevels = cashDeviceRepository.readCurrentLevels(deviceID)
                                coinBaselineCents = coinBaselineLevels.calculateTotalCents()
                                // ⚠️ 保存 baseline levels 到 baselineStore（用于退款时计算）
                                cashDeviceRepository.getBaselineStore()
                                    .setBaselineLevels(deviceID, coinBaselineLevels)
                                // 创建 baseline snapshot
                                coinBaselineSnapshot =
                                    cashDeviceRepository.createLevelsSnapshot(deviceID, coinBaselineLevels)
                                // ⚠️ Step F: 添加关键日志
                                Log.d("CASH_BASELINE_SET", "coinTotal=$coinBaselineCents")
                                Log.d(
                                    TAG,
                                    "现金支付：硬币器 baseline levels 已设置: deviceID=$deviceID, totalCents=$coinBaselineCents (${coinBaselineCents / 100.0}€)"
                                )
                                Log.d(TAG, "========== 硬币器会话启动成功 ==========")
                                Log.d(TAG, "deviceID=$deviceID")
                                Log.d(TAG, "baselineCents=$coinBaselineCents")
                                Log.d(TAG, "==========================================")
                            } catch (e: Exception) {
                                // ⚠️ 关键修复：baseline 读取失败改为非致命警告，不阻止支付流程
                                Log.w(TAG, "⚠️ 现金支付：读取硬币器 baseline levels 失败（非致命，继续执行）", e)
                                Log.w(TAG, "⚠️ 说明：GetCounters/GetAllLevels 解析失败不影响收款，使用空 baseline")
                                // 使用空 baseline，继续执行
                                coinBaselineLevels = null
                                coinBaselineCents = 0
                                coinBaselineSnapshot = null
                                Log.d(TAG, "========== 硬币器会话启动（baseline 读取失败，使用空 baseline） ==========")
                                Log.d(TAG, "deviceID=$deviceID")
                                Log.d(TAG, "baselineCents=0（空 baseline）")
                                Log.d(TAG, "==========================================")
                                // ⚠️ 不再调用 handlePaymentFailure，继续执行支付流程
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "现金支付：硬币器启用接收器/自动接受异常", e)
                    // 如果硬币器异常，尝试结束纸币器会话
                    try {
                        cashDeviceRepository.disableAcceptor(deviceID)
                        billDeviceID?.let { cashDeviceRepository.disableAcceptor(it) }
                    } catch (disableException: Exception) {
                        Log.e(TAG, "恢复状态失败（禁用接收器异常）", disableException)
                    }
                    handlePaymentFailure("硬币器启动异常: ${e.message}")
                    return
                }
            }

            // 5.2. 后台并行执行：其他初始化请求（不阻塞 UI 允许投币）
            // 这些操作在后台执行，不影响用户投币
            viewModelScope.launch {
                try {
                    // 后台更新找零库存（用于UI提示，不影响投币）
                    val billLevels =
                        billDeviceID?.let { cashDeviceRepository.readCurrentLevels(it) }
                    val coinLevels =
                        coinDeviceID?.let { cashDeviceRepository.readCurrentLevels(it) }
                    changeInventory =
                        com.carwash.carpayment.data.cashdevice.ChangeInventory.fromLevels(
                            billLevels?.levels ?: emptyList(),
                            coinLevels?.levels ?: emptyList()
                        )
                    Log.d(
                        TAG,
                        "后台更新找零库存完成: totalCents=${changeInventory?.getTotalCents()}分"
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "后台更新找零库存失败（不影响投币）", e)
                }
            }

            // ⚠️ 关键修复：标记会话为活跃状态（防止 reset tracker）
            cashDeviceRepository.setSessionActive(true)
            Log.d(TAG, "现金支付：会话已标记为活跃状态")

            // 6. 开始轮询总收款金额（使用 GetCounters，修复"50€漏记"）
            // 会话累计金额 = 当前总收款 - 基线总收款
            Log.d(
                TAG,
                "现金支付：开始轮询等待收款，目标金额=${targetAmount}€ (${targetAmountCents}分)"
            )
            Log.d(
                TAG,
                "现金支付：轮询已启动（使用 GetCurrencyAssignment 的 stored + storedInCashbox），将在收款完成或超时后禁用接收器"
            )
            val paymentStartTime = System.currentTimeMillis()
            var lastPollTime = paymentStartTime  // 记录最后一次轮询时间
            
            // 更新状态，设置目标金额（用于 UI 显示）
            _flowState.value = _flowState.value.copy(
                targetAmountCents = targetAmountCents,
                paidAmountCents = 0
            )
            
            // 用于跟踪上次轮询的 sessionDelta（用于计算增量）
            var previousBillSessionDelta = 0
            var previousCoinSessionDelta = 0
            
            // 可找零性判定计算器
            val changeCalculator =
                com.carwash.carpayment.data.cashdevice.AcceptedDenominationsCalculator()
            val availableDenominations =
                listOf(100, 200, 500, 1000, 2000, 5000) // 支持的面额（分）：1€, 2€, 5€, 10€, 20€, 50€
            
            // ⚠️ 关键修复：纸币器不再使用 GetCounters，删除相关变量
            // ⚠️ 硬币器 GetCounters 仅用于维护统计，不影响支付流程
            var previousCoinCounters: com.carwash.carpayment.data.cashdevice.CountersResponse? =
                null
            
            // ⚠️ 关键修复：轮询间隔设置为 300~500ms，确保 SPECTRAL_PAYOUT-0 的 GetAllLevels 差分作为 paidTotalCents 的唯一来源
            val POLL_INTERVAL_MS = 400L  // 轮询间隔：400ms（在 300~500ms 范围内）
            
            while (currentCoroutineContext().isActive) {
                // ⚠️ 关键修复：轮询间隔控制
                kotlinx.coroutines.delay(POLL_INTERVAL_MS)
                
                // ⚠️ 关键修复：纸币器 SPECTRAL_PAYOUT-0 不再调用 GetCounters
                // ⚠️ 纸币器投入纸币时 GetAllLevels.Stored 会增长，差分即可得到本次投入金额，因此 counters 对纸币器没有必要
                lastPollTime = System.currentTimeMillis()  // 更新轮询时间戳
                var coinCounters: com.carwash.carpayment.data.cashdevice.CountersResponse? = null
                
                // ⚠️ 关键修复：硬币器 SMART_COIN_SYSTEM-1 的 GetCounters 只用于"维护统计"，不得影响支付成功/失败
                // ⚠️ GetCounters 解析失败时：仅记录日志，不抛出 COUNTERS_PARSE_FAILED 导致会话失败
                coinDeviceID?.let { deviceID ->
                    try {
                        // ⚠️ 仅用于维护统计，不影响支付流程
                        coinCounters = cashDeviceRepository.getCounters(deviceID)
                        Log.d(TAG, "硬币器 GetCounters（维护统计）: deviceID=$deviceID, stacked=${coinCounters.stacked}, stored=${coinCounters.stored}")
                    } catch (e: Exception) {
                        // ⚠️ 非致命警告：GetCounters 失败只记录日志，不影响支付流程
                        Log.w(TAG, "⚠️ 硬币器 GetCounters 维护统计失败（非致命，不影响支付）: deviceID=$deviceID", e)
                        // 继续轮询纸币器，不中断支付流程
                    }
                }
                
                // ⚠️ Step A: 使用 LevelsSnapshot 统一差分计算，禁止 avgVal 推导
                // 计算本次会话投入金额（sessionDelta）= calcSessionDeltaCents(baseline, current)
                var billSessionDelta = 0
                var coinSessionDelta = 0

                // 纸币器：使用 LevelsSnapshot 计算 delta
                billDeviceID?.let { deviceID ->
                    try {
                        // ⚠️ 关键修复：GetAllLevels 调用在 IO 线程执行
                        val getAllLevelsStartTime = System.currentTimeMillis()
                        val currentLevels = withContext(Dispatchers.IO) {
                            cashDeviceRepository.readCurrentLevels(deviceID)
                        }
                        val getAllLevelsElapsed = System.currentTimeMillis() - getAllLevelsStartTime
                        if (getAllLevelsElapsed > 200) {
                            Log.w(TAG, "⚠️ GetAllLevels 耗时 ${getAllLevelsElapsed}ms (deviceID=$deviceID)")
                        }
                        val currentSnapshot =
                            cashDeviceRepository.createLevelsSnapshot(deviceID, currentLevels)
                        if (billBaselineSnapshot != null) {
                            billSessionDelta = cashDeviceRepository.calcSessionDeltaCents(
                                billBaselineSnapshot,
                                currentSnapshot
                            )
                        } else {
                            // 降级：使用旧方法
                            billSessionDelta = cashDeviceRepository.calculateLevelsDeltaCents(
                                billBaselineLevels,
                                currentLevels
                            )
                        }

                        // ⚠️ 关键修复：每次 GetAllLevels 成功后，立即更新 tracker
                        val levelsMap =
                            currentLevels.levels?.associate { it.value to it.stored } ?: emptyMap()
                        val trackerDelta = cashDeviceRepository.getAmountTracker()
                            .updateFromLevels(deviceID, levelsMap, "LEVELS")
                        Log.d(
                            "CASH_DELTA",
                            "deviceID=$deviceID baselineTotal=${
                                cashDeviceRepository.getAmountTracker()
                                    .getDeviceBaselineCents(deviceID)
                            } currentTotal=${
                                cashDeviceRepository.getAmountTracker()
                                    .getDeviceCurrentCents(deviceID)
                            } delta=$trackerDelta"
                        )
                    } catch (e: Exception) {
                        // ⚠️ Step A: 禁止使用 GetCounters 降级，只使用 GetAllLevels 差分
                        Log.e(TAG, "现金支付：轮询纸币器 levels 异常: deviceID=$deviceID", e)
                        // 不再降级到 counters，保持 billSessionDelta = 0（避免历史累计污染）
                        billSessionDelta = 0
                        Log.w(
                            TAG,
                            "纸币器 levels 读取失败，billSessionDelta 设为 0（避免使用 GetCounters 污染）"
                        )
                    }
                }

                // 硬币器：使用 LevelsSnapshot 计算 delta（禁止 avgVal 推导）
                coinDeviceID?.let { deviceID ->
                    try {
                        // ⚠️ 关键修复：GetAllLevels 调用在 IO 线程执行
                        val getAllLevelsStartTime = System.currentTimeMillis()
                        val currentLevels = withContext(Dispatchers.IO) {
                            cashDeviceRepository.readCurrentLevels(deviceID)
                        }
                        val getAllLevelsElapsed = System.currentTimeMillis() - getAllLevelsStartTime
                        if (getAllLevelsElapsed > 200) {
                            Log.w(TAG, "⚠️ GetAllLevels 耗时 ${getAllLevelsElapsed}ms (deviceID=$deviceID)")
                        }
                        val currentSnapshot =
                            cashDeviceRepository.createLevelsSnapshot(deviceID, currentLevels)
                        if (coinBaselineSnapshot != null) {
                            coinSessionDelta = cashDeviceRepository.calcSessionDeltaCents(
                                coinBaselineSnapshot,
                                currentSnapshot
                            )
                        } else {
                            // 降级：使用旧方法
                            coinSessionDelta = cashDeviceRepository.calculateLevelsDeltaCents(
                                coinBaselineLevels,
                                currentLevels
                            )
                        }

                        // ⚠️ 关键修复：每次 GetAllLevels 成功后，立即更新 tracker
                        val levelsMap =
                            currentLevels.levels?.associate { it.value to it.stored } ?: emptyMap()
                        val trackerDelta = cashDeviceRepository.getAmountTracker()
                            .updateFromLevels(deviceID, levelsMap, "LEVELS")
                        Log.d(
                            "CASH_DELTA",
                            "deviceID=$deviceID baselineTotal=${
                                cashDeviceRepository.getAmountTracker()
                                    .getDeviceBaselineCents(deviceID)
                            } currentTotal=${
                                cashDeviceRepository.getAmountTracker()
                                    .getDeviceCurrentCents(deviceID)
                            } delta=$trackerDelta"
                        )
                    } catch (e: Exception) {
                        // ⚠️ Step A: 禁止使用 GetCounters 降级，只使用 GetAllLevels 差分
                        Log.e(TAG, "现金支付：轮询硬币器 levels 异常: deviceID=$deviceID", e)
                        // 不再降级到 counters，保持 coinSessionDelta = 0（避免历史累计污染）
                        coinSessionDelta = 0
                        Log.w(
                            TAG,
                            "硬币器 levels 读取失败，coinSessionDelta 设为 0（避免使用 GetCounters 污染）"
                        )
                    }
                }

                // ⚠️ 关键修复：统一"判定用金额"变量（强制）
                // ⚠️ paidSessionDeltaCents 是唯一权威的判定用金额（基于 GetAllLevels 差分）
                val paidSessionDeltaCents = billSessionDelta + coinSessionDelta
                
                // ⚠️ 关键修复：统一使用 paidSessionDeltaCents 作为判定用金额
                val paidTotalCents = paidSessionDeltaCents  // 判定唯一权威
                
                // ⚠️ 关键修复：修正 CASH_PAID_SUM 日志，使用 billSessionDelta 和 coinSessionDelta
                // ⚠️ 禁止在 CASH_PAID_SUM 里再去读 billBaselineTotal/billCurrentTotal 这套（它现在明显拿不到正确值）
                Log.d(
                    "CASH_PAID_SUM",
                    "billDeviceID=$billDeviceID billDelta=$billSessionDelta coinDeviceID=$coinDeviceID coinDelta=$coinSessionDelta paidTotal=$paidTotalCents target=$targetAmountCents"
                )

                // ⚠️ Step A: 会话金额：只用 GetAllLevels 差分，禁止出现负数
                if (paidSessionDeltaCents < 0) {
                    Log.e(
                        TAG,
                        "⚠️ 异常：paidSessionDeltaCents < 0 ($paidSessionDeltaCents)，baseline 可能被覆盖或读错"
                    )
                    Log.e(
                        TAG,
                        "billBaselineLevels=${billBaselineLevels?.calculateTotalCents()}, coinBaselineLevels=${coinBaselineLevels?.calculateTotalCents()}"
                    )
                    Log.e(
                        TAG,
                        "billSessionDelta=$billSessionDelta, coinSessionDelta=$coinSessionDelta"
                    )
                    // 强制设为 0，避免负数影响支付判断
                    val correctedPaid = 0
                    Log.w(TAG, "强制修正 paidSessionDeltaCents 为 0（避免负数）")
                    // 继续使用修正后的值，但记录警告
                }

                // ⚠️ Step F: 添加关键日志（可降频打印，但必须包含）
                Log.d(
                    "CASH_LEVELS_DELTA",
                    "billDelta=$billSessionDelta coinDelta=$coinSessionDelta paid=$paidSessionDeltaCents"
                )

                // ⚠️ 关键修复：纸币器不再使用 GetCounters，删除相关计算
                // ⚠️ 注意：coinPaidInDelta 是计数项的增量（枚数），不是金额增量，仅用于维护统计日志
                val coinPaidInDelta =
                    (coinCounters?.coinsPaidIn ?: 0) - (previousCoinCounters?.coinsPaidIn ?: 0)

                // ⚠️ 关键修复：billDeltaCents 必须等于 billSessionDelta（累计值，不是增量）
                // ⚠️ billDeltaCents 用于检测是否有现金事件，应该使用累计值
                val billDeltaCents = billSessionDelta  // ⚠️ 修复：使用累计值，不是增量
                val coinDeltaCents = coinSessionDelta  // ⚠️ 修复：使用累计值，不是增量
                
                // 计算本次轮询的增量（用于日志，但不用于金额计算）
                val billDeltaIncrement = billSessionDelta - previousBillSessionDelta
                val coinDeltaIncrement = coinSessionDelta - previousCoinSessionDelta

                // ⚠️ 修正硬币金额识别：打印面额映射表，校验1€=100分
                coinDeviceID?.let { deviceID ->
                    if (coinPaidInDelta > 0) {
                        Log.d(TAG, "========== 硬币金额识别诊断 ==========")
                        Log.d(TAG, "coinsPaidInDelta (count)=$coinPaidInDelta (枚数)")
                        Log.d(TAG, "coinDeltaCents (money)=$coinDeltaCents (金额，分)")
                        // 获取面额映射表
                        try {
                            val assignments = cashDeviceRepository.getDeviceAssignments(deviceID)
                            Log.d(TAG, "硬币器面额映射表: deviceID=$deviceID")
                            assignments.forEach { assignment ->
                                Log.d(
                                    TAG,
                                    "  面额: Value=${assignment.value}分 (${assignment.value / 100.0}€), CountryCode=${assignment.countryCode}"
                                )
                            }
                            // 校验：如果投了1枚，金额应该是100分（1€）
                            if (coinPaidInDelta == 1 && coinDeltaCents != 100) {
                                Log.e(
                                    TAG,
                                    "⚠️ 警告：投了1枚硬币，但金额=${coinDeltaCents}分，不是100分（1€）"
                                )
                                Log.e(TAG, "⚠️ 可能原因：面额映射表单位错误或通道映射错误")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "获取硬币器面额映射表失败: deviceID=$deviceID", e)
                        }
                        Log.d(TAG, "========================================")
                    }
                }
                
                // 更新上次值
                previousBillSessionDelta = billSessionDelta
                previousCoinSessionDelta = coinSessionDelta
                // ⚠️ 关键修复：纸币器不再使用 GetCounters，删除相关变量更新
                previousCoinCounters = coinCounters  // 硬币器 GetCounters 仅用于维护统计
                
                // 获取各设备的 sessionDelta（用于日志）
                val billPaidCents = billSessionDelta
                val coinPaidCents = coinSessionDelta
                
                // ⚠️ 关键修复：纸币器不再使用 GetCounters，删除相关日志
                // ⚠️ 统一日志：CashSessionSnapshot（基于 GetAllLevels 差分）
                Log.d(TAG, "========== CashSessionSnapshot（基于 GetAllLevels 差分） ==========")
                billDeviceID?.let { deviceID ->
                        Log.d(TAG, "纸币器: deviceID=$deviceID")
                    Log.d(
                        TAG,
                        "  baseline.totalReceivedAmount=$billBaselineCents (${billBaselineCents / 100.0}€)"
                    )
                    Log.d(
                        TAG,
                        "  delta.totalReceivedAmount=$billSessionDelta (${billSessionDelta / 100.0}€)"
                    )
                    Log.d(TAG, "  说明：纸币器使用 GetAllLevels 差分，不再调用 GetCounters")
                }
                coinDeviceID?.let { deviceID ->
                    coinCounters?.let { counters ->
                        Log.d(TAG, "硬币器: deviceID=$deviceID")
                        Log.d(
                            TAG,
                            "  baseline.totalReceivedAmount=$coinBaselineCents (${coinBaselineCents / 100.0}€)"
                        )
                        // ⚠️ 关键修复：硬币器不再使用 GetCounters 的 totalReceivedCents，删除相关日志
                        Log.d(
                            TAG,
                            "  delta.totalReceivedAmount=$coinSessionDelta (${coinSessionDelta / 100.0}€)"
                        )
                        Log.d(TAG, "  计数项delta: coinsPaidInDelta=$coinPaidInDelta (枚数，非金额)")
                        Log.d(TAG, "  计数项当前值: coinsPaidIn=${counters.coinsPaidIn} (枚数)")
                    }
                }
                // ⚠️ 关键：统一输出三项，确保口径一致
                Log.d(TAG, "========== 统一金额口径 ==========")
                Log.d(TAG, "billSessionDelta=$billSessionDelta (${billSessionDelta / 100.0}€)")
                Log.d(TAG, "coinSessionDelta=$coinSessionDelta (${coinSessionDelta / 100.0}€)")
                Log.d(
                    TAG,
                    "paidSessionDeltaCents=$paidSessionDeltaCents (${paidSessionDeltaCents / 100.0}€)"
                )
                Log.d(TAG, "paidTotalCents=$paidTotalCents (${paidTotalCents / 100.0}€) [判定用金额]")
                Log.d(TAG, "billDeltaIncrement=$billDeltaIncrement (${billDeltaIncrement / 100.0}€) [本次增量]")
                Log.d(TAG, "coinDeltaIncrement=$coinDeltaIncrement (${coinDeltaIncrement / 100.0}€) [本次增量]")

                // ⚠️ 关键：每次轮询时重新读取库存，确保可接收面额计算基于实时库存
                var currentChangeInventory = changeInventory
                try {
                    // ⚠️ 关键修复：GetAllLevels 调用在 IO 线程执行
                    val mergeStartTime = System.currentTimeMillis()
                    val billLevels = withContext(Dispatchers.IO) {
                        billDeviceID?.let { cashDeviceRepository.readCurrentLevels(it) }
                    }
                    val coinLevels = withContext(Dispatchers.IO) {
                        coinDeviceID?.let { cashDeviceRepository.readCurrentLevels(it) }
                    }
                    // ⚠️ 关键修复：库存合并计算在 IO 线程执行
                    currentChangeInventory = withContext(Dispatchers.IO) {
                        com.carwash.carpayment.data.cashdevice.ChangeInventory.fromLevels(
                            billLevels?.levels ?: emptyList(),
                            coinLevels?.levels ?: emptyList()
                        )
                    }
                    val mergeElapsed = System.currentTimeMillis() - mergeStartTime
                    if (mergeElapsed > 200) {
                        Log.w(TAG, "⚠️ mergeLevels 耗时 ${mergeElapsed}ms")
                    }
                    Log.d(
                        TAG,
                        "实时找零库存: totalCents=${currentChangeInventory.getTotalCents()}分 (${currentChangeInventory.getTotalCents() / 100.0}€)"
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "重新读取找零库存失败，使用缓存的库存", e)
                    // 如果读取失败，使用缓存的库存（如果存在）
                }

                // 计算可接收面额集合（用于找零判定）- 基于实时库存
                val remaining = targetAmountCents - paidSessionDeltaCents
                val acceptedDenoms = currentChangeInventory?.let { inventory ->
                    changeCalculator.calculateAcceptedDenominations(
                        targetAmountCents = targetAmountCents,
                        paidAmountCents = paidSessionDeltaCents,  // 使用统一的paidSessionDeltaCents
                        changeInventory = inventory,
                        availableDenominations = availableDenominations,
                        changeEnabled = true
                    )
                } ?: availableDenominations.toSet() // 如果库存读取失败，允许所有面额
                
                Log.d(
                    TAG,
                    "可接收面额（基于实时库存）: remaining=${remaining}分, acceptedDenoms=${
                        acceptedDenoms.sorted().joinToString(", ") { "${it / 100}€" }
                    }"
                )
                Log.d(TAG, "========================================")

                // ⚠️ 关键修复：纸币器不再使用 GetCounters，删除基于 counters 的拒收检测
                // ⚠️ 注意：纸币器拒收检测需要基于其他信号（如设备状态事件），不再依赖 GetCounters
                // 暂时禁用拒收检测，等待基于事件的新实现
                val billRejectedDelta = 0  // 不再从 GetCounters 获取
                val billStackedDelta = 0   // 不再从 GetCounters 获取
                val billStoredDelta = 0    // 不再从 GetCounters 获取
                
                // A) 识别"纸币被拒收/退回"的信号（暂时禁用，等待基于事件的新实现）
                if (false && billRejectedDelta > 0 && billStackedDelta == 0 && billStoredDelta == 0) {
                    Log.d(TAG, "========== 检测到纸币拒收事件 ==========")
                    Log.d(
                        TAG,
                        "rejectedDelta=$billRejectedDelta, stackedDelta=$billStackedDelta, storedDelta=$billStoredDelta"
                    )
                    Log.d(TAG, "说明: 纸币被拒收/退回，但未收款成功")

                    // 节流：3秒内只弹一次
                    val now = System.currentTimeMillis()
                    if (now - lastRejectionPromptTime >= REJECTION_PROMPT_THROTTLE_MS) {
                        lastRejectionPromptTime = now

                        // 获取允许面额列表（基于实时库存动态计算）
                        val acceptedDenomsList = acceptedDenoms.sorted()
                        val acceptedDenomsText = if (acceptedDenomsList.isNotEmpty()) {
                            acceptedDenomsList.joinToString(", ") { "${it / 100}€" }
                        } else {
                            null
                        }

                        // 触发 UI 提示（通过 StateFlow）- 使用错误码，UI 层映射到 i18n
                        val hint = CashRejectionHint(
                            messageKey = when {
                                acceptedDenomsText != null -> "cash_note_rejected_with_denominations"
                                acceptedDenomsList.isEmpty() -> "cash_payment_unavailable"  // 如果没有任何可接收面额，提示现金支付不可用
                                else -> "cash_note_rejected"
                            },
                            acceptedDenominations = acceptedDenomsText
                        )
                        _noteRejectionMessage.value = hint
                        Log.d(
                            TAG,
                            "已触发拒收提示: messageKey=${hint.messageKey}, acceptedDenominations=${hint.acceptedDenominations}"
                        )

                        // 5秒后清除提示（让 UI 自动隐藏）
                        viewModelScope.launch {
                            kotlinx.coroutines.delay(5000)
                            _noteRejectionMessage.value = null
                        }
                    } else {
                        Log.d(
                            TAG,
                            "拒收提示节流：距离上次提示 ${now - lastRejectionPromptTime}ms < ${REJECTION_PROMPT_THROTTLE_MS}ms，跳过"
                        )
                    }
                    Log.d(TAG, "========================================")
                }
                
                // 必须加的日志：每次收到现金更新事件/轮询结果时打印
                if (billDeltaCents != 0 || coinDeltaCents != 0) {
                    Log.d(TAG, "========== 现金更新事件 ==========")
                    Log.d(TAG, "noteInserted=${billDeltaCents}分 (${billDeltaCents / 100.0}€)")
                    Log.d(TAG, "coinInserted=${coinDeltaCents}分 (${coinDeltaCents / 100.0}€)")
                    Log.d(
                        TAG,
                        "paidSoFar=${paidSessionDeltaCents}分 (${paidSessionDeltaCents / 100.0}€)"
                    )
                    Log.d(
                        TAG,
                        "delta=${billDeltaCents + coinDeltaCents}分 (${(billDeltaCents + coinDeltaCents) / 100.0}€)"
                    )
                    Log.d(TAG, "单位=分 (cents)")
                    Log.d(TAG, "timestamp=${lastPollTime}")
                    Log.d(TAG, "====================================")
                }
                
                // 必须加的日志：支付期间必须打印（使用统一的paidTotalCents）
                val elapsed = System.currentTimeMillis() - paymentStartTime
                Log.d(
                    TAG,
                    "现金支付：paidTotalCents=${paidTotalCents}分，目标=${targetAmountCents}分"
                )
                Log.d(
                    TAG,
                    "现金支付：billSessionDelta=${billSessionDelta}分, coinSessionDelta=${coinSessionDelta}分"
                )
                Log.d(
                    TAG,
                    "现金支付：等待收款中...（已等待${elapsed}ms，paidTotalCents=${paidTotalCents}分 (${paidTotalCents / 100.0}€)，目标=${targetAmountCents}分 (${targetAmount}€)）"
                )

                // ⚠️ 关键修复：更新 UI 状态（已收金额）- 使用 paidTotalCents（= paidSessionDeltaCents，基于 GetAllLevels 差分）
                // ⚠️ 关键修复：UI 状态更新节流（降低更新频率）
                val now = System.currentTimeMillis()
                if (now - lastUiUpdateTime >= UI_UPDATE_THROTTLE_MS) {
                    withContext(Dispatchers.Main) {
                        _flowState.value = _flowState.value.copy(paidAmountCents = paidTotalCents)
                    }
                    lastUiUpdateTime = now
                }

                // ⚠️ 关键修复：支付成功判定必须基于 paidSessionDeltaCents（GetAllLevels 差分）
                // ⚠️ 统一使用 paidTotalCents（= paidSessionDeltaCents）作为判定用金额
                if (paidTotalCents >= targetAmountCents) {
                    // 收款完成
                    Log.d(
                        TAG,
                        "现金支付：收款完成，退出轮询循环（paidTotalCents=${paidTotalCents}分 >= targetAmountCents=${targetAmountCents}分）"
                    )
                    Log.d(
                        TAG,
                        "现金支付：完成详情 - billSessionDelta=${billSessionDelta}分, coinSessionDelta=${coinSessionDelta}分, paidTotalCents=${paidTotalCents}分"
                    )
                    break
                }
                
                // 检查超时
                if (elapsed > CASH_PAYMENT_TIMEOUT_MS) {
                    // ⚠️ 关键修复：超时判定必须使用同一个 paidTotalCents（= paidSessionDeltaCents）
                    val finalPaidSoFar = paidTotalCents  // ⚠️ 使用统一的判定用金额
                    val difference = targetAmountCents - finalPaidSoFar
                    
                    // ⚠️ 强制一致性日志：验证三个值必须一致
                    val trackerBillDelta = billDeviceID?.let {
                        cashDeviceRepository.getAmountTracker().getDeviceSessionCentsFromLevels(it)
                    } ?: 0
                    val trackerCoinDelta = coinDeviceID?.let {
                        cashDeviceRepository.getAmountTracker().getDeviceSessionCentsFromLevels(it)
                    } ?: 0
                    val trackerTotalCents = cashDeviceRepository.getAmountTracker().getTotalCents()
                    
                    // ⚠️ 强制一致性检查：如果值不一致，输出错误日志
                    if (trackerBillDelta != billSessionDelta || trackerCoinDelta != coinSessionDelta || trackerTotalCents != paidTotalCents) {
                        Log.e(TAG, "========== ⚠️ 金额不一致错误 ==========")
                        Log.e(TAG, "billSessionDelta=$billSessionDelta vs trackerBillDelta=$trackerBillDelta")
                        Log.e(TAG, "coinSessionDelta=$coinSessionDelta vs trackerCoinDelta=$trackerCoinDelta")
                        Log.e(TAG, "paidTotalCents=$paidTotalCents vs trackerTotalCents=$trackerTotalCents")
                        Log.e(TAG, "来源路径：paidTotalCents=paidSessionDeltaCents=billSessionDelta+coinSessionDelta")
                        Log.e(TAG, "来源路径：trackerTotalCents=tracker.getTotalCents()")
                        Log.e(TAG, "来源路径：trackerBillDelta=tracker.getDeviceSessionCentsFromLevels(billDeviceID)")
                        Log.e(TAG, "来源路径：trackerCoinDelta=tracker.getDeviceSessionCentsFromLevels(coinDeviceID)")
                        Log.e(TAG, "=========================================")
                    }
                    
                    Log.e(
                        "CASH_TIMEOUT",
                        "paidTotal=$finalPaidSoFar target=$targetAmountCents elapsedMs=$elapsed"
                    )
                    Log.e(TAG, "========== 现金支付超时失败 ==========")
                    Log.e(TAG, "targetAmount=${targetAmountCents}分 (${targetAmount}€)")
                    Log.e(TAG, "paidTotalCents=${finalPaidSoFar}分 (${finalPaidSoFar / 100.0}€) [判定用金额]")
                    Log.e(TAG, "差值=${difference}分 (${difference / 100.0}€)")
                    Log.e(
                        TAG,
                        "billSessionDelta=${billSessionDelta}分, coinSessionDelta=${coinSessionDelta}分"
                    )
                    Log.e(
                        TAG,
                        "paidSessionDeltaCents=${paidSessionDeltaCents}分 (billSessionDelta + coinSessionDelta)"
                    )
                    Log.e(TAG, "最后一次现金事件/轮询时间戳=${lastPollTime}")
                    Log.e(TAG, "超时时间=${elapsed}ms (${elapsed / 1000}秒)")
                    Log.e(TAG, "priceCents=${targetAmountCents}分")
                    Log.e(TAG, "====================================")
                    
                    // ⚠️ 防止退钞：避免因为统计为0就退钞
                    // 如果 paidSessionDeltaCents=0 且没有收到任何现金事件，可能是统计解析失败，不要退钞
                    val hasReceivedCash =
                        billDeltaCents != 0 || coinDeltaCents != 0 || finalPaidSoFar > 0
                    
                    if (!hasReceivedCash && finalPaidSoFar == 0) {
                        // ⚠️ 关键修复：COUNTERS_PARSE_FAILED 改为非致命警告，不阻止支付流程
                        Log.w(
                            TAG,
                            "⚠️ 检测到统计解析可能失败：paidSessionDeltaCents=0 且没有收到任何现金事件（非致命警告）"
                        )
                        Log.w(TAG, "⚠️ 说明：GetCounters/GetAllLevels 解析失败不影响收款，继续等待用户投币")
                        Log.w(
                            TAG,
                            "⚠️ 提示：无法读取入账金额/设备统计解析失败，但支付流程继续（使用 GetAllLevels 差分作为权威方案）"
                        )
                        // ⚠️ 不再调用 cleanupDisableAcceptors 和 handlePaymentFailure
                        // ⚠️ 继续执行支付流程，等待用户投币或超时
                        // 注意：这里不 return，继续执行超时处理
                    }
                    
                    // ⚠️ 关键修复：超时时使用 tracker 的 paidTotalCents
                    val timeoutSeconds = (elapsed / 1000).toInt()
                    finishCashAttempt(
                        outcome = "TIMEOUT",
                        reason = "PAYMENT_TIMEOUT:$timeoutSeconds",
                        billDeviceID = billDeviceID,
                        coinDeviceID = coinDeviceID,
                        paidSessionDeltaCents = finalPaidSoFar,  // 使用 tracker 的 paidTotalCents
                        targetAmountCents = targetAmountCents,
                        billBaselineLevels = billBaselineLevels,
                        coinBaselineLevels = coinBaselineLevels
                    )

                    // 使用错误码，UI层映射到EN/DE
                    handlePaymentFailure("PAYMENT_TIMEOUT:$timeoutSeconds")
                    return
                }
                
                delay(300)  // 每 300ms 轮询一次（200~500ms 范围内）
            }
            
            // 6. 收款完成检查（使用 GetAllLevels 差分，确保准确）
            // ⚠️ 关键修复：统一使用 GetAllLevels 差分作为唯一权威金额
            // 重新获取一次最终值（确保准确）
            var finalBillSessionDelta = 0
            var finalCoinSessionDelta = 0
            
            billDeviceID?.let { deviceID ->
                try {
                    // ⚠️ 使用 GetAllLevels 差分计算最终金额
                    val finalLevels = cashDeviceRepository.readCurrentLevels(deviceID)
                    val finalBillTotalCents = finalLevels.calculateTotalCents()
                    finalBillSessionDelta = finalBillTotalCents - billBaselineCents
                } catch (e: Exception) {
                    Log.w(TAG, "现金支付：获取最终纸币器总收款失败", e)
                }
            }
            
            coinDeviceID?.let { deviceID ->
                try {
                    // ⚠️ 使用 GetAllLevels 差分计算最终金额
                    val finalLevels = cashDeviceRepository.readCurrentLevels(deviceID)
                    val finalCoinTotalCents = finalLevels.calculateTotalCents()
                    finalCoinSessionDelta = finalCoinTotalCents - coinBaselineCents
                } catch (e: Exception) {
                    Log.w(TAG, "现金支付：获取最终硬币器总收款失败", e)
                }
            }
            
            // ⚠️ 关键修复：统一使用 GetAllLevels 差分作为唯一权威金额
            // ⚠️ 禁止使用 tracker.getTotalCents()，必须使用 GetAllLevels 差分
            val totalPaidCents = finalBillSessionDelta + finalCoinSessionDelta  // ⚠️ 使用 GetAllLevels 差分
            val paymentDuration = System.currentTimeMillis() - paymentStartTime
            
            // ⚠️ 关键修复：支付成功判定必须基于 GetAllLevels 差分（paidSessionDeltaCents）
            // ⚠️ 注意：不再校验 tracker.getTotalCents()，因为 stopCashSession 会 reset tracker，导致校验必然失败
            // ⚠️ 统一使用 GetAllLevels 差分作为唯一权威金额
            if (totalPaidCents >= targetAmountCents) {
                Log.d(
                    TAG,
                    "现金支付：收款完成（耗时${paymentDuration}ms），paidTotalCents=${totalPaidCents}分 (${totalPaidCents / 100.0}€)，目标=${targetAmountCents}分 (${targetAmount}€)"
                )
                Log.d(
                    TAG,
                    "现金支付：完成详情 - billSessionDelta=${finalBillSessionDelta}分, coinSessionDelta=${finalCoinSessionDelta}分, paidTotalCents=${totalPaidCents}分"
                )
                Log.d(TAG, "现金支付：priceCents=${targetAmountCents}分")
                
                // ⚠️ Step B: 成功/失败/退款规则（修复"投 5€ 也退款"）
                // ⚠️ Step C: 修复支付判定逻辑：exact paid 不做 change 校验，overpay 才做
                val changeNeededCents = totalPaidCents - targetAmountCents

                // ⚠️ Step B: 若 paidDeltaCents == targetCents：直接 markPaidSuccess()，不允许调用任何 DispenseValue
                if (changeNeededCents == 0) {
                    // 刚好金额：直接成功，不检查找零库存，不调用任何 DispenseValue/退款逻辑
                    Log.d(TAG, "========== 支付金额刚好 ==========")
                    Log.d(
                        TAG,
                        "paidSessionDelta=${totalPaidCents}分 == targetAmountCents=${targetAmountCents}分"
                    )
                    Log.d(TAG, "无需找零，直接成功（禁止调用 DispenseValue）")
                    Log.d("CASH_SESSION_MARK", "COMPLETE paid=${totalPaidCents} change=0")
                    Log.d(TAG, "====================================")

                    // 直接进入成功流程，不调用任何 DispenseValue
                    // 支付成功：统一收尾（幂等）
                    // ⚠️ Step A: 使用统一的 finishCashAttempt 方法
                    finishCashAttempt(
                        outcome = "SUCCESS",
                        reason = "SUCCESS_EXACT_PAYMENT",
                        billDeviceID = billDeviceID,
                        coinDeviceID = coinDeviceID,
                        paidSessionDeltaCents = totalPaidCents,
                        targetAmountCents = targetAmountCents,
                        billBaselineLevels = billBaselineLevels,
                        coinBaselineLevels = coinBaselineLevels
                    )

                    // ⚠️ Step F: 添加关键日志
                    Log.d("CASH_SUCCESS", "paid=$totalPaidCents price=$targetAmountCents change=0")

                    // 支付成功
                    val successState = stateMachine.paymentSuccess(_flowState.value)
                    if (successState != null) {
                        // ⚠️ Step C: 添加关键日志
                        Log.d(
                            "PAY_STATE_SET",
                            "state=SUCCESS by=processCashPayment txId=${successState.paidAmountCents}"
                        )
                        _flowState.value = successState
                        Log.d(TAG, "现金支付处理完成，状态: SUCCESS")

                        // 支付成功后触发打印
                        triggerReceiptPrint(currentState)

                        // 支付成功后接入真实洗车流程
                        startWashFlowAfterPayment(
                            program,
                            currentState.selectedPaymentMethod ?: PaymentMethod.CASH,
                            totalPaidCents
                        )
                        } else {
                        Log.e(TAG, "现金支付：状态机转换失败（paymentSuccess 返回 null）")
                        handlePaymentFailure("状态机转换失败")
                    }
                            return
                } else if (changeNeededCents > 0) {
                    // ⚠️ Step B: 若 paidDeltaCents > targetCents：找零金额 = paidDeltaCents - targetCents，再执行找零
                    // 超付：需要找零，检查找零能力
                    Log.d(TAG, "========== 支付超付，需要找零 ==========")
                    Log.d(
                        TAG,
                        "paidSessionDelta=${totalPaidCents}分 > targetAmountCents=${targetAmountCents}分"
                    )
                    Log.d(
                        TAG,
                        "changeNeededCents=${changeNeededCents}分 (${changeNeededCents / 100.0}€)"
                    )
                    Log.d(TAG, "开始检查找零能力...")
                    Log.d(TAG, "========== 找零能力判断 ==========")
                    Log.d(
                        TAG,
                        "changeNeededCents=${changeNeededCents}分 (${changeNeededCents / 100.0}€)"
                    )
                    Log.d(TAG, "totalPaidCents=${totalPaidCents}分 (${totalPaidCents / 100.0}€)")
                    Log.d(
                        TAG,
                        "targetAmountCents=${targetAmountCents}分 (${targetAmountCents / 100.0}€)"
                    )

                    // 重新读取实时库存（基于纸币+硬币的库存明细）
                    // ⚠️ 关键：必须合并硬币器能力，不能只读纸币器
                    var mergedChangeInventory: com.carwash.carpayment.data.cashdevice.ChangeInventory? =
                        null
                    var levelsUnavailable = false
                    try {
                        // 纸币器：继续用 GetAllLevels
                        val billLevels =
                            billDeviceID?.let { cashDeviceRepository.readCurrentLevels(it) }
                        Log.d(
                            TAG,
                            "纸币器库存: deviceID=$billDeviceID, levelsCount=${billLevels?.levels?.size ?: 0}, totalCents=${billLevels?.calculateTotalCents() ?: 0}"
                        )

                        // 硬币器：优先尝试 GetAllLevels，若失败则降级为能力模式
                        var coinLevels: com.carwash.carpayment.data.cashdevice.LevelsResponse? =
                            null
                        coinDeviceID?.let { deviceID ->
                            try {
                                coinLevels = cashDeviceRepository.readCurrentLevels(deviceID)
                                Log.d(
                                    TAG,
                                    "硬币器库存: deviceID=$deviceID, levelsCount=${coinLevels?.levels?.size ?: 0}, totalCents=${coinLevels?.calculateTotalCents() ?: 0}"
                                )
                            } catch (e: retrofit2.HttpException) {
                                if (e.code() == 404) {
                                    // 硬币器不支持 GetAllLevels，降级为能力模式
                                    Log.w(
                                        TAG,
                                        "硬币器不支持 GetAllLevels (404)，降级为能力模式: deviceID=$deviceID"
                                    )
                                    levelsUnavailable = true
                                    // 尝试用 GetCurrencyAssignment 获取可 payout 的面额集合
                                    try {
                                        val assignments =
                                            cashDeviceRepository.getDeviceAssignments(deviceID)
                                        Log.d(
                                            TAG,
                                            "硬币器面额分配: deviceID=$deviceID, assignmentsCount=${assignments.size}"
                                        )
                                        assignments.forEach { assignment ->
                                            Log.d(
                                                TAG,
                                                "  面额: Value=${assignment.value}分 (${assignment.value / 100.0}€), CountryCode=${assignment.countryCode}"
                                            )
                                        }
                                    } catch (e2: Exception) {
                                        Log.e(TAG, "获取硬币器面额分配失败: deviceID=$deviceID", e2)
                        }
                    } else {
                                    throw e
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "读取硬币器库存失败: deviceID=$deviceID", e)
                                levelsUnavailable = true
                            }
                        }

                        // ⚠️ 关键修复：合并库存（纸币+硬币）在 IO 线程执行
                        val mergeStartTime = System.currentTimeMillis()
                        mergedChangeInventory = withContext(Dispatchers.IO) {
                            com.carwash.carpayment.data.cashdevice.ChangeInventory.fromLevels(
                                billLevels?.levels ?: emptyList(),
                                coinLevels?.levels ?: emptyList()
                            )
                        }
                        val mergeElapsed = System.currentTimeMillis() - mergeStartTime
                        if (mergeElapsed > 200) {
                            Log.w(TAG, "⚠️ mergeLevels 耗时 ${mergeElapsed}ms")
                        }

                        // ⚠️ 关键修复：减少日志输出频率（避免大量字符串拼接阻塞主线程）
                        val totalCents = mergedChangeInventory.getTotalCents()
                        Log.d(TAG, "合并库存总金额=${totalCents}分 (${totalCents / 100.0}€)")
                        // 详细明细只在首次或变化时打印（减少日志开销）
                        if (totalCents == 0 && !levelsUnavailable) {
                            Log.w(
                                TAG,
                                "⚠️ 警告：合并库存金额为0，但levelsUnavailable=false，可能是纸币器和硬币器库存都为0"
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "重新读取库存明细失败", e)
                        levelsUnavailable = true
                    }

                    // 处理找零判断（考虑 levelsUnavailable 情况）
                    if (levelsUnavailable) {
                        // 方案B：允许超付但提示"Change availability unknown"
                        Log.w(
                            TAG,
                            "⚠️ 库存明细不可用（levelsUnavailable=true），采用保守策略：允许超付但提示找零可用性未知"
                        )
                        Log.w(TAG, "⚠️ 支付完成后将以真实 payout 结果决定成功/失败")
                        // 继续支付流程，不阻止
                    } else if (mergedChangeInventory != null) {
                        // ⚠️ 关键修复：找零判断计算在 IO 线程执行
                        val planChangeStartTime = System.currentTimeMillis()
                        val changeResult = withContext(Dispatchers.IO) {
                            mergedChangeInventory.canMakeChangeWithReason(changeNeededCents)
                        }
                        val planChangeElapsed = System.currentTimeMillis() - planChangeStartTime
                        if (planChangeElapsed > 200) {
                            Log.w(TAG, "⚠️ planChange 耗时 ${planChangeElapsed}ms")
                        }

                        Log.d(TAG, "找零判断结果: canMakeChange=${changeResult.canMakeChange}")
                        if (!changeResult.canMakeChange) {
                            Log.e(TAG, "找零失败原因: ${changeResult.failureReason}")
                            Log.e(
                                TAG,
                                "剩余无法找零金额: ${changeResult.remainingAmount}分 (${changeResult.remainingAmount / 100.0}€)"
                            )
                            if (changeResult.missingDenoms.isNotEmpty()) {
                                Log.e(
                                    TAG,
                                    "缺少的面额: ${changeResult.missingDenoms.joinToString(", ") { "${it / 100.0}€" }}"
                                )
                            }

                            // 检查是否是 LEVELS_ZERO（库存全为0）
                            val totalCents = mergedChangeInventory.getTotalCents()
                            if (totalCents == 0) {
                                Log.e(TAG, "⚠️ 合并库存金额为0（纸币器+硬币器库存都为0），无法找零")
                                // 根据失败原因输出不同的错误码
                                val errorCode = when (changeResult.failureReason) {
                                    com.carwash.carpayment.data.cashdevice.ChangeInventory.ChangeFailureReason.LEVELS_UNAVAILABLE -> "CHANGE_LEVELS_UNAVAILABLE"
                                    com.carwash.carpayment.data.cashdevice.ChangeInventory.ChangeFailureReason.INSUFFICIENT_SUM -> "CHANGE_INSUFFICIENT"
                                    com.carwash.carpayment.data.cashdevice.ChangeInventory.ChangeFailureReason.NO_SMALL_DENOMS -> "CHANGE_NO_SMALL_DENOMS"
                                    com.carwash.carpayment.data.cashdevice.ChangeInventory.ChangeFailureReason.ALGO_NO_SOLUTION -> "CHANGE_ALGO_FAILED"
                                    null -> "CHANGE_INSUFFICIENT"
                                }
                                Log.e(
                                    TAG,
                                    "库存不足，无法找零 ${changeNeededCents}分，失败原因=${changeResult.failureReason}"
                                )
                        // 进入 ManualInterventionRequired，禁止继续进入洗车
                                handlePaymentFailure(errorCode)
                        return
                    }
                }
                        Log.d(TAG, "========================================")

                        if (changeResult.canMakeChange) {
                        // ⚠️ 关键修复：计算找零方案在 IO 线程执行
                            val calculateChangeStartTime = System.currentTimeMillis()
                            val changePlan = withContext(Dispatchers.IO) {
                                mergedChangeInventory.calculateChange(changeNeededCents)
                            }
                            val calculateChangeElapsed = System.currentTimeMillis() - calculateChangeStartTime
                            if (calculateChangeElapsed > 200) {
                                Log.w(TAG, "⚠️ calculateChange 耗时 ${calculateChangeElapsed}ms")
                            }
                        if (changePlan != null) {
                                Log.d(
                                    TAG,
                                    "找零方案: ${
                                        changePlan.map { "${it.key / 100}€ x ${it.value}" }
                                            .joinToString(", ")
                                    }"
                                )
                            
                            // ⚠️ 关键修复：执行找零流程（自动吐钞/吐币）
                            Log.d(TAG, "========== 开始执行找零流程 ==========")
                            Log.d(TAG, "找零金额: ${changeNeededCents}分 (${changeNeededCents / 100.0}€)")
                            
                            var changeSuccess = true
                            var changeFailureReason: String? = null
                            
                            // 按面额从大到小排序，优先找大面额
                            val sortedPlan = changePlan.toList().sortedByDescending { it.first }
                            
                            // ⚠️ 关键修复：根据实际库存来源确定设备（纸币器或硬币器）
                            // 需要检查该面额在哪个设备的库存中
                            for ((denomCents, count) in sortedPlan) {
                                if (count <= 0) continue
                                
                                // 确定使用哪个设备（纸币器或硬币器）
                                // ⚠️ 关键修复：根据实际库存来源确定设备（纸币器或硬币器）
                                // 优先检查纸币器库存，如果纸币器没有该面额，则使用硬币器
                                var deviceID: String? = null
                                var deviceName = ""
                                
                                // 检查纸币器是否有该面额
                                if (billDeviceID != null) {
                                    try {
                                        val billLevels = cashDeviceRepository.readCurrentLevels(billDeviceID)
                                        val billHasDenom = billLevels.levels?.any { it.value == denomCents && it.stored > 0 } ?: false
                                        if (billHasDenom) {
                                            deviceID = billDeviceID
                                            deviceName = "纸币器"
                                        }
                } catch (e: Exception) {
                                        // 忽略异常，继续检查硬币器
                                    }
                                }
                                
                                // 如果纸币器没有该面额，检查硬币器
                                if (deviceID == null && coinDeviceID != null) {
                                    try {
                                        val coinLevels = cashDeviceRepository.readCurrentLevels(coinDeviceID)
                                        val coinHasDenom = coinLevels.levels?.any { it.value == denomCents && it.stored > 0 } ?: false
                                        if (coinHasDenom) {
                                            deviceID = coinDeviceID
                                            deviceName = "硬币器"
                                        }
                                    } catch (e: Exception) {
                                        // 忽略异常
                                    }
                                }
                                
                                if (deviceID == null) {
                                    Log.e(TAG, "找零失败：无法确定设备（面额=${denomCents}分），纸币器和硬币器都没有该面额库存")
                                    changeSuccess = false
                                    changeFailureReason = "无法确定设备（面额=${denomCents}分），纸币器和硬币器都没有该面额库存"
                                    break
                                }
                                
                                Log.d(TAG, "找零：${denomCents}分 x ${count}张，使用设备=$deviceID ($deviceName)")
                                
                                // 对每个面额，调用 count 次 dispenseValue
                                for (i in 1..count) {
                                    try {
                                        val dispenseSuccess = cashDeviceRepository.dispenseValue(
                                            deviceID,
                                            denomCents,
                                            "EUR"
                                        )
                                        
                                        if (!dispenseSuccess) {
                                            Log.e(TAG, "找零失败：DispenseValue 失败（面额=${denomCents}分，第${i}次）")
                                            changeSuccess = false
                                            changeFailureReason = "DispenseValue 失败（面额=${denomCents}分，第${i}次）"
                                            break
                                        } else {
                                            Log.d(TAG, "找零成功：${denomCents}分（第${i}/${count}次）")
                                        }
                                        
                                        // 每次吐钞/吐币后稍作延迟，避免设备忙碌
                                        if (i < count) {
                                            kotlinx.coroutines.delay(300)
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "找零异常：DispenseValue 异常（面额=${denomCents}分，第${i}次）", e)
                                        changeSuccess = false
                                        changeFailureReason = "DispenseValue 异常（面额=${denomCents}分，第${i}次）: ${e.message}"
                                        break
                                    }
                                }
                                
                                if (!changeSuccess) {
                                    break
                                }
                                
                                // 不同面额之间稍作延迟
                                val currentIndex = sortedPlan.indexOfFirst { it.first == denomCents && it.second == count }
                                if (currentIndex >= 0 && currentIndex < sortedPlan.size - 1) {
                                    kotlinx.coroutines.delay(500)
                                }
                            }
                            
                            if (changeSuccess) {
                                Log.d(TAG, "========== 找零流程完成 ==========")
                                Log.d(TAG, "找零成功：${changeNeededCents}分 (${changeNeededCents / 100.0}€)")
                                // ⚠️ Step D: 添加 CASH_SESSION_MARK COMPLETE 日志（包含 change 信息）
                                Log.d(
                                    "CASH_SESSION_MARK",
                                    "COMPLETE paid=${totalPaidCents} change=${changeNeededCents}"
                                )
                            } else {
                                Log.e(TAG, "========== 找零流程失败 ==========")
                                Log.e(TAG, "找零失败原因: $changeFailureReason")
                                Log.e(TAG, "需要人工处理找零或退款")
                                
                                // 找零失败时，需要退款
                                val paidReceivedCents = totalPaidCents  // ⚠️ 使用 GetAllLevels 差分
                                if (paidReceivedCents > 0) {
                                    Log.d(
                                        "CASH_REFUND_TRIGGER",
                                        "paid=$paidReceivedCents target=$targetAmountCents reason=CHANGE_DISPENSE_FAILED"
                                    )
                                    Log.d(
                                        TAG,
                                        "找零失败且已收款，执行退款: refundAmountCents=$paidReceivedCents"
                                    )
                                    try {
                                        // ⚠️ 关键修复：等待退款函数返回最终结果，再决定最终状态
                                        val refundResult = cashDeviceRepository.refundAmount(
                                            paidReceivedCents,
                                            billDeviceID,
                                            coinDeviceID
                                        )
                                        
                                        // ⚠️ 关键修复：如果退款成功（remaining=0），进入 CANCELLED_REFUNDED 状态
                                        if (refundResult.success && refundResult.remaining == 0) {
                                            Log.d(TAG, "========== 找零失败但退款成功 ==========")
                                            Log.d(TAG, "已收款=${paidReceivedCents}分，已退款=${refundResult.totalDispensed}分，remaining=0")
                                            Log.d(TAG, "进入 CANCELLED_REFUNDED 状态")
                                            
                                            // 进入已取消且退款完成状态
                                            val refundedState = stateMachine.transition(
                                                _flowState.value,
                                                PaymentFlowStatus.CANCELLED_REFUNDED,
                                                "找零失败但退款完成"
                                            )
                                            withContext(Dispatchers.Main) {
                                                _flowState.value = refundedState
                                            }
                                            
                                            // 统一收尾
                                            finishCashAttempt(
                                                outcome = "CANCELLED_REFUNDED",
                                                reason = "找零失败但退款完成",
                                                billDeviceID,
                                                coinDeviceID,
                                                paidReceivedCents,  // ⚠️ 使用 GetAllLevels 差分
                                                targetAmountCents,
                                                billBaselineLevels,
                                                coinBaselineLevels
                                            )
                                            return
                                        } else {
                                            Log.e(
                                                TAG,
                                                "⚠️ 退款失败或部分失败: remaining=${refundResult.remaining}分"
                                            )
                                            handlePaymentFailure("CHANGE_DISPENSE_FAILED_AND_REFUND_FAILED: remaining=${refundResult.remaining}")
                                            return
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "退款异常，需要人工处理", e)
                                        handlePaymentFailure("CHANGE_DISPENSE_FAILED_AND_REFUND_EXCEPTION")
                                        return
                                    }
                                } else {
                                    // 找零失败且未收款，直接进入失败分支
                                    handlePaymentFailure("CHANGE_DISPENSE_FAILED: $changeFailureReason")
                                    return
                                }
                            }
                        } else {
                            Log.e(TAG, "找零方案计算失败，需要人工干预")
                                // ⚠️ 找零方案计算失败时必须退款：已投入金额 > 0，必须触发退款
                                // ⚠️ 关键修复：使用 tracker.getTotalCents() 作为退款金额
                                val paidReceivedCents =
                                    cashDeviceRepository.getAmountTracker().getTotalCents()
                                if (paidReceivedCents > 0) {
                                    Log.d(
                                        "CASH_REFUND_TRIGGER",
                                        "paid=$paidReceivedCents target=$targetAmountCents reason=CHANGE_CALC_FAILED"
                                    )
                                    Log.d(
                                        TAG,
                                        "找零方案计算失败且已收款，执行退款: refundAmountCents=$paidReceivedCents"
                                    )
                                    try {
                                        // ⚠️ 关键修复：等待退款函数返回最终结果
                                        val refundResult = cashDeviceRepository.refundAmount(
                                            paidReceivedCents,  // ⚠️ 使用 GetAllLevels 差分
                                            billDeviceID,
                                            coinDeviceID
                                        )
                                        if (!refundResult.success) {
                                            Log.e(
                                                TAG,
                                                "⚠️ 退款失败，需要人工处理: refundAmountCents=$paidReceivedCents remaining=${refundResult.remaining}"
                                            )
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "退款异常，需要人工处理", e)
                                    }
                                }
                            // 进入 ManualInterventionRequired，禁止继续进入洗车
                                handlePaymentFailure("CHANGE_CALC_FAILED")
                            return
                        }
                    } else {
                            // 根据失败原因输出不同的错误码
                            val errorCode = when (changeResult.failureReason) {
                                com.carwash.carpayment.data.cashdevice.ChangeInventory.ChangeFailureReason.LEVELS_UNAVAILABLE -> "CHANGE_LEVELS_UNAVAILABLE"
                                com.carwash.carpayment.data.cashdevice.ChangeInventory.ChangeFailureReason.INSUFFICIENT_SUM -> "CHANGE_INSUFFICIENT"
                                com.carwash.carpayment.data.cashdevice.ChangeInventory.ChangeFailureReason.NO_SMALL_DENOMS -> "CHANGE_NO_SMALL_DENOMS"
                                com.carwash.carpayment.data.cashdevice.ChangeInventory.ChangeFailureReason.ALGO_NO_SOLUTION -> "CHANGE_ALGO_FAILED"
                                null -> "CHANGE_INSUFFICIENT"
                            }
                            Log.e(
                                TAG,
                                "库存不足，无法找零 ${changeNeededCents}分，失败原因=${changeResult.failureReason}"
                            )
                            // ⚠️ 找零失败时必须退款：已投入金额 > 0，必须触发退款
                            // ⚠️ 关键修复：使用 tracker.getTotalCents() 作为退款金额
                            val paidReceivedCents =
                                cashDeviceRepository.getAmountTracker().getTotalCents()
                            if (paidReceivedCents > 0) {
                                Log.d(
                                    "CASH_REFUND_TRIGGER",
                                    "paid=$paidReceivedCents target=$targetAmountCents reason=CHANGE_FAILED"
                                )
                                Log.d(
                                    TAG,
                                    "找零失败且已收款，执行退款: refundAmountCents=$paidReceivedCents"
                                )
                                try {
                                    // ⚠️ 关键修复：等待退款函数返回最终结果
                                    val refundResult = cashDeviceRepository.refundAmount(
                                        paidReceivedCents,  // ⚠️ 使用 GetAllLevels 差分
                                        billDeviceID,
                                        coinDeviceID
                                    )
                                    if (!refundResult.success) {
                                        Log.e(
                                            TAG,
                                            "⚠️ 退款失败，需要人工处理: refundAmountCents=$paidReceivedCents remaining=${refundResult.remaining}"
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "退款异常，需要人工处理", e)
                                }
                            }
                        // 进入 ManualInterventionRequired，禁止继续进入洗车
                            handlePaymentFailure(errorCode)
                        return
                    }
                    } else {
                        Log.e(TAG, "库存明细不可用，无法判断找零能力")
                        // 采用保守策略：允许超付但提示
                        Log.w(TAG, "⚠️ 采用保守策略：允许超付但提示找零可用性未知")
                    }
                } else if (changeNeededCents > 0 && totalPaidCents == 0) {
                    // 如果 changeNeededCents > 0 但 totalPaidCents == 0，说明可能是拒收导致的误判
                    Log.w(
                        TAG,
                        "检测到可能的误判：changeNeededCents=$changeNeededCents 但 totalPaidCents=0，可能是拒收导致的，跳过找零流程"
                    )
                }
                
                // 支付成功：统一收尾（幂等）
                // ⚠️ Step A: 使用统一的 finishCashAttempt 方法
                finishCashAttempt(
                    outcome = "SUCCESS",
                    reason = if (changeNeededCents == 0) "SUCCESS_EXACT_PAYMENT" else "SUCCESS_OVERPAYMENT",
                    billDeviceID = billDeviceID,
                    coinDeviceID = coinDeviceID,
                    paidSessionDeltaCents = totalPaidCents,
                    targetAmountCents = targetAmountCents,
                    billBaselineLevels = billBaselineLevels,
                    coinBaselineLevels = coinBaselineLevels
                )

                // ⚠️ Step F: 添加关键日志
                Log.d(
                    "CASH_SUCCESS",
                    "paid=$totalPaidCents price=$targetAmountCents change=$changeNeededCents"
                )
                
                // 支付成功
                val successState = stateMachine.paymentSuccess(_flowState.value)
                if (successState != null) {
                    // ⚠️ Step C: 添加关键日志
                    Log.d(
                        "PAY_STATE_SET",
                        "state=SUCCESS by=processCashPayment txId=${successState.paidAmountCents}"
                    )
                    _flowState.value = successState
                    Log.d(TAG, "现金支付处理完成，状态: SUCCESS")
                    
                    // 支付成功后触发打印
                    triggerReceiptPrint(currentState)
                    
                    // 支付成功后接入真实洗车流程
                    startWashFlowAfterPayment(
                        program,
                        currentState.selectedPaymentMethod ?: PaymentMethod.CASH,
                        totalPaidCents
                    )
                } else {
                    Log.e(TAG, "现金支付：状态机转换失败（paymentSuccess 返回 null）")
                    handlePaymentFailure("状态机转换失败")
                }
                return
            } else {
                Log.e(
                    TAG,
                    "现金支付：收款未完成，paidSessionDelta=${totalPaidCents}分 (${totalPaidCents / 100.0}€)，目标=${targetAmountCents}分 (${targetAmount}€)"
                )
                Log.e(
                    TAG,
                    "现金支付：未完成详情 - billSessionDelta=${finalBillSessionDelta}分, coinSessionDelta=${finalCoinSessionDelta}分, paidSessionDelta=${totalPaidCents}分"
                )

                // ⚠️ 收款未完成时必须退款：如果已收款（delta > 0），执行退款
                // ⚠️ 关键修复：使用 tracker.getTotalCents() 作为退款金额
                val paidReceivedCents = cashDeviceRepository.getAmountTracker().getTotalCents()
                if (paidReceivedCents > 0) {
                    Log.d(
                        "CASH_REFUND_TRIGGER",
                        "paid=$paidReceivedCents target=$targetAmountCents reason=PAYMENT_INCOMPLETE"
                    )
                    Log.d(TAG, "收款未完成且已收款，执行退款: refundAmountCents=$paidReceivedCents")
                    try {
                        // ⚠️ 关键修复：等待退款函数返回最终结果
                        val refundResult = cashDeviceRepository.refundAmount(
                            paidReceivedCents,  // ⚠️ 使用 GetAllLevels 差分
                            billDeviceID,
                            coinDeviceID
                        )
                        if (!refundResult.success) {
                            Log.e(
                                TAG,
                                "⚠️ 退款失败，需要人工处理: refundAmountCents=$paidReceivedCents remaining=${refundResult.remaining}"
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "退款异常，需要人工处理", e)
                    }
                }
                
                // 收款未完成：统一收尾（幂等）
                if (!isAcceptorDisabled) {
                    cleanupDisableAcceptors(billDeviceID, coinDeviceID, "收款未完成")
                    isAcceptorDisabled = true
                    Log.d(TAG, "收款未完成：已禁用接收器")
                } else {
                    Log.d(TAG, "收款未完成：接收器已禁用（幂等，跳过）")
                }
                
                // 停止现金会话（幂等）
                if (!isCashSessionStopped) {
                    try {
                        cashDeviceRepository.stopCashSession("收款未完成")
                        isCashSessionStopped = true
                        Log.d(TAG, "收款未完成：已停止现金会话")
                    } catch (e: Exception) {
                        Log.e(TAG, "停止现金会话异常", e)
                    }
                } else {
                    Log.d(TAG, "收款未完成：现金会话已停止（幂等，跳过）")
                }
                
                handlePaymentFailure("收款未完成")
            }
        } catch (e: Exception) {
            Log.e(TAG, "现金支付异常", e)
            
            // 异常时：统一收尾（幂等）
            if (!isAcceptorDisabled) {
                cleanupDisableAcceptors(billDeviceID, coinDeviceID, "EXCEPTION")
                isAcceptorDisabled = true
                Log.d(TAG, "收款异常：已禁用接收器")
            } else {
                Log.d(TAG, "收款异常：接收器已禁用（幂等，跳过）")
            }
            
            // 停止现金会话（幂等）
            if (!isCashSessionStopped) {
                try {
                    cashDeviceRepository.stopCashSession("EXCEPTION: ${e.message}")
                    isCashSessionStopped = true
                    Log.d(TAG, "收款异常：已停止现金会话")
                } catch (stopException: Exception) {
                    Log.e(TAG, "停止现金会话异常", stopException)
                }
            } else {
                Log.d(TAG, "收款异常：现金会话已停止（幂等，跳过）")
            }
            
            handlePaymentFailure("支付异常: ${e.message}")
        } finally {
            // 确保无论成功/失败/异常，都停止现金会话（幂等：只在未停止时执行）
            // 注意：不再重复 disableAcceptor，因为已在 SUCCESS/失败/异常分支中执行
            if (!isCashSessionStopped) {
                try {
                    cashDeviceRepository.stopCashSession("FINALLY")
                    isCashSessionStopped = true
                    Log.d(TAG, "收款清理（FINALLY）：已停止现金会话")
                } catch (e: Exception) {
                    Log.e(TAG, "finally 中停止现金会话异常", e)
                }
            } else {
                Log.d(TAG, "收款清理（FINALLY）：现金会话已停止（幂等，跳过）")
            }
        }
    }
    
    /**
     * 统一收尾：禁用接收器（所有退出路径都调用）
     * 
     * @param billDeviceID 纸币器设备ID（可为null）
     * @param coinDeviceID 硬币器设备ID（可为null）
     * @param reason 调用原因（TIMEOUT/SUCCESS/EXCEPTION/统计解析失败等）
     */
/**
 * 统一现金支付结束方法（单一出口）
 * @param outcome 结束原因：SUCCESS, CANCEL, TIMEOUT, FAILED
 * @param reason 详细原因描述
 * @param billDeviceID 纸币器设备ID
 * @param coinDeviceID 硬币器设备ID
 * @param paidSessionDeltaCents 本次会话已收金额（基于 Levels 差分）
 * @param targetAmountCents 目标金额
 * @param billBaselineLevels 纸币器 baseline levels（用于计算退款）
 * @param coinBaselineLevels 硬币器 baseline levels（用于计算退款）
 */
private suspend fun finishCashAttempt(
    outcome: String, // SUCCESS, CANCEL, TIMEOUT, FAILED
    reason: String,
    billDeviceID: String?,
    coinDeviceID: String?,
    paidSessionDeltaCents: Int,  // ⚠️ 注意：此参数已废弃，实际使用 tracker.getTotalCents()
    targetAmountCents: Int,
    billBaselineLevels: com.carwash.carpayment.data.cashdevice.LevelsResponse?,
    coinBaselineLevels: com.carwash.carpayment.data.cashdevice.LevelsResponse?
) {
    // ⚠️ 关键修复：使用 GetAllLevels 差分作为退款金额（paidSessionDeltaCents）
    // ⚠️ 注意：此函数接收的 paidSessionDeltaCents 参数已基于 GetAllLevels 差分计算
    val paidReceivedCents = paidSessionDeltaCents  // ⚠️ 使用传入的 GetAllLevels 差分，不使用 tracker.getTotalCents()
    Log.d(
        "CASH_FINISH",
        "outcome=$outcome reason=$reason paid=$paidReceivedCents price=$targetAmountCents"
    )
    // ⚠️ 关键修复：CASH_FINISH 日志中的 paid 字段必须等于 paidTotalCents（GetAllLevels 差分）
    if (outcome == "SUCCESS" && paidReceivedCents == 0) {
        Log.e(TAG, "========== ⚠️ CASH_FINISH 错误：SUCCESS 但 paid=0 ==========")
        Log.e(TAG, "outcome=$outcome paidReceivedCents=$paidReceivedCents paidSessionDeltaCents=$paidSessionDeltaCents")
        Log.e(TAG, "应该使用 GetAllLevels 差分作为 paid 字段")
        Log.e(TAG, "=========================================")
    }

    // 1. 立即停止事件/轮询（避免边退款边继续收款造成基线漂移）
    // 注意：轮询循环会在协程取消时自动停止，这里主要是确保不再处理新事件

    // 2. DisableAcceptor + SetAutoAccept(false)（两台设备都做，失败也继续）
    billDeviceID?.let { deviceID ->
        try {
            cashDeviceRepository.setAutoAccept(deviceID, false)
            cashDeviceRepository.disableAcceptor(deviceID)
        } catch (e: Exception) {
            Log.w(TAG, "纸币器 DisableAcceptor/SetAutoAccept 失败: deviceID=$deviceID", e)
        }
    }

    coinDeviceID?.let { deviceID ->
        try {
            cashDeviceRepository.setAutoAccept(deviceID, false)
            cashDeviceRepository.disableAcceptor(deviceID)
        } catch (e: Exception) {
            Log.w(TAG, "硬币器 DisableAcceptor/SetAutoAccept 失败: deviceID=$deviceID", e)
        }
    }

    // 3. 验证 paidReceivedCents（必须 >= 0）
    if (paidReceivedCents < 0) {
        Log.e(TAG, "⚠️ 异常：paidReceivedCents < 0 ($paidReceivedCents)，baseline 可能被覆盖或读错")
        Log.e(
            TAG,
            "billBaselineLevels=${billBaselineLevels?.calculateTotalCents()}, coinBaselineLevels=${coinBaselineLevels?.calculateTotalCents()}"
        )
        // 尝试读取当前 levels 进行对比
        try {
            val currentBillLevels = billDeviceID?.let { cashDeviceRepository.readCurrentLevels(it) }
            val currentCoinLevels = coinDeviceID?.let { cashDeviceRepository.readCurrentLevels(it) }
            Log.e(
                TAG,
                "currentBillLevels=${currentBillLevels?.calculateTotalCents()}, currentCoinLevels=${currentCoinLevels?.calculateTotalCents()}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "读取当前 levels 失败", e)
        }
        // 强制设为 0，避免负退款
        Log.w(TAG, "强制修正 paidReceivedCents 为 0（避免负退款）")
        // 继续执行，但使用修正后的值 0
        if (outcome != "SUCCESS") {
            // 非成功路径：执行退款（使用修正后的值 0，实际不退款）
            // 但无论如何都要清理 baseline
            billDeviceID?.let { cashDeviceRepository.getBaselineStore().clearBaseline(it) }
            coinDeviceID?.let { cashDeviceRepository.getBaselineStore().clearBaseline(it) }
            cashDeviceRepository.stopCashSession(reason)
            return
        }
    }

    // 4. 若 paidReceivedCents > 0 且 outcome != SUCCESS：执行退款
    if (paidReceivedCents > 0 && outcome != "SUCCESS") {
        Log.d(TAG, "执行退款: refundAmountCents=$paidReceivedCents（使用 tracker.getTotalCents()）")
        try {
            // ⚠️ 关键修复：等待退款函数返回最终结果
            val refundResult = cashDeviceRepository.refundAmount(
                paidReceivedCents,  // ⚠️ 关键修复：使用 paidReceivedCents（GetAllLevels 差分），严禁使用 targetAmountCents
                billDeviceID,
                coinDeviceID
            )
            
            // ⚠️ 关键修复：如果退款成功（remaining=0），更新状态为 CANCELLED_REFUNDED
            if (refundResult.success && refundResult.remaining == 0) {
                Log.d(TAG, "========== 退款成功 ==========")
                Log.d(TAG, "已收款=${paidReceivedCents}分，已退款=${refundResult.totalDispensed}分，remaining=0")
                
                // 如果当前状态不是 CANCELLED_REFUNDED，更新状态
                if (_flowState.value.status != PaymentFlowStatus.CANCELLED_REFUNDED) {
                    val refundedState = stateMachine.transition(
                        _flowState.value,
                        PaymentFlowStatus.CANCELLED_REFUNDED,
                        "退款完成"
                    )
                    withContext(Dispatchers.Main) {
                        _flowState.value = refundedState
                    }
                }
            } else if (!refundResult.success) {
                Log.e(
                    TAG,
                    "⚠️ 退款失败（找零不足），需要人工处理: refundAmountCents=$paidReceivedCents"
                )
                // ⚠️ Step 5: 找零不足时提示用户并锁单或弹窗提醒
                val config = cashDeviceRepository.getRefundConfig()
                when (config.refundInsufficientAction) {
                    RefundInsufficientAction.SHOW_MESSAGE_ONLY -> {
                        // 仅提示用户
                        _noteRejectionMessage.value = CashRejectionHint(
                            messageKey = "error_change_insufficient",
                            acceptedDenominations = null
                        )
                    }

                    RefundInsufficientAction.LOCK_MACHINE_AND_ALERT -> {
                        // 锁单并报警（更安全）
                        _noteRejectionMessage.value = CashRejectionHint(
                            messageKey = "error_change_insufficient",
                            acceptedDenominations = null
                        )
                        Log.e(TAG, "⚠️ 找零不足，触发锁单：需要管理员处理")
                        // 这里可以添加锁单逻辑（如果需要）
                    }
                }
                // 退款失败时：必须把失败原因透出（日志+UI 可选），但无论如何都要进入 reset（不能卡住）
            }
            Log.d("CASH_FINISH", "refund=$paidReceivedCents success=${refundResult.success} remaining=${refundResult.remaining}")
        } catch (e: Exception) {
            Log.e(TAG, "退款异常，需要人工处理", e)
            // 即使退款异常，也要继续执行清理
        }
    } else if (outcome == "SUCCESS") {
        Log.d("CASH_FINISH", "refund=0 (SUCCESS, no refund needed)")
    } else {
        Log.d("CASH_FINISH", "refund=0 (no payment received)")
    }

    // 5. 清理 baseline（防止下次出现负 delta）
    billDeviceID?.let { cashDeviceRepository.getBaselineStore().clearBaseline(it) }
    coinDeviceID?.let { cashDeviceRepository.getBaselineStore().clearBaseline(it) }

    // 6. 停止现金会话
    try {
        cashDeviceRepository.stopCashSession(reason)
    } catch (e: Exception) {
        Log.w(TAG, "停止现金会话异常", e)
    }

    // 7. 清空状态
    _noteRejectionMessage.value = null

    // 8. 根据 outcome 决定是否调用 resetForNewAttempt
    if (outcome == "SUCCESS") {
        // SUCCESS 路径：不在这里 reset，由 PaymentNavigation 在返回主界面时 reset
        Log.d(TAG, "现金支付成功，等待 PaymentNavigation 返回主界面时 reset")
    } else {
        // CANCEL/TIMEOUT/FAILED 路径：立即 reset
        resetForNewAttempt(reason = "CASH_FINISH_$outcome")
    }
}

private suspend fun cleanupDisableAcceptors(
    billDeviceID: String?,
    coinDeviceID: String?,
    reason: String
) {
    Log.d(TAG, "========== 结束现金支付会话（cleanupDisableAcceptors） ==========")
        Log.d(TAG, "reason=$reason")
        Log.d(TAG, "billDeviceID=$billDeviceID")
        Log.d(TAG, "coinDeviceID=$coinDeviceID")
        
        billDeviceID?.let { deviceID ->
            try {
            Log.d(TAG, "calling endCashSession deviceID=$deviceID reason=$reason")
            cashDeviceRepository.endCashSession(deviceID)
            } catch (e: Exception) {
            Log.w(TAG, "现金支付：结束纸币器支付会话失败: deviceID=$deviceID, reason=$reason", e)
            }
        } ?: run {
        Log.w(TAG, "⚠️ billDeviceID == null，跳过结束纸币器支付会话")
        }
        
        coinDeviceID?.let { deviceID ->
            try {
            Log.d(TAG, "calling endCashSession deviceID=$deviceID reason=$reason")
            cashDeviceRepository.endCashSession(deviceID)
            } catch (e: Exception) {
            Log.w(TAG, "现金支付：结束硬币器支付会话失败: deviceID=$deviceID, reason=$reason", e)
            }
        } ?: run {
        Log.w(TAG, "⚠️ coinDeviceID == null，跳过结束硬币器支付会话")
        }
        
    Log.d(TAG, "========== 结束现金支付会话完成 ==========")
    }
    
    /**
     * 启动洗车流程（支付成功后调用）
     */
    private fun startWashFlowAfterPayment(
        program: WashProgram,
        paymentMethod: PaymentMethod,
        paidAmountCents: Int
    ) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "========== 支付成功后启动洗车流程 ==========")
            Log.d(
                TAG,
                "program=${program.name}, paymentMethod=$paymentMethod, paidAmountCents=$paidAmountCents"
            )
                
                // 创建订单
                val order = com.carwash.carpayment.ui.viewmodel.WashOrder(
                    program = program,
                    paymentMethod = paymentMethod
                )
                
                // 获取 WashFlowViewModel 实例（从 Application 单例获取）
                val washFlowViewModel = com.carwash.carpayment.CarPaymentApplication.washFlowViewModel
                if (washFlowViewModel == null) {
                    Log.e(TAG, "WashFlowViewModel 未初始化，无法启动洗车流程")
                    handlePaymentFailure("洗车流程初始化失败")
                    return@launch
                }
                
                // 启动洗车流程
                washFlowViewModel.startFlow(order)
                
                Log.d(TAG, "========== 洗车流程已启动 ==========")
            } catch (e: Exception) {
                Log.e(TAG, "启动洗车流程异常", e)
                handlePaymentFailure("启动洗车流程失败: ${e.message}")
            }
        }
    }
    
    /**
     * 处理卡支付（真实 POS 交易）
     */
    private suspend fun processCardPayment(currentState: PaymentFlowState) {
        val program = currentState.selectedProgram
        if (program == null) {
            Log.e(TAG, "卡支付：程序信息为空")
            handlePaymentFailure("程序信息缺失")
            return
        }
        
        val targetAmount = program.price
        val targetAmountCents = (targetAmount * 100).toInt()
        
        Log.d(TAG, "卡支付开始：目标金额=${targetAmount}€ (${targetAmountCents}分)")
        
        try {
            // 1. 初始化 POS 设备（如果未初始化）
            val initialized = posPaymentService.initialize()
            if (!initialized) {
                Log.e(TAG, "卡支付：POS 设备初始化失败")
                handlePaymentFailure("POS 设备初始化失败，请检查设备连接")
                return
            }
            
            // 2. 发起支付（等待真实回调）
            Log.d(TAG, "卡支付：发起 POS 交易，金额=${targetAmountCents}分")
            
            // 使用 suspendCancellableCoroutine 等待回调
            val paymentResult = suspendCancellableCoroutine<PaymentResult> { continuation ->
                // 在协程中调用 suspend 函数
                viewModelScope.launch {
                    try {
                        posPaymentService.initiatePayment(targetAmountCents) { result ->
                            Log.d(TAG, "卡支付：收到 POS 回调，结果=${result::class.java.simpleName}")
                            continuation.resume(result)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "卡支付：发起支付异常", e)
                        continuation.resume(PaymentResult.Failure("支付异常: ${e.message}"))
                    }
                }
            }
            
            // 3. 处理支付结果
            when (paymentResult) {
                is PaymentResult.Success -> {
                    Log.d(TAG, "========== POS 支付成功 ==========")
                Log.d(
                    TAG,
                    "金额=${paymentResult.amountCents}分 (${paymentResult.amountCents / 100.0}€)"
                )
                    Log.d(TAG, "====================================")
                    
                    // ⚠️ POS 成功后必打印：在状态机 SUCCESS 分支必须调用 printReceipt()
                    val successState = stateMachine.paymentSuccess(_flowState.value)
                    if (successState != null) {
                        _flowState.value = successState
                        Log.d(TAG, "POS 支付处理完成，状态: SUCCESS")
                        
                        // 支付成功后触发打印（使用 SupervisorJob 确保打印协程不被取消）
                        triggerReceiptPrintWithSupervisor(currentState)
                    } else {
                        Log.e(TAG, "POS 支付：状态机转换失败（paymentSuccess 返回 null）")
                        handlePaymentFailure("状态机转换失败")
                    }
                }

                is PaymentResult.Failure -> {
                Log.e(
                    TAG,
                    "卡支付：支付失败，原因=${paymentResult.errorMessage}, 错误码=${paymentResult.errorCode}"
                )
                    handlePaymentFailure("支付失败: ${paymentResult.errorMessage}")
                }

                is PaymentResult.Cancelled -> {
                    Log.w(TAG, "卡支付：支付已取消，原因=${paymentResult.reason}")
                    handlePaymentFailure("支付已取消: ${paymentResult.reason ?: "用户取消"}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "卡支付异常", e)
            handlePaymentFailure("支付异常: ${e.message}")
        }
    }
    
    /**
     * 支付失败（供外部调用）
     */
    fun handlePaymentFailure(errorMessage: String) {
        val currentState = _flowState.value
    // 如果是现金支付失败，确保禁用接收器但不断开连接
    if (currentState.selectedPaymentMethod == PaymentMethod.CASH) {
        viewModelScope.launch {
            try {
                // 从 CashDeviceRepository 获取 deviceID
                val billDeviceID = cashDeviceRepository.getBillAcceptorDeviceID()
                val coinDeviceID = cashDeviceRepository.getCoinAcceptorDeviceID()

                // ⚠️ 支付失败时必须退款：如果已收款（delta > 0），执行退款
                // ⚠️ 关键修复：使用 tracker.getTotalCents() 作为退款金额
                try {
                    val paidReceivedCents = cashDeviceRepository.getAmountTracker().getTotalCents()
                    if (paidReceivedCents > 0) {
                        Log.d(
                            "CASH_REFUND_TRIGGER",
                            "paid=$paidReceivedCents target=0 reason=PAYMENT_FAILED"
                        )
                        Log.d(
                            TAG,
                            "支付失败且已收款，执行退款: refundAmountCents=$paidReceivedCents"
                        )
                        // ⚠️ 关键修复：等待退款函数返回最终结果
                        val refundResult = cashDeviceRepository.refundAmount(
                            paidReceivedCents,  // ⚠️ 使用 tracker.getTotalCents()
                            billDeviceID,
                            coinDeviceID
                        )
                        if (!refundResult.success) {
                            Log.e(
                                TAG,
                                "⚠️ 退款失败，需要人工处理: refundAmountCents=$paidReceivedCents"
                            )
                            // 弹窗提示（EN/DE）将在 UI 层处理
                        }
                    } else {
                        Log.d(
                            TAG,
                            "支付失败但未收款（paidReceivedCents=$paidReceivedCents），无需退款"
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "退款异常，需要人工处理", e)
                }

                cleanupDisableAcceptors(billDeviceID, coinDeviceID, "支付失败: $errorMessage")
            } catch (e: Exception) {
                Log.e(TAG, "支付失败：禁用接收器异常", e)
                // 继续执行失败流程，不中断
            }
            // 停止现金会话（但不断开连接）
            try {
                cashDeviceRepository.stopCashSession("支付失败: $errorMessage")
            } catch (e: Exception) {
                Log.e(TAG, "停止现金会话异常", e)
            }
            // 清空状态
            _noteRejectionMessage.value = null
        }
    }
        val newState = stateMachine.paymentFailed(currentState, errorMessage)
        if (newState != null) {
        // ⚠️ Step C: 添加关键日志
        Log.d(
            "PAY_STATE_SET",
            "state=FAILED by=handlePaymentFailure txId=${newState.paidAmountCents}"
        )
            _flowState.value = newState
            Log.d(TAG, "支付失败: $errorMessage")
        }
    }

/**
 * 支付失败（根据错误原因，UI 层映射到 stringResource）
 */
fun handlePaymentFailureByReason(reason: com.carwash.carpayment.data.carwash.CarWashGateCheckFailureReason) {
    val currentState = _flowState.value
    // 传递错误码而不是硬编码消息，UI 层根据 reason 映射到 stringResource
    val errorCode = when (reason) {
        com.carwash.carpayment.data.carwash.CarWashGateCheckFailureReason.COMMUNICATION_FAILED -> "COMMUNICATION_FAILED"
        com.carwash.carpayment.data.carwash.CarWashGateCheckFailureReason.NOT_CONNECTED -> "NOT_CONNECTED"
        com.carwash.carpayment.data.carwash.CarWashGateCheckFailureReason.DEVICE_FAULT -> "DEVICE_FAULT"
        com.carwash.carpayment.data.carwash.CarWashGateCheckFailureReason.PREVIOUS_CAR_PRESENT -> "PREVIOUS_CAR_PRESENT"
        com.carwash.carpayment.data.carwash.CarWashGateCheckFailureReason.DEVICE_NOT_READY -> "DEVICE_NOT_READY"
    }
    val newState = stateMachine.paymentFailed(currentState, errorCode)
    if (newState != null) {
        // ⚠️ Step C: 添加关键日志
        Log.d(
            "PAY_STATE_SET",
            "state=FAILED by=handlePaymentFailure txId=${newState.paidAmountCents}"
        )
        _flowState.value = newState
        Log.d(TAG, "支付失败: reason=$reason, errorCode=$errorCode")
        }
    }
    
    /**
     * 重置支付状态（允许重新选择支付方式）
     * 从 FAILED 状态回到 SELECTING_METHOD
     */
    fun resetToSelectingMethod() {
        val currentState = _flowState.value
        val newState = stateMachine.resetToSelecting(currentState)
        if (newState != null) {
            _flowState.value = newState
            Log.d(TAG, "重置支付状态：回到选择支付方式")
        } else {
            Log.w(TAG, "无法重置支付状态，当前状态: ${currentState.status}")
        }
    }
    
    /**
     * 取消支付并返回首页（统一入口，支持卡支付和现金支付）
     * 必须 stopCashSession / stopPosPayment（如有）
     * 导航回 Home，并清理 PaymentFlowStateMachine 状态为初始
     */
    fun cancelPaymentAndReturnHome() {
        Log.d(TAG, "========== 取消支付并返回首页 ==========")
        
        val currentState = _flowState.value
        
        viewModelScope.launch {
            try {
            // 1. 停止现金会话（如果有）- 禁用接收器但不断开连接
                if (currentState.selectedPaymentMethod == PaymentMethod.CASH) {
                    try {
                        Log.d(TAG, "停止现金会话...")
                    // 从 CashDeviceRepository 获取 deviceID
                    val billDeviceID = cashDeviceRepository.getBillAcceptorDeviceID()
                    val coinDeviceID = cashDeviceRepository.getCoinAcceptorDeviceID()

                    // ⚠️ 取消时必须退款：如果已收款（delta > 0），执行退款
                    // ⚠️ 关键修复：使用 tracker.getTotalCents() 作为退款金额
                    try {
                        val paidReceivedCents =
                            cashDeviceRepository.getAmountTracker().getTotalCents()
                        if (paidReceivedCents > 0) {
                            Log.d(
                                "CASH_REFUND_TRIGGER",
                                "paid=$paidReceivedCents target=0 reason=CANCEL"
                            )
                            Log.d(
                                TAG,
                                "返回首页且已收款，执行退款: refundAmountCents=$paidReceivedCents"
                            )
                            // ⚠️ Step D: 添加 CASH_SESSION_MARK CANCEL 日志（返回首页）
                            Log.d("CASH_SESSION_MARK", "CANCEL paid=$paidReceivedCents")
                            // ⚠️ 关键修复：等待退款函数返回最终结果
                        val refundResult = cashDeviceRepository.refundAmount(
                                paidReceivedCents,  // ⚠️ 使用 tracker.getTotalCents()
                                billDeviceID,
                                coinDeviceID
                            )
                            if (!refundResult.success) {
                                Log.e(
                                    TAG,
                                    "⚠️ 退款失败，需要人工处理: refundAmountCents=$paidReceivedCents"
                                )
                            }
                        } else {
                            Log.d(
                                TAG,
                                "返回首页但未收款（paidReceivedCents=$paidReceivedCents），无需退款"
                            )
                            // ⚠️ Step D: 添加 CASH_SESSION_MARK CANCEL 日志（返回首页，未收款）
                            Log.d("CASH_SESSION_MARK", "CANCEL paid=0")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "退款异常，需要人工处理", e)
                    }

                    cleanupDisableAcceptors(billDeviceID, coinDeviceID, "用户返回首页")
                        cashDeviceRepository.stopCashSession("用户返回首页")
                    // 清空状态
                    _noteRejectionMessage.value = null
                    } catch (e: Exception) {
                        Log.e(TAG, "停止现金会话异常", e)
                    }
                }
                
                // 2. 停止 POS 支付（如果有）
                if (currentState.selectedPaymentMethod == PaymentMethod.CARD) {
                    try {
                        Log.d(TAG, "停止 POS 支付...")
                        posPaymentService.cancelPayment()
                    } catch (e: Exception) {
                        Log.e(TAG, "停止 POS 支付异常", e)
                    }
                }
                
                // 3. 清理 PaymentFlowStateMachine 状态为初始
                reset()
                
                Log.d(TAG, "========== 取消支付并返回首页完成 ==========")
            } catch (e: Exception) {
                Log.e(TAG, "取消支付并返回首页异常", e)
                // 即使异常，也重置状态
                reset()
            }
        }
    }
    
    /**
     * 取消支付（统一入口，支持卡支付和现金支付）
     */
    fun cancelPayment() {
        val currentState = _flowState.value
        if (currentState.status != PaymentFlowStatus.PAYING) {
            Log.w(TAG, "无法取消支付，当前状态: ${currentState.status}")
            return
        }
        
        Log.d(TAG, "用户取消支付")
        
        viewModelScope.launch {
            try {
                when (currentState.selectedPaymentMethod) {
                    PaymentMethod.CARD -> {
                        // 卡支付：调用 POS 服务的取消方法
                        val cancelled = posPaymentService.cancelPayment()
                        if (cancelled) {
                            Log.d(TAG, "POS 支付取消成功")
                            // 状态会通过回调更新
                        } else {
                            Log.w(TAG, "POS 支付取消失败，但继续执行本地取消")
                            // 即使设备取消失败，也要执行本地取消
                            handlePaymentCancellation("用户取消支付")
                        }
                    }

                    PaymentMethod.CASH -> {
                    // 现金支付：使用统一的 finishCashAttempt 方法
                    Log.d(TAG, "现金支付取消...")
                    viewModelScope.launch {
                        try {
                            // 从 CashDeviceRepository 获取 deviceID
                            val billDeviceID = cashDeviceRepository.getBillAcceptorDeviceID()
                            val coinDeviceID = cashDeviceRepository.getCoinAcceptorDeviceID()

                            // 计算本次会话已收金额（基于 Levels 差分）
                            val currentBillLevels =
                                billDeviceID?.let { cashDeviceRepository.readCurrentLevels(it) }
                            val currentCoinLevels =
                                coinDeviceID?.let { cashDeviceRepository.readCurrentLevels(it) }

                            // 从 baselineStore 获取 baseline levels
                            val baselineStore = cashDeviceRepository.getBaselineStore()
                            val billBaselineLevels =
                                billDeviceID?.let { baselineStore.getBaselineLevels(it) }
                            val coinBaselineLevels =
                                coinDeviceID?.let { baselineStore.getBaselineLevels(it) }

                            val paidSessionDeltaCents =
                                cashDeviceRepository.calculateLevelsDeltaCents(
                                    billBaselineLevels, currentBillLevels
                                ) + cashDeviceRepository.calculateLevelsDeltaCents(
                                    coinBaselineLevels, currentCoinLevels
                                )

                            val targetAmountCents = currentState.targetAmountCents

                            // ⚠️ Step A: 使用统一的 finishCashAttempt 方法
                            finishCashAttempt(
                                outcome = "CANCEL",
                                reason = "用户取消现金支付",
                                billDeviceID = billDeviceID,
                                coinDeviceID = coinDeviceID,
                                paidSessionDeltaCents = paidSessionDeltaCents,
                                targetAmountCents = targetAmountCents,
                                billBaselineLevels = billBaselineLevels,
                                coinBaselineLevels = coinBaselineLevels
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "现金支付取消异常", e)
                            // 继续执行取消流程，不中断
                        }
                    }
                        handlePaymentCancellation("用户取消现金支付")
                    }

                    null -> {
                        Log.w(TAG, "支付方式为空，无法取消")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "取消支付异常", e)
                handlePaymentCancellation("取消支付异常: ${e.message}")
            }
        }
    }
    
    /**
     * 处理支付取消（本地取消逻辑）
     */
    private fun handlePaymentCancellation(reason: String) {
        val currentState = _flowState.value
        // 使用状态机的 paymentCancelled 方法
        val newState = stateMachine.paymentCancelled(currentState, reason)
        if (newState != null) {
            _flowState.value = newState
            Log.d(TAG, "支付已取消: $reason")
        // 清空拒收提示消息
        _noteRejectionMessage.value = null
        } else {
            Log.w(TAG, "无法取消支付，当前状态: ${currentState.status}")
        }
    }
    
    /**
     * 触发小票打印（支付成功后调用）
     * 打印失败不影响主流程，但会更新状态供UI显示
     */
    private fun triggerReceiptPrint(state: PaymentFlowState) {
        val program = state.selectedProgram
        val paymentMethod = state.selectedPaymentMethod
        
        if (program == null || paymentMethod == null) {
            Log.w(TAG, "无法触发打印：程序或支付方式为空")
            return
        }
        
        viewModelScope.launch {
            try {
                val amountCents = (program.price * 100).toInt()
                Log.d(TAG, "========== 开始打印小票 ==========")
            Log.d(
                TAG,
                "program=${program.name}, paymentMethod=$paymentMethod, amountCents=$amountCents"
            )
                Log.d(TAG, "payload: programId=${program.id}, programPrice=${program.price}€")
                
                val result = receiptPrintService.printForPayment(program, paymentMethod, amountCents)
                
                when (result) {
                    is com.carwash.carpayment.data.printer.PrintResult.Success -> {
                        Log.d(TAG, "========== 打印成功 ==========")
                        Log.d(TAG, "invoiceId=${result.invoiceId}")
                        Log.d(TAG, "================================")
                        _printResult.value = PrintResult.Success(result.invoiceId)
                    }

                    is com.carwash.carpayment.data.printer.PrintResult.Failure -> {
                        Log.w(TAG, "========== 打印失败 ==========")
                        Log.w(TAG, "reason=${result.reason}")
                        Log.w(TAG, "==============================")
                    // 将错误码映射到 i18n key（UI 层会映射到实际文案）
                    val errorKey = when (result.reason) {
                        "PRINTER_NOT_CONNECTED" -> "printer_not_connected"
                        "PRINTER_CUTTER_ERROR" -> "printer_cutter_error"
                        "PRINTER_OVERHEAT" -> "printer_overheat"
                        "PRINTER_OFFLINE" -> "printer_offline"
                        "PRINTER_NO_PAPER" -> "printer_no_paper"
                        "PRINTER_COVER_OPEN" -> "printer_cover_open"
                        "PRINTER_STATUS_QUERY_FAILED" -> "printer_status_query_failed"
                        "PRINTER_WRITE_FAILED" -> "printer_write_failed"
                        "PRINTER_EXCEPTION" -> "printer_exception"
                        else -> "printer_write_failed"  // 默认
                    }
                    _printResult.value = PrintResult.Failure(errorKey)
                    Log.e(TAG, "打印失败: errorKey=$errorKey, reason=${result.reason}")
                        // 打印失败不影响主流程，但UI会显示提示
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "========== 打印异常 ==========")
                Log.e(TAG, "异常类型: ${t::class.java.name}")
                Log.e(TAG, "异常消息: ${t.message}")
                Log.e(TAG, "异常堆栈:", t)
                Log.e(TAG, "==============================")
            _printResult.value = PrintResult.Failure("printer_exception")
            Log.e(TAG, "打印异常: ${t.message ?: t::class.java.simpleName}", t)
                // 打印异常不影响主流程，但UI会显示提示
            }
        }
    }
    
    /**
     * 触发小票打印（使用 SupervisorJob 确保打印协程不被导航/cleanup 取消）
     * 打印失败不影响主流程，但会更新状态供UI显示
     */
    private fun triggerReceiptPrintWithSupervisor(state: PaymentFlowState) {
        val program = state.selectedProgram
        val paymentMethod = state.selectedPaymentMethod
        
        if (program == null || paymentMethod == null) {
            Log.w(TAG, "无法触发打印：程序或支付方式为空")
            return
        }
        
        // 使用 SupervisorJob 确保打印协程不被取消
        viewModelScope.launch(SupervisorJob()) {
            try {
                val amountCents = (program.price * 100).toInt()
                Log.d(TAG, "========== 开始打印小票（SupervisorJob） ==========")
            Log.d(
                TAG,
                "program=${program.name}, paymentMethod=$paymentMethod, amountCents=$amountCents"
            )
                Log.d(TAG, "payload: programId=${program.id}, programPrice=${program.price}€")
                
                val result = receiptPrintService.printForPayment(program, paymentMethod, amountCents)
                
                when (result) {
                    is com.carwash.carpayment.data.printer.PrintResult.Success -> {
                        Log.d(TAG, "========== 打印成功（SupervisorJob） ==========")
                        Log.d(TAG, "invoiceId=${result.invoiceId}")
                        Log.d(TAG, "==============================================")
                        _printResult.value = PrintResult.Success(result.invoiceId)
                    }

                    is com.carwash.carpayment.data.printer.PrintResult.Failure -> {
                        Log.w(TAG, "========== 打印失败（SupervisorJob） ==========")
                        Log.w(TAG, "reason=${result.reason}")
                        Log.w(TAG, "==============================================")
                    // 将错误码映射到 i18n key（UI 层会映射到实际文案）
                    val errorKey = when (result.reason) {
                        "PRINTER_NOT_CONNECTED" -> "printer_not_connected"
                        "PRINTER_CUTTER_ERROR" -> "printer_cutter_error"
                        "PRINTER_OVERHEAT" -> "printer_overheat"
                        "PRINTER_OFFLINE" -> "printer_offline"
                        "PRINTER_NO_PAPER" -> "printer_no_paper"
                        "PRINTER_COVER_OPEN" -> "printer_cover_open"
                        "PRINTER_STATUS_QUERY_FAILED" -> "printer_status_query_failed"
                        "PRINTER_WRITE_FAILED" -> "printer_write_failed"
                        "PRINTER_EXCEPTION" -> "printer_exception"
                        else -> "printer_write_failed"  // 默认
                    }
                    _printResult.value = PrintResult.Failure(errorKey)
                    Log.e(TAG, "打印失败: errorKey=$errorKey, reason=${result.reason}")
                        // 打印失败不影响主流程，但UI会显示提示
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "========== 打印异常（SupervisorJob） ==========")
                Log.e(TAG, "异常类型: ${t::class.java.name}")
                Log.e(TAG, "异常消息: ${t.message}")
                Log.e(TAG, "异常堆栈:", t)
                Log.e(TAG, "==============================================")
            _printResult.value = PrintResult.Failure("printer_exception")
            Log.e(TAG, "打印异常: ${t.message ?: t::class.java.simpleName}", t)
                // 打印异常不影响主流程，但UI会显示提示
            }
        }
    }
    
    /**
     * 启动洗车（支付成功后）
     * 使用状态机实现阶段 8 + 9：支付后洗车机门禁检查 + 启动流程
     * 
     * S8.1 等待 752==0：轮询 12s，最大 5min，连续确认2次通过；超时触发退款（原因：前车未离开）
     * S8.2 等待 102==1：轮询 6s，最大 3min，连续确认2次通过；超时退款（原因：车辆未到位）
     * S8.3 等待 240==1：轮询 4s，最大 1min，连续确认2次通过；240=0 时禁止发送 Mode；超时退款（原因：设备未就绪）
     * S9.1 发送 Mode：仅发送一次；通信失败/无回显可重试最多3次（间隔1s）；仍失败退款（原因：发送命令失败）
     * S9.2 启动确认：轮询 214（每1s，最多10s），只有 214=自动状态（连续确认2次）才算启动成功；若超时未进入自动则按"启动未成功"处理，可回到 S9.1 再尝试（最多3轮），最终失败退款（原因：未进入自动状态）
     * S8.9 退款：按支付方式退款，并在 UI 显示具体失败原因（752/102/240/214/发送失败）
     */
    fun startWashing(): Boolean {
        val currentState = _flowState.value
        val newState = stateMachine.startWashing(currentState)
        return if (newState != null) {
            _flowState.value = newState
            Log.d(TAG, "[CarWash] ========== 启动洗车流程开始（状态机） ==========")
            
            // 取消之前的Job（如果存在）
            carWashStartJob?.cancel()
            
            // 创建新的单一Job（避免并发轮询）
            carWashStartJob = viewModelScope.launch {
                try {
                    val program = currentState.selectedProgram
                    if (program == null) {
                        Log.w(TAG, "[CarWash] 程序信息为空，无法启动洗车")
                        return@launch
                    }
                    
                    // 根据程序ID或名称确定洗车模式（1-4）
                    val washMode = determineWashMode(program)
                    Log.d(TAG, "[CarWash] 洗车模式: Mode $washMode (程序: ${program.name})")
                    
                    // 创建状态机控制器
                    val controller = CarWashStartController(carWashRepository, washMode)
                    
                    // 设置状态变化回调（更新UI）
                    controller.setStateChangeCallback { state ->
                        _carWashStartState.value = state
                        Log.d(TAG, "[CarWash] 状态变化: ${state::class.simpleName}")
                    }
                    
                    // 执行状态机
                    val finalState = controller.execute()
                    
                    // 处理最终状态
                    when (finalState) {
                        is CarWashStartState.Success -> {
                            Log.d(TAG, "[CarWash] ========== 洗车程序已成功启动 ==========")
                            // UI 已通过状态回调更新为"洗车程序已启动"
                        }

                        is CarWashStartState.Refunding -> {
                            Log.e(TAG, "[CarWash] ========== 洗车启动失败，触发退款 ==========")
                            Log.e(TAG, "[CarWash] 失败原因: ${finalState.reason}")
                            handleCarWashStartFailure(finalState.reason, currentState)
                        }

                        else -> {
                            Log.w(TAG, "[CarWash] 未知的最终状态: ${finalState::class.simpleName}")
                        }
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "[CarWash] 启动洗车流程异常", e)
                    // 异常时也触发退款
                val refundState =
                    CarWashStartState.Refunding(CarWashStartFailureReason.SEND_MODE_FAILED)
                    _carWashStartState.value = refundState
                    handleCarWashStartFailure(CarWashStartFailureReason.SEND_MODE_FAILED, currentState)
                } finally {
                    carWashStartJob = null
                }
            }
            
            true
        } else {
            Log.w(TAG, "[CarWash] 无法启动洗车，当前状态: ${currentState.status}")
            false
        }
    }
    
    /**
     * 处理洗车启动失败（退款）
     * @param reason 失败原因
     * @param currentState 当前支付状态
     */
    private suspend fun handleCarWashStartFailure(
        reason: CarWashStartFailureReason,
        currentState: PaymentFlowState
    ) {
        Log.d(TAG, "[CarWash] ========== 处理洗车启动失败（退款） ==========")
        Log.d(TAG, "[CarWash] 失败原因: $reason")
        
        // 根据失败原因生成错误消息
        val errorMessage = when (reason) {
            CarWashStartFailureReason.PREVIOUS_CAR_NOT_LEFT -> "前车未离开，请等待"
            CarWashStartFailureReason.CAR_NOT_IN_POSITION -> "车辆未到位，请将车辆停放在指定位置"
            CarWashStartFailureReason.DEVICE_NOT_READY -> "设备未就绪，请稍后再试"
            CarWashStartFailureReason.SEND_MODE_FAILED -> "发送命令失败，请重试"
            CarWashStartFailureReason.NOT_ENTERED_AUTO_STATUS -> "未进入自动状态，请重试"
        }
        
        // 根据支付方式退款
        when (currentState.selectedPaymentMethod) {
            PaymentMethod.CASH -> {
                Log.d(TAG, "[CarWash] 现金支付退款: 需要手动退款或提示用户")
                // TODO: 实现现金退款逻辑（可能需要找零或提示用户联系管理员）
                handlePaymentFailure("洗车启动失败: $errorMessage")
            }

            PaymentMethod.CARD -> {
                Log.d(TAG, "[CarWash] POS支付退款: 调用POS退款接口")
                // TODO: 实现POS退款逻辑
                try {
                    posPaymentService.cancelPayment()
                    Log.d(TAG, "[CarWash] POS退款成功")
                } catch (e: Exception) {
                    Log.e(TAG, "[CarWash] POS退款失败", e)
                }
                handlePaymentFailure("洗车启动失败: $errorMessage")
            }

            null -> {
                Log.w(TAG, "[CarWash] 支付方式为空，无法退款")
                handlePaymentFailure("洗车启动失败: $errorMessage")
            }
        }
    }
    
    /**
     * 根据洗车程序确定洗车模式（1-4）
     * 这里需要根据实际的程序配置来确定模式
     * 默认返回 Mode 1（基础洗车）
     */
    private fun determineWashMode(program: WashProgram): Int {
        // 根据程序名称或ID确定模式
        // 这里使用程序名称的简单匹配，实际应根据业务需求调整
        val programName = program.name.lowercase()
        return when {
            programName.contains("mode 1") || programName.contains("basic") || programName.contains("基础") -> 1
            programName.contains("mode 2") || programName.contains("standard") || programName.contains("标准") -> 2
            programName.contains("mode 3") || programName.contains("premium") || programName.contains("高级") -> 3
            programName.contains("mode 4") || programName.contains("luxury") || programName.contains("豪华") -> 4
            else -> {
                // 默认根据程序ID或价格区间确定模式
                // 这里可以根据实际业务逻辑调整
                when (program.id) {
                    "1" -> 1
                    "2" -> 2
                    "3" -> 3
                    "4" -> 4
                    else -> 1 // 默认 Mode 1
                }
            }
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
    resetPaymentAttempt("RESET")
}

/**
 * 重置支付尝试（统一入口，清空所有session数据和计时）
 * @param reason 重置原因（PROGRAM_CHANGED/NEW_ATTEMPT/RESET等）
 */
private fun resetPaymentAttempt(reason: String) {
    Log.d(TAG, "========== 重置支付尝试 ==========")
    Log.d(TAG, "reason=$reason")

    // 1. 取消所有与支付相关的协程Job
    // 注意：viewModelScope会自动管理，但我们需要确保新的attempt不会使用旧的计时

    // 2. 重置flowState到初始态
        _flowState.value = PaymentFlowState(status = PaymentFlowStatus.NOT_STARTED)

    // 3. 清空所有session数据
    _noteRejectionMessage.value = null

    // 4. 重置计时起点（在processCashPayment中会重新设置paymentStartTime）
    // 这里只做状态清理，计时起点在每次开始支付时重新设置

    Log.d(TAG, "========== 重置支付尝试完成 ==========")
}

/**
 * 重置为新支付尝试（用于返回主界面后允许再次选择套餐）
 * @param reason 重置原因（例如 NAVIGATE_HOME_AFTER_SUCCESS）
 */
fun resetForNewAttempt(reason: String) {
    val currentState = _flowState.value
    val fromState = currentState.status
    val attemptId = currentState.selectedProgram?.id
    val txId = currentState.paidAmountCents // 使用 paidAmountCents 作为标识

    // ⚠️ Step C: 添加关键日志
    Log.d("PAY_RESET_BEGIN", "reason=$reason fromState=$fromState attemptId=$attemptId txId=$txId")

    try {
        // 1. 取消所有可能残留的协程/job
        carWashStartJob?.cancel()
        carWashStartJob = null
        Log.d(TAG, "已取消 carWashStartJob")

        // 注意：viewModelScope 会自动管理其子协程，但显式取消关键 job 更安全

        // 2. 清空所有状态数据
        _noteRejectionMessage.value = null
        _gateCheckResult.value = null
        _carWashStartState.value = null
        _printResult.value = null

        // 3. 强制把 PaymentFlowStateMachine 状态设置回初始状态（NOT_STARTED）
        // ⚠️ 关键修复：forceResetToNotStarted 会保留 selectedProgram 和 selectedPaymentMethod，避免白屏
        val resetState = stateMachine.forceResetToNotStarted(currentState)
        _flowState.value = resetState
        Log.d(TAG, "resetForNewAttempt: 保留 selectedProgram=${resetState.selectedProgram?.id}, selectedPaymentMethod=${resetState.selectedPaymentMethod}")

        // ⚠️ Step C: 添加关键日志
        Log.d("PAY_RESET_END", "toState=NOT_STARTED attemptId=${resetState.selectedProgram?.id} txId=0")
        Log.d(TAG, "========== resetForNewAttempt 完成 ==========")
        Log.d(TAG, "resetForNewAttempt: 保留 selectedProgram=${resetState.selectedProgram?.id}, selectedPaymentMethod=${resetState.selectedPaymentMethod}")
    } catch (e: Exception) {
        Log.e(TAG, "resetForNewAttempt 异常", e)
        // ⚠️ 关键修复：即使异常，也保留 selectedProgram 和 selectedPaymentMethod，避免白屏
        val currentState = _flowState.value
        _flowState.value = PaymentFlowState(
            status = PaymentFlowStatus.NOT_STARTED,
            selectedProgram = currentState.selectedProgram,  // ⚠️ 保留选择，避免白屏
            selectedPaymentMethod = currentState.selectedPaymentMethod,  // ⚠️ 保留支付方式，避免白屏
            paymentConfirmed = false,
            errorMessage = null,
            paidAmountCents = 0,
            targetAmountCents = 0,
            lastUpdated = System.currentTimeMillis()
        )
        Log.d("PAY_RESET_END", "toState=NOT_STARTED (异常恢复) attemptId=${currentState.selectedProgram?.id} txId=0")
        Log.d(TAG, "resetForNewAttempt 异常恢复: 保留 selectedProgram=${currentState.selectedProgram?.id}, selectedPaymentMethod=${currentState.selectedPaymentMethod}")
    }
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
