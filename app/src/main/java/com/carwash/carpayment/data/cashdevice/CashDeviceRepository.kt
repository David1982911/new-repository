package com.carwash.carpayment.data.cashdevice

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException

/**
 * 现金设备 Repository
 * 管理纸币器和硬币器的连接和收款
 */
class CashDeviceRepository(
    private val api: CashDeviceApi,
    private val baseUrl: String = "http://localhost:8080"  // TODO: 从配置读取
) {
    
    companion object {
        private const val TAG = "CashDeviceRepository"
        private const val DEFAULT_USERNAME = "admin"
        private const val DEFAULT_PASSWORD = "password"
        
        // 探测超时时间（秒）- 调大到 12 秒，因为 OpenConnection 需要等待设备响应
        private const val PROBE_TIMEOUT_SECONDS = 12L
        
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
     * @return OpenConnectionRequest
     */
    private fun createOpenConnectionRequest(
        comPort: Int,
        sspAddress: Int,
        deviceID: String? = null,
        enableAcceptor: Boolean = true,
        enableAutoAcceptEscrow: Boolean = true,
        enablePayout: Boolean = false
    ): OpenConnectionRequest {
        Log.d(TAG, "createOpenConnectionRequest 调用: ComPort=$comPort, SspAddress=$sspAddress, deviceID=$deviceID, enableAcceptor=$enableAcceptor")
        val request = OpenConnectionRequest(
            ComPort = comPort,
            SspAddress = sspAddress,
            DeviceID = deviceID,  // 设备ID（从 GetConnectedUSBDevices 获取）
            LogFilePath = LOG_FILE_PATH,  // 日志文件路径（可选）
            SetInhibits = null,  // 面额禁用设置（null 表示不禁用任何面额）
            SetRoutes = null,  // 面额路由设置（null 表示使用默认路由）
            SetCashBoxPayoutLimit = CASH_BOX_PAYOUT_LIMIT,  // 找零限制（null 表示不限制）
            EnableAcceptor = enableAcceptor,
            EnableAutoAcceptEscrow = enableAutoAcceptEscrow,
            EnablePayout = enablePayout
        )
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
    
    // 设备映射：Port -> SspAddress
    private var billAcceptorMapping: Pair<Int, Int>? = null  // (Port, SspAddress)
    private var coinAcceptorMapping: Pair<Int, Int>? = null  // (Port, SspAddress)
    
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
            val request = createOpenConnectionRequest(
                comPort = port,
                sspAddress = sspAddress,
                deviceID = usbDeviceID,  // GetConnectedUSBDevices 不返回 DeviceID，这里为 null 是正常的
                enableAcceptor = false,  // 探测时不启用，避免干扰
                enableAutoAcceptEscrow = false,
                enablePayout = false
            )
            Log.d(TAG, "OpenConnection 请求已创建: ComPort=${request.ComPort}, SspAddress=${request.SspAddress}, DeviceID=${request.DeviceID ?: "null（正常，将从响应中获取）"}, LogFilePath=${request.LogFilePath ?: "null"}, SetInhibits=${request.SetInhibits?.size ?: 0}项, SetRoutes=${request.SetRoutes?.size ?: 0}项, SetCashBoxPayoutLimit=${request.SetCashBoxPayoutLimit?.size ?: 0}项, EnableAcceptor=false")
            val response = probeApi.openConnection(request)
            val duration = System.currentTimeMillis() - startTime
            deviceID = response.deviceID
            
            // 显示 OpenConnection 响应的完整信息（用于调试响应格式）
            Log.d(TAG, "OpenConnection 响应（完整）: deviceID=${response.deviceID ?: "null"}, DeviceModel=${response.DeviceModel ?: "null"}, IsOpen=${response.IsOpen}, DeviceError=${response.DeviceError ?: "null"}, error=${response.error ?: "null"}, Firmware=${response.Firmware ?: "null"}, Dataset=${response.Dataset ?: "null"}, ValidatorSerialNumber=${response.ValidatorSerialNumber ?: "null"}, PayoutModuleSerialNumber=${response.PayoutModuleSerialNumber ?: "null"} (耗时${duration}ms)")
            
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
            Log.w(TAG, "探测超时: Port=$port, SspAddress=$sspAddress, timeout=${timeoutMs}ms, 实际耗时=${duration}ms")
            null
        } catch (e: retrofit2.HttpException) {
            val duration = System.currentTimeMillis() - startTime
            if (e.code() == 401) {
                Log.w(TAG, "探测失败（401 Unauthorized）: Port=$port, SspAddress=$sspAddress (耗时${duration}ms)")
            } else {
                Log.w(TAG, "探测失败（HTTP ${e.code()}）: Port=$port, SspAddress=$sspAddress, error=${e.message()} (耗时${duration}ms)")
            }
            null
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            Log.w(TAG, "探测异常（other）: Port=$port, SspAddress=$sspAddress, error=${e.message ?: e.javaClass.simpleName} (耗时${duration}ms)")
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
            Log.d(TAG, "准备创建 OpenConnection 请求: ComPort=$actualPort, SspAddress=$actualSspAddr, DeviceID=${usbDeviceID ?: "null（将从OpenConnection响应中获取）"}")
            val request = createOpenConnectionRequest(
                comPort = actualPort,
                sspAddress = actualSspAddr,
                deviceID = usbDeviceID,  // GetConnectedUSBDevices 不返回 DeviceID，这里为 null 是正常的
                enableAcceptor = true,
                enableAutoAcceptEscrow = true,
                enablePayout = false  // 关闭找零功能
            )
            Log.d(TAG, "OpenConnection 请求已创建: ComPort=${request.ComPort}, SspAddress=${request.SspAddress}, DeviceID=${request.DeviceID ?: "null（正常，将从响应中获取）"}, LogFilePath=${request.LogFilePath ?: "null"}, SetInhibits=${request.SetInhibits?.size ?: 0}项, SetRoutes=${request.SetRoutes?.size ?: 0}项, SetCashBoxPayoutLimit=${request.SetCashBoxPayoutLimit?.size ?: 0}项, EnableAcceptor=true")
            val response = api.openConnection(request)
            
            // 显示 OpenConnection 响应的完整信息（用于调试响应格式）
            Log.d(TAG, "OpenConnection 响应（完整）: deviceID=${response.deviceID ?: "null"}, DeviceModel=${response.DeviceModel ?: "null"}, IsOpen=${response.IsOpen}, DeviceError=${response.DeviceError ?: "null"}, error=${response.error ?: "null"}, Firmware=${response.Firmware ?: "null"}, Dataset=${response.Dataset ?: "null"}, ValidatorSerialNumber=${response.ValidatorSerialNumber ?: "null"}, PayoutModuleSerialNumber=${response.PayoutModuleSerialNumber ?: "null"}")
            
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
            
            if (response.deviceID != null) {
                _billAcceptorDeviceID.value = response.deviceID
                Log.d(TAG, "纸币器连接成功: deviceID=${response.deviceID}, DeviceModel=${response.DeviceModel}")
                Log.d(TAG, "设备ID获取成功: OpenConnection响应返回的deviceID=${response.deviceID}")
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
            Log.d(TAG, "准备创建 OpenConnection 请求: ComPort=$actualPort, SspAddress=$actualSspAddr, DeviceID=${usbDeviceID ?: "null（将从OpenConnection响应中获取）"}")
            val request = createOpenConnectionRequest(
                comPort = actualPort,
                sspAddress = actualSspAddr,
                deviceID = usbDeviceID,  // GetConnectedUSBDevices 不返回 DeviceID，这里为 null 是正常的
                enableAcceptor = true,
                enableAutoAcceptEscrow = true,
                enablePayout = false  // 关闭找零功能
            )
            Log.d(TAG, "OpenConnection 请求已创建: ComPort=${request.ComPort}, SspAddress=${request.SspAddress}, DeviceID=${request.DeviceID ?: "null（正常，将从响应中获取）"}, LogFilePath=${request.LogFilePath ?: "null"}, SetInhibits=${request.SetInhibits?.size ?: 0}项, SetRoutes=${request.SetRoutes?.size ?: 0}项, SetCashBoxPayoutLimit=${request.SetCashBoxPayoutLimit?.size ?: 0}项, EnableAcceptor=true")
            val response = api.openConnection(request)
            
            // 显示 OpenConnection 响应的完整信息（用于调试响应格式）
            Log.d(TAG, "OpenConnection 响应（完整）: deviceID=${response.deviceID ?: "null"}, DeviceModel=${response.DeviceModel ?: "null"}, IsOpen=${response.IsOpen}, DeviceError=${response.DeviceError ?: "null"}, error=${response.error ?: "null"}, Firmware=${response.Firmware ?: "null"}, Dataset=${response.Dataset ?: "null"}, ValidatorSerialNumber=${response.ValidatorSerialNumber ?: "null"}, PayoutModuleSerialNumber=${response.PayoutModuleSerialNumber ?: "null"}")
            
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
            
            if (response.deviceID != null) {
                _coinAcceptorDeviceID.value = response.deviceID
                Log.d(TAG, "硬币器连接成功: deviceID=${response.deviceID}, DeviceModel=${response.DeviceModel}")
                Log.d(TAG, "设备ID获取成功: OpenConnection响应返回的deviceID=${response.deviceID}")
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
     */
    suspend fun startDevice(deviceID: String): Boolean {
        return try {
            Log.d(TAG, "启动设备: deviceID=$deviceID")
            val response = api.startDevice(deviceID)
            Log.d(TAG, "StartDevice 响应: success=${response.success}, error=${response.error ?: "null"}")
            if (response.success) {
                Log.d(TAG, "设备启动成功: deviceID=$deviceID")
                true
            } else {
                Log.e(TAG, "设备启动失败: deviceID=$deviceID, error=${response.error ?: "未知错误"}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动设备异常: deviceID=$deviceID", e)
            false
        }
    }
    
    /**
     * 启用接收器
     */
    suspend fun enableAcceptor(deviceID: String): Boolean {
        return try {
            Log.d(TAG, "启用接收器: deviceID=$deviceID")
            val response = api.enableAcceptor(deviceID)
            Log.d(TAG, "EnableAcceptor 响应: success=${response.success}, error=${response.error ?: "null"}")
            if (response.success) {
                Log.d(TAG, "接收器启用成功: deviceID=$deviceID")
                true
            } else {
                Log.e(TAG, "接收器启用失败: deviceID=$deviceID, error=${response.error ?: "未知错误"}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "启用接收器异常: deviceID=$deviceID", e)
            false
        }
    }
    
    /**
     * 禁用接收器
     */
    suspend fun disableAcceptor(deviceID: String): Boolean {
        return try {
            Log.d(TAG, "禁用接收器: deviceID=$deviceID")
            val response = api.disableAcceptor(deviceID)
            if (response.success) {
                Log.d(TAG, "接收器禁用成功")
                true
            } else {
                Log.e(TAG, "接收器禁用失败: ${response.error}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "禁用接收器异常", e)
            false
        }
    }
    
    /**
     * 设置自动接受
     */
    suspend fun setAutoAccept(deviceID: String, autoAccept: Boolean = true): Boolean {
        return try {
            Log.d(TAG, "设置自动接受: deviceID=$deviceID, autoAccept=$autoAccept")
            val response = api.setAutoAccept(deviceID, autoAccept)
            Log.d(TAG, "SetAutoAccept 响应: success=${response.success}, error=${response.error ?: "null"}")
            if (response.success) {
                Log.d(TAG, "自动接受设置成功: deviceID=$deviceID, autoAccept=$autoAccept")
                true
            } else {
                Log.e(TAG, "自动接受设置失败: deviceID=$deviceID, autoAccept=$autoAccept, error=${response.error ?: "未知错误"}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "设置自动接受异常: deviceID=$deviceID, autoAccept=$autoAccept", e)
            false
        }
    }
    
    /**
     * 获取设备状态（容错处理：服务器可能返回空数组 []）
     */
    suspend fun getDeviceStatus(deviceID: String): DeviceStatusResponse? {
        return try {
            Log.d(TAG, "获取设备状态: deviceID=$deviceID")
            val response = api.getDeviceStatus(deviceID)
            Log.d(TAG, "GetDeviceStatus 响应: deviceID=${response.deviceID ?: "null"}, status=${response.status ?: "null"}, error=${response.error ?: "null"}")
            response
        } catch (e: kotlinx.serialization.SerializationException) {
            // 处理服务器返回空数组 [] 的情况
            Log.w(TAG, "获取设备状态：服务器返回了非对象格式（可能是空数组 []），返回默认状态: deviceID=$deviceID")
            // 返回一个默认的成功状态，允许系统继续运行
            DeviceStatusResponse(deviceID = deviceID, status = "UNKNOWN", error = null)
        } catch (e: Exception) {
            Log.e(TAG, "获取设备状态异常: deviceID=$deviceID", e)
            // 即使状态查询失败，也返回一个默认状态，不阻塞支付流程
            Log.w(TAG, "设备状态查询失败，但允许继续操作（容错处理）")
            DeviceStatusResponse(deviceID = deviceID, status = "UNKNOWN", error = null)
        }
    }
    
    /**
     * 断开设备连接
     */
    suspend fun disconnectDevice(deviceID: String): Boolean {
        return try {
            Log.d(TAG, "断开设备连接: deviceID=$deviceID")
            val response = api.disconnectDevice(deviceID)
            if (response.success) {
                Log.d(TAG, "设备断开成功")
                // 清除 deviceID
                if (deviceID == _billAcceptorDeviceID.value) {
                    _billAcceptorDeviceID.value = null
                }
                if (deviceID == _coinAcceptorDeviceID.value) {
                    _coinAcceptorDeviceID.value = null
                }
                true
            } else {
                Log.e(TAG, "设备断开失败: ${response.error}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "断开设备连接异常", e)
            false
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
        
        // 1. 认证（获取 token）
        val (authSuccess, authError) = authenticate()
        if (!authSuccess) {
            Log.e(TAG, "纸币器：认证失败，无法初始化: ${authError ?: "未知错误"}")
            return false
        }
        
        // 2. 如果还没有映射，先进行探测
        if (billAcceptorMapping == null) {
            val probeApiInstance = probeApi ?: api  // 如果没有提供探测 API，使用默认 API（但会慢一些）
            val (probeSuccess, probeError) = probeAndMapDevices(probeApiInstance)
            if (!probeSuccess) {
                Log.e(TAG, "纸币器：设备探测失败，无法初始化: ${probeError ?: "未知错误"}")
                return false
            }
        }
        
        // 3. 使用映射打开连接（关键步骤，失败则返回 false）
        if (!openBillAcceptorConnection()) {
            Log.e(TAG, "纸币器：打开连接失败")
            return false
        }
        
        val deviceID = _billAcceptorDeviceID.value
        if (deviceID == null) {
            Log.e(TAG, "纸币器：未获取到 deviceID")
            return false
        }
        
        Log.d(TAG, "纸币器：连接成功，deviceID=$deviceID，开始后续初始化步骤...")
        
        // 4. 启动设备（非关键步骤，失败不影响连接状态）
        val startSuccess = startDevice(deviceID)
        if (!startSuccess) {
            Log.w(TAG, "纸币器：启动设备失败（但连接已建立，可继续使用）")
        } else {
            Log.d(TAG, "纸币器：启动设备成功")
        }
        
        // 5. 启用接收器（非关键步骤，失败不影响连接状态）
        val enableSuccess = enableAcceptor(deviceID)
        if (!enableSuccess) {
            Log.w(TAG, "纸币器：启用接收器失败（但连接已建立，可稍后重试）")
        } else {
            Log.d(TAG, "纸币器：启用接收器成功")
        }
        
        // 6. 设置自动接受（非关键步骤，失败不影响连接状态）
        val autoAcceptSuccess = setAutoAccept(deviceID, true)
        if (!autoAcceptSuccess) {
            Log.w(TAG, "纸币器：设置自动接受失败（但连接已建立，可稍后重试）")
        } else {
            Log.d(TAG, "纸币器：设置自动接受成功")
        }
        
        // 只要连接成功，就认为初始化成功（即使后续步骤失败）
        val allStepsSuccess = startSuccess && enableSuccess && autoAcceptSuccess
        if (allStepsSuccess) {
            Log.d(TAG, "纸币器：初始化完全成功（所有步骤都成功）")
        } else {
            Log.w(TAG, "纸币器：初始化部分成功（连接成功，但部分步骤失败，设备仍可使用）")
        }
        
        return true  // 连接成功即返回 true，后续步骤失败不影响
    }
    
    /**
     * 初始化硬币器（完整流程）
     * 注意：每个步骤独立处理，即使某个步骤失败也会继续尝试后续步骤
     * @param probeApi 用于探测的 API 实例（应使用短超时），如果为 null 则使用默认 api
     * @return Boolean 如果设备连接成功，返回 true；如果连接失败，返回 false（启动/启用步骤失败不影响返回值）
     */
    suspend fun initializeCoinAcceptor(probeApi: CashDeviceApi? = null): Boolean {
        Log.d(TAG, "开始初始化硬币器（独立处理，不影响其他设备）")
        
        // 1. 认证（如果还未认证）
        if (!TokenStore.hasToken()) {
            val (authSuccess, authError) = authenticate()
            if (!authSuccess) {
                Log.e(TAG, "硬币器：认证失败，无法初始化: ${authError ?: "未知错误"}")
                return false
            }
        }
        
        // 2. 如果还没有映射，先进行探测
        if (coinAcceptorMapping == null) {
            val probeApiInstance = probeApi ?: api  // 如果没有提供探测 API，使用默认 API（但会慢一些）
            val (probeSuccess, probeError) = probeAndMapDevices(probeApiInstance)
            if (!probeSuccess) {
                Log.e(TAG, "硬币器：设备探测失败，无法初始化: ${probeError ?: "未知错误"}")
                return false
            }
        }
        
        // 3. 如果硬币器映射仍为空，说明探测时未找到硬币器
        if (coinAcceptorMapping == null) {
            Log.w(TAG, "硬币器：未找到映射，跳过硬币器初始化（这是正常的，如果只有纸币器）")
            return false
        }
        
        // 4. 使用映射打开连接（关键步骤，失败则返回 false）
        if (!openCoinAcceptorConnection()) {
            Log.e(TAG, "硬币器：打开连接失败")
            return false
        }
        
        val deviceID = _coinAcceptorDeviceID.value
        if (deviceID == null) {
            Log.e(TAG, "硬币器：未获取到 deviceID")
            return false
        }
        
        Log.d(TAG, "硬币器：连接成功，deviceID=$deviceID，开始后续初始化步骤...")
        
        // 5. 启动设备（非关键步骤，失败不影响连接状态）
        val startSuccess = startDevice(deviceID)
        if (!startSuccess) {
            Log.w(TAG, "硬币器：启动设备失败（但连接已建立，可继续使用）")
        } else {
            Log.d(TAG, "硬币器：启动设备成功")
        }
        
        // 6. 启用接收器（非关键步骤，失败不影响连接状态）
        val enableSuccess = enableAcceptor(deviceID)
        if (!enableSuccess) {
            Log.w(TAG, "硬币器：启用接收器失败（但连接已建立，可稍后重试）")
        } else {
            Log.d(TAG, "硬币器：启用接收器成功")
        }
        
        // 7. 设置自动接受（非关键步骤，失败不影响连接状态）
        val autoAcceptSuccess = setAutoAccept(deviceID, true)
        if (!autoAcceptSuccess) {
            Log.w(TAG, "硬币器：设置自动接受失败（但连接已建立，可稍后重试）")
        } else {
            Log.d(TAG, "硬币器：设置自动接受成功")
        }
        
        // 只要连接成功，就认为初始化成功（即使后续步骤失败）
        val allStepsSuccess = startSuccess && enableSuccess && autoAcceptSuccess
        if (allStepsSuccess) {
            Log.d(TAG, "硬币器：初始化完全成功（所有步骤都成功）")
        } else {
            Log.w(TAG, "硬币器：初始化部分成功（连接成功，但部分步骤失败，设备仍可使用）")
        }
        
        return true  // 连接成功即返回 true，后续步骤失败不影响
    }
}
