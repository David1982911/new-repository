package com.carwash.carpayment.ui.viewmodel

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carwash.carpayment.data.printer.ReceiptPrinter
import com.carwash.carpayment.data.printer.ReceiptSettings
import com.carwash.carpayment.data.printer.ReceiptSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "PrinterTabViewModel"
private const val ACTION_USB_PERMISSION = "com.carwash.carpayment.USB_PERMISSION"

/**
 * 打印机 Tab ViewModel
 */
class PrinterTabViewModel(application: Application) : AndroidViewModel(application) {
    
    private val receiptSettingsRepository = ReceiptSettingsRepository(application)
    private val receiptPrinter = ReceiptPrinter(application)
    private val usbManager = application.getSystemService(Context.USB_SERVICE) as? UsbManager
    
    // 小票配置状态
    private val _settings = MutableStateFlow(ReceiptSettings())
    val settings: StateFlow<ReceiptSettings> = _settings.asStateFlow()
    
    // 打印机连接状态
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    // 错误信息
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    // 状态信息
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()
    
    // USB 权限请求的 PendingIntent
    private var pendingPermissionIntent: PendingIntent? = null
    private var pendingUsbDevice: UsbDevice? = null
    
    // 接收器注册状态
    private var isReceiverRegistered = false
    
    // USB 权限接收器
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            Log.d(TAG, "USB 权限已授予: ${it.deviceName}")
                            viewModelScope.launch {
                                try {
                                    connectUsbDevice(it)
                                } catch (t: Throwable) {
                                    Log.e(TAG, "connectPrinter failed: ${t::class.java.name}, message=${t.message}", t)
                                    _errorMessage.value = "连接失败: ${t.message ?: t::class.java.simpleName}"
                                    _statusMessage.value = null
                                    _isConnected.value = false
                                }
                            }
                        }
                    } else {
                        Log.w(TAG, "USB 权限被拒绝: ${device?.deviceName}")
                        _errorMessage.value = "USB 权限被拒绝"
                        _statusMessage.value = null
                    }
                }
            }
        }
    }
    
    init {
        // 加载小票配置
        viewModelScope.launch {
            try {
                receiptSettingsRepository.receiptSettings.collect { settings ->
                    _settings.value = settings
                }
            } catch (t: Throwable) {
                Log.e(TAG, "加载小票配置失败: ${t::class.java.name}, message=${t.message}", t)
                _errorMessage.value = "加载配置失败: ${t.message}"
            }
        }
        
        // 更新连接状态（安全获取，在协程中调用 suspend 函数）
        viewModelScope.launch {
            try {
                _isConnected.value = receiptPrinter.isConnected()
            } catch (t: Throwable) {
                Log.e(TAG, "获取打印机连接状态失败: ${t::class.java.name}, message=${t.message}", t)
                _isConnected.value = false
            }
        }
        
        // 创建 PendingIntent（轻量操作，不会失败）
        try {
            val intent = Intent(ACTION_USB_PERMISSION)
            pendingPermissionIntent = PendingIntent.getBroadcast(
                getApplication(),
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } catch (t: Throwable) {
            Log.e(TAG, "创建 PendingIntent 失败: ${t::class.java.name}, message=${t.message}", t)
        }
        
        // 注意：USB 接收器注册移到 onEnterScreen()，不在 init 中执行
    }
    
    /**
     * 进入打印机 Tab 时调用（生命周期方法）
     * 注册 USB 接收器，可选扫描设备
     */
    fun onEnterScreen() {
        try {
            if (!isReceiverRegistered) {
                val filter = IntentFilter(ACTION_USB_PERMISSION)
                val context = getApplication<Application>()
                
                // Android 13+ (API 33+) 需要指定 RECEIVER_EXPORTED 或 RECEIVER_NOT_EXPORTED
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    context.registerReceiver(usbPermissionReceiver, filter)
                }
                
                isReceiverRegistered = true
                Log.d(TAG, "USB 接收器注册成功")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "注册 USB 接收器失败: ${t::class.java.name}, message=${t.message}", t)
            _errorMessage.value = "初始化 USB 接收器失败: ${t.message ?: t::class.java.simpleName}"
            isReceiverRegistered = false
        }
    }
    
    /**
     * 离开打印机 Tab 时调用（生命周期方法）
     * 取消注册 USB 接收器
     */
    fun onLeaveScreen() {
        try {
            if (isReceiverRegistered) {
                getApplication<Application>().unregisterReceiver(usbPermissionReceiver)
                isReceiverRegistered = false
                Log.d(TAG, "USB 接收器已取消注册")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "取消注册 USB 接收器失败: ${t::class.java.name}, message=${t.message}", t)
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        // 确保在 ViewModel 销毁时也取消注册
        onLeaveScreen()
    }
    
    /**
     * 更新 Header Title
     */
    fun updateHeaderTitle(title: String) {
        _settings.value = _settings.value.copy(headerTitle = title)
    }
    
    /**
     * 更新 Store Address
     */
    fun updateStoreAddress(address: String) {
        _settings.value = _settings.value.copy(storeAddress = address)
    }
    
    /**
     * 保存小票配置
     */
    fun saveSettings() {
        viewModelScope.launch {
            try {
                receiptSettingsRepository.saveSettings(_settings.value)
                _statusMessage.value = "配置已保存"
                Log.d(TAG, "小票配置已保存")
            } catch (t: Throwable) {
                Log.e(TAG, "保存小票配置失败: ${t::class.java.name}, message=${t.message}", t)
                _errorMessage.value = "保存失败: ${t.message ?: t::class.java.simpleName}"
            }
        }
    }
    
    /**
     * 检查设备是否有 USB Printer Class 接口（interfaceClass == 7）
     */
    private fun hasPrinterInterface(device: UsbDevice): Boolean {
        try {
            for (i in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(i)
                if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                    return true
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "检查 Printer Interface 失败: ${t.message}", t)
        }
        return false
    }
    
    /**
     * 检查设备是否有 Bulk OUT endpoint（打印必需）
     */
    private fun hasBulkOut(device: UsbDevice): Boolean {
        try {
            for (i in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(i)
                for (j in 0 until usbInterface.endpointCount) {
                    val endpoint = usbInterface.getEndpoint(j)
                    if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        endpoint.direction == UsbConstants.USB_DIR_OUT) {
                        return true
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "检查 Bulk OUT endpoint 失败: ${t.message}", t)
        }
        return false
    }
    
    /**
     * 从设备列表中选择打印机候选设备
     * 优先级：1) USB Printer Class (interfaceClass == 7)  2) 有 Bulk OUT endpoint
     */
    private fun pickPrinterCandidate(deviceList: Collection<UsbDevice>): UsbDevice? {
        // 先打印所有设备的摘要日志
        Log.d(TAG, "=== USB 设备列表（共 ${deviceList.size} 个）===")
        deviceList.forEach { device ->
            val hasPrinter = hasPrinterInterface(device)
            val hasBulk = hasBulkOut(device)
            Log.d(TAG, "USB 设备: deviceName=${device.deviceName}, VID=0x${device.vendorId.toString(16).uppercase()}, PID=0x${device.productId.toString(16).uppercase()}, " +
                    "deviceClass=${device.deviceClass}, hasPrinterInterface=$hasPrinter, hasBulkOut=$hasBulk")
        }
        Log.d(TAG, "=========================================")
        
        // 仅匹配指定的打印机设备：VID=0x0FE6、PID=0x811E
        val TARGET_VID = 0x0FE6
        val TARGET_PID = 0x811E
        
        val targetDevice = deviceList.firstOrNull { device ->
            device.vendorId == TARGET_VID && device.productId == TARGET_PID
        }
        
        if (targetDevice != null) {
            val hasBulk = hasBulkOut(targetDevice)
            if (hasBulk) {
                Log.d(TAG, "找到目标打印机设备: ${targetDevice.deviceName}, VID=0x${TARGET_VID.toString(16).uppercase()}, PID=0x${TARGET_PID.toString(16).uppercase()}, hasBulkOut=true")
                return targetDevice
            } else {
                Log.w(TAG, "目标打印机设备 ${targetDevice.deviceName} 没有 Bulk OUT endpoint，无法使用")
                return null
            }
        } else {
            Log.w(TAG, "未找到目标打印机设备（VID=0x${TARGET_VID.toString(16).uppercase()}, PID=0x${TARGET_PID.toString(16).uppercase()}）")
            Log.w(TAG, "可用 USB 设备列表：")
            deviceList.forEach { device ->
                Log.w(TAG, "  - deviceName=${device.deviceName}, VID=0x${device.vendorId.toString(16).uppercase()}, PID=0x${device.productId.toString(16).uppercase()}")
            }
            return null
        }
    }
    
    /**
     * 连接打印机（USB 模式）
     */
    fun connectPrinter() {
        viewModelScope.launch {
            try {
                if (usbManager == null) {
                    _errorMessage.value = "无法获取 USB 管理器"
                    return@launch
                }
                
                _errorMessage.value = null
                _statusMessage.value = "正在查找 USB 打印机..."
                
                // 获取 USB 设备列表
                val deviceList = usbManager.deviceList
                if (deviceList.isEmpty()) {
                    _errorMessage.value = "未找到 USB 设备，请检查打印机是否已连接"
                    _statusMessage.value = null
                    return@launch
                }
                
                // 使用筛选逻辑选择打印机候选设备
                val usbDevice = pickPrinterCandidate(deviceList.values)
                if (usbDevice == null) {
                    _errorMessage.value = "未找到可用于打印的 USB 设备（无 Bulk OUT endpoint）"
                    _statusMessage.value = "请检查设备是否支持 USB 打印"
                    return@launch
                }
                
                Log.d(TAG, "找到 USB 打印机候选: ${usbDevice.deviceName}, vendorId=${usbDevice.vendorId}, productId=${usbDevice.productId}")
                
                // 检查是否已有权限
                if (usbManager.hasPermission(usbDevice)) {
                    Log.d(TAG, "已有 USB 权限，直接连接")
                    connectUsbDevice(usbDevice)
                } else {
                    Log.d(TAG, "请求 USB 权限")
                    _statusMessage.value = "正在请求 USB 权限..."
                    pendingUsbDevice = usbDevice
                    usbManager.requestPermission(usbDevice, pendingPermissionIntent)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "connectPrinter failed: ${t::class.java.name}, message=${t.message}", t)
                _errorMessage.value = "连接异常: ${t.message ?: t::class.java.simpleName}"
                _statusMessage.value = null
                _isConnected.value = false
            }
        }
    }
    
    /**
     * 连接 USB 设备（内部方法，在权限授予后调用）
     */
    private suspend fun connectUsbDevice(usbDevice: UsbDevice) {
        try {
            _statusMessage.value = "正在连接打印机..."
            
            // 显示设备信息
            val deviceInfo = "设备: ${usbDevice.deviceName}, VID=${usbDevice.vendorId}, PID=${usbDevice.productId}"
            Log.d(TAG, "尝试连接 USB 打印机: $deviceInfo")
            
            // 检查设备能力（用于诊断）
            val hasPrinter = hasPrinterInterface(usbDevice)
            val hasBulk = hasBulkOut(usbDevice)
            
            val connected = receiptPrinter.connect(usbDevice)
            _isConnected.value = connected
            
            if (connected) {
                _statusMessage.value = "打印机已连接: ${usbDevice.deviceName}"
                Log.d(TAG, "打印机连接成功: ${usbDevice.deviceName}")
            } else {
                // 提供详细的诊断信息
                val diagnosticInfo = buildString {
                    append("USB printer connect failed: No Endpoint")
                    if (!hasBulk) {
                        append(" (设备缺少 Bulk OUT endpoint)")
                    }
                    if (!hasPrinter) {
                        append(" (设备不是 USB Printer Class)")
                    }
                    append(" - 设备: ${usbDevice.deviceName}, VID=${usbDevice.vendorId}, PID=${usbDevice.productId}")
                }
                _errorMessage.value = diagnosticInfo
                _statusMessage.value = "连接失败: ${if (!hasBulk) "缺少 Bulk OUT endpoint" else if (!hasPrinter) "不是 USB Printer Class" else "未知原因"}"
                Log.e(TAG, "打印机连接失败: $diagnosticInfo")
            }
        } catch (t: Throwable) {
            val deviceInfo = "设备: ${usbDevice.deviceName}, VID=${usbDevice.vendorId}, PID=${usbDevice.productId}"
            Log.e(TAG, "connectUsbDevice failed: ${t::class.java.name}, message=${t.message}, $deviceInfo", t)
            _errorMessage.value = "USB printer connect failed: ${t.message ?: t::class.java.simpleName} ($deviceInfo)"
            _statusMessage.value = deviceInfo
            _isConnected.value = false
        }
    }
    
    /**
     * 断开打印机
     */
    fun disconnectPrinter() {
        viewModelScope.launch {
            try {
                receiptPrinter.disconnect()
                _isConnected.value = false
                _statusMessage.value = "打印机已断开"
                _errorMessage.value = null
                Log.d(TAG, "打印机已断开")
            } catch (t: Throwable) {
                Log.e(TAG, "断开打印机异常: ${t::class.java.name}, message=${t.message}", t)
                _errorMessage.value = "断开异常: ${t.message ?: t::class.java.simpleName}"
            }
        }
    }
    
    /**
     * 测试打印
     */
    fun testPrint() {
        viewModelScope.launch {
            try {
                if (!_isConnected.value) {
                    _errorMessage.value = "请先连接打印机"
                    return@launch
                }
                
                _errorMessage.value = null
                _statusMessage.value = "正在打印测试小票..."
                
                val success = receiptPrinter.testPrint(_settings.value)
                
                if (success) {
                    _statusMessage.value = "测试小票打印成功"
                    Log.d(TAG, "测试打印成功")
                } else {
                    _errorMessage.value = "打印失败，请检查打印机状态（缺纸/上盖打开等）"
                    _statusMessage.value = null
                }
            } catch (t: Throwable) {
                Log.e(TAG, "testPrint failed: ${t::class.java.name}, message=${t.message}", t)
                _errorMessage.value = "打印异常: ${t.message ?: t::class.java.simpleName}"
                _statusMessage.value = null
            }
        }
    }
    
    /**
     * 清除错误信息
     */
    fun clearError() {
        _errorMessage.value = null
    }
    
    /**
     * 清除状态信息
     */
    fun clearStatus() {
        _statusMessage.value = null
    }
}
