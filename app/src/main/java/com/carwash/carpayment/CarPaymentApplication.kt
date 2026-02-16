package com.carwash.carpayment

import android.app.Application
import android.util.Log
import com.carwash.carpayment.data.cashdevice.CashDeviceClient
import com.carwash.carpayment.data.cashdevice.CashDeviceRepository
import com.carwash.carpayment.data.cashdevice.device.CashDevice
import com.carwash.carpayment.data.cashdevice.device.SpectralPayoutDevice
import com.carwash.carpayment.data.cashdevice.device.SmartCoinSystemDevice
import com.carwash.carpayment.data.cashdevice.device.CashDeviceInitializer
import com.carwash.carpayment.data.cashdevice.device.CashDeviceStatusMonitor
import com.carwash.carpayment.data.carwash.CarWashDeviceClient
import com.carwash.carpayment.data.carwash.CarWashDeviceRepository
import com.carwash.carpayment.ui.viewmodel.WashFlowViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application 类，用于初始化 USDK SDK
 * 
 * 根据 USDK 文档，USDK 应在 Application.onCreate() 中初始化
 */
class CarPaymentApplication : Application() {
    
    companion object {
        private const val TAG = "CarPaymentApplication"
        
        // 洗车机单例（应用生命周期）
        @Volatile
        var carWashApi: CarWashDeviceClient? = null
            private set
        
        @Volatile
        var carWashRepository: CarWashDeviceRepository? = null
            private set
        
        // 洗车流程 ViewModel 单例（应用生命周期）
        @Volatile
        var washFlowViewModel: com.carwash.carpayment.ui.viewmodel.WashFlowViewModel? = null
            private set
        
        // ⚠️ V3.2 新增：现金设备单例（应用生命周期）
        @Volatile
        var billAcceptor: CashDevice? = null
            private set
        
        @Volatile
        var coinAcceptor: CashDevice? = null
            private set
        
        @Volatile
        var cashDeviceStatusMonitor: CashDeviceStatusMonitor? = null
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // 设置安全的默认异常处理器（防止 AbstractMethodError 等未捕获异常导致崩溃）
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("Crash", "Uncaught exception on ${thread.name}: ${throwable::class.java.name}, message=${throwable.message}", throwable)
            // 不调用系统默认处理器，避免重复处理
            // 如果需要上报崩溃，可以在这里添加
        }
        
        Log.d(TAG, "========== Application.onCreate() ==========")
        Log.d(TAG, "开始初始化 USDK SDK...")
        Log.d(TAG, "根据文档，USDK 应在 Application.onCreate() 中调用 USDK.getInstance().init(context)")
        
        try {
            // 使用反射方式调用 USDK SDK（避免编译时依赖）
            val usdkClass = Class.forName("cc.uling.usdk.USDK")
            val getInstanceMethod = usdkClass.getMethod("getInstance")
            val usdk = getInstanceMethod.invoke(null)
            
            if (usdk == null) {
                Log.e(TAG, "USDK.getInstance() 返回 null")
                return
            }
            
            Log.d(TAG, "USDK 单例实例获取成功")
            
            // 尝试使用 Application 类型初始化
            // 根据文档，init(context) 方法签名
            val initMethod = try {
                val applicationClass = Class.forName("android.app.Application")
                usdkClass.getMethod("init", applicationClass)
            } catch (e: NoSuchMethodException) {
                // 如果 Application 类型失败，尝试 Context 类型
                Log.w(TAG, "尝试使用 Context 类型初始化...")
                usdkClass.getMethod("init", android.content.Context::class.java)
            }
            
            initMethod.invoke(usdk, this)
            Log.d(TAG, "========== USDK SDK 初始化成功 ==========")
            Log.d(TAG, "使用的方法签名: ${initMethod.parameterTypes.joinToString { it.simpleName }}")
            Log.d(TAG, "注意: USDK 只初始化一次，后续串口扫描时不再重复调用 init()")
            
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "USDK SDK 类未找到，跳过初始化", e)
            Log.w(TAG, "注意: 如果实际使用 POS 功能，请确保 usdk_v2024112602.jar 已正确添加到 classpath")
        } catch (e: NoSuchMethodException) {
            Log.e(TAG, "USDK.init() 方法未找到", e)
            Log.e(TAG, "尝试查找所有 init 方法...")
            try {
                val usdkClass = Class.forName("cc.uling.usdk.USDK")
                val methods = usdkClass.methods.filter { it.name == "init" }
                Log.e(TAG, "找到的 init 方法: ${methods.map { it.parameterTypes.joinToString { it.simpleName } }}")
            } catch (e: Throwable) {
                // 忽略
                Log.w(TAG, "查找 init 方法时出错", e)
            }
        } catch (t: Throwable) {
            // 捕获所有异常（包括 Error 类）
            Log.e(TAG, "初始化 USDK SDK 失败: ${t::class.java.name}, message=${t.message}", t)
            Log.e(TAG, "错误详情: ${t.message}")
        }
        
        // ⚠️ V3.2 重构：APP 启动时初始化现金设备（使用新的驱动层架构）
        // 关键：设备配置依赖 AES128 会话，任何断开都会导致设置失效
        // 因此 APP 启动后就应该初始化并连接纸币器/硬币器（只是默认不启用收款 enable）
        try {
            Log.d(TAG, "========== V3.2 APP 启动时初始化现金设备 ==========")
            val cashDeviceApi = CashDeviceClient.create(context = this)
            
            // ⚠️ V3.2 关键修复：创建 CashDeviceRepository 用于保存设备ID
            val cashDeviceRepository = CashDeviceRepository(cashDeviceApi)
            
            // 创建设备驱动实例
            val billAcceptor = SpectralPayoutDevice(cashDeviceApi)
            val coinAcceptor = SmartCoinSystemDevice(cashDeviceApi)
            
            // 保存为单例
            CarPaymentApplication.billAcceptor = billAcceptor
            CarPaymentApplication.coinAcceptor = coinAcceptor
            
            // ⚠️ V3.2 关键修复：创建设备初始化器，传递 Repository 用于保存设备ID
            val initializer = CashDeviceInitializer(billAcceptor, coinAcceptor, cashDeviceRepository)
            
            // 创建状态监测器
            val statusMonitor = CashDeviceStatusMonitor(billAcceptor, coinAcceptor)
            CarPaymentApplication.cashDeviceStatusMonitor = statusMonitor
            
            // 在后台协程中初始化设备连接（不阻塞启动）
            val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            appScope.launch {
                try {
                    // ⚠️ V3.2：仅在 Application 启动时调用一次 OpenConnection
                    // ⚠️ 关键修复：OpenConnection 成功后，DeviceID 会自动保存到 Repository
                    Log.d(TAG, "开始初始化现金设备连接...")
                    initializer.initialize()
                    Log.d(TAG, "✅ 现金设备连接初始化完成")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 现金设备连接初始化异常（不影响应用启动）", e)
                }
                
                // 启动设备状态监测（独立运行，不受支付流程影响）
                // ⚠️ V3.2 规范：使用 GetDeviceStatus 定期轮询，间隔 350-500ms
                try {
                    Log.d(TAG, "启动设备状态监测...")
                    statusMonitor.startMonitoring(appScope)
                    Log.d(TAG, "✅ 设备状态监测已启动（轮询间隔: ${com.carwash.carpayment.data.cashdevice.CashPaymentConfig.DEVICE_STATUS_POLL_INTERVAL}ms）")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 启动设备状态监测异常（不影响应用启动）", e)
                }
                
                Log.d(TAG, "========== V3.2 现金设备初始化完成 ==========")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "启动现金设备初始化失败", t)
            // 不阻塞应用启动，继续执行
        }
        
        // APP 启动时自动连接洗车机（后台执行，不阻塞启动）
        // ⚠️ 创建单例，供全局复用
        try {
            Log.d(TAG, "========== APP 启动时自动连接洗车机 ==========")
            val carWashApi = CarWashDeviceClient(context = this)
            val carWashRepository = CarWashDeviceRepository(carWashApi)
            
            // 保存单例到 companion object（供全局访问）
            CarPaymentApplication.carWashApi = carWashApi
            CarPaymentApplication.carWashRepository = carWashRepository
            
            // 在后台协程中初始化洗车机（不阻塞启动）
            val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            appScope.launch {
                try {
                    Log.d(TAG, "开始连接洗车机...")
                    val success = carWashRepository.connect()
                    if (success) {
                        Log.d(TAG, "✅ 洗车机连接成功")
                        // 连接成功后，检查一次状态
                        val statusCheck = carWashRepository.checkStatus()
                        Log.d(TAG, "洗车机状态检查: isAvailable=${statusCheck.isAvailable}, errorMessage=${statusCheck.errorMessage}")
                    } else {
                        Log.w(TAG, "❌ 洗车机连接失败（不影响其他设备）")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 洗车机初始化异常（不影响其他设备）", e)
                }
                
                Log.d(TAG, "========== 洗车机自动连接完成 ==========")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "启动洗车机自动连接失败", t)
            // 不阻塞应用启动，继续执行
        }
        
        // 初始化 WashFlowViewModel 单例（供全局访问）
        try {
            Log.d(TAG, "========== 初始化 WashFlowViewModel ==========")
            val washFlowViewModel = WashFlowViewModel(this)
            CarPaymentApplication.washFlowViewModel = washFlowViewModel
            Log.d(TAG, "========== WashFlowViewModel 初始化完成 ==========")
        } catch (t: Throwable) {
            Log.e(TAG, "初始化 WashFlowViewModel 失败", t)
            // 不阻塞应用启动，继续执行
        }
    }
}
