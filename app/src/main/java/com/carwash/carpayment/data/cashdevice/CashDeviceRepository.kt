package com.carwash.carpayment.data.cashdevice

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response

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
        Log.d(TAG, "createOpenConnectionRequest 调用: ComPort=$comPort, SspAddress=$sspAddress, deviceID=$deviceID, enableAcceptor=$enableAcceptor, setInhibits=${setInhibits?.size ?: 0}项, setRoutes=${setRoutes?.size ?: 0}项")
        val request = OpenConnectionRequest(
            ComPort = comPort,
            SspAddress = sspAddress,
            DeviceID = deviceID,  // 设备ID（从 GetConnectedUSBDevices 获取）
            LogFilePath = LOG_FILE_PATH,  // 日志文件路径（可选）
            SetInhibits = setInhibits,  // 面额禁用设置
            SetRoutes = setRoutes,  // 面额路由设置
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
            val request = createOpenConnectionRequest(
                comPort = port,
                sspAddress = sspAddress,
                deviceID = usbDeviceID,  // GetConnectedUSBDevices 不返回 DeviceID，这里为 null 是正常的
                enableAcceptor = true,  // 恢复默认值：true（与 Windows 工具一致）
                enableAutoAcceptEscrow = true,  // 恢复默认值：true（与 Windows 工具一致）
                enablePayout = true  // 设备连接成功后自动启用找零功能
            )
            
            // 序列化请求体（用于日志）
            val json = kotlinx.serialization.json.Json {
                encodeDefaults = true
                ignoreUnknownKeys = false
            }
            val requestBodyJson = json.encodeToString(
                kotlinx.serialization.serializer<OpenConnectionRequest>(), request
            )
            
            Log.d(TAG, "========== OpenConnection REQ ==========")
            Log.d(TAG, "Port=$port, SspAddress=$sspAddress, timeout=${timeoutMs}ms")
            Log.d(TAG, "requestBodyJson=$requestBodyJson")
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
            Log.d(TAG, "启用接收器: deviceID=$deviceID")
            val response = api.enableAcceptor(deviceID)
            val bodyText = cleanResponseBody(response.body()?.string())
            Log.d(TAG, "EnableAcceptor 响应: isSuccessful=${response.isSuccessful}, code=${response.code()}, body=$bodyText")
            if (response.isSuccessful) {
                Log.d(TAG, "接收器启用成功: deviceID=$deviceID, body=$bodyText")
                true
            } else {
                Log.e(TAG, "接收器启用失败: deviceID=$deviceID, code=${response.code()}, body=$bodyText")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "启用接收器异常: deviceID=$deviceID", e)
            false
        }
    }
    
    /**
     * 禁用接收器
     * 注意：服务器返回 text/plain 纯文本，response.isSuccessful 即认为成功
     */
    suspend fun disableAcceptor(deviceID: String): Boolean {
        return try {
            Log.d(TAG, "禁用接收器: deviceID=$deviceID")
            val response = api.disableAcceptor(deviceID)
            val bodyText = cleanResponseBody(response.body()?.string())
            Log.d(TAG, "DisableAcceptor 响应: isSuccessful=${response.isSuccessful}, code=${response.code()}, body=$bodyText")
            if (response.isSuccessful) {
                Log.d(TAG, "接收器禁用成功: deviceID=$deviceID, body=$bodyText")
                true
            } else {
                Log.e(TAG, "接收器禁用失败: deviceID=$deviceID, code=${response.code()}, body=$bodyText")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "禁用接收器异常: deviceID=$deviceID", e)
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
            false
        }
    }
    
    /**
     * 启动现金设备会话（认证 + 打开双设备连接）
     * @return Map<String, String> 设备映射：deviceName -> deviceID
     *         例如：{"SPECTRAL_PAYOUT-0" -> "device-id-1", "SMART_COIN_SYSTEM-1" -> "device-id-2"}
     */
    suspend fun startCashSession(): Map<String, String> {
        Log.d(TAG, "启动现金设备会话...")
        
        // 重置金额跟踪器（新的支付会话）
        amountTracker.reset()
        
        // 使用会话管理器启动会话
        val devices = try {
            sessionManager.startSession()
        } catch (e: Exception) {
            Log.e(TAG, "启动现金设备会话失败", e)
            throw e
        }
        
        // 更新设备ID映射（兼容现有代码）并设置基线金额
        devices["SPECTRAL_PAYOUT-0"]?.let { deviceID ->
            _billAcceptorDeviceID.value = deviceID
            Log.d(TAG, "纸币器 deviceID: $deviceID")
            // 在 OpenConnection 成功后，立即执行设备配置
            // 1. SetInhibits（设置"可接收面额"）
            try {
                val inhibitSuccess = setInhibits(deviceID, "SPECTRAL_PAYOUT-0")
                if (!inhibitSuccess) {
                    Log.w(TAG, "纸币器：SetInhibits 失败，但继续执行后续配置")
                }
            } catch (e: Exception) {
                Log.e(TAG, "纸币器：SetInhibits 异常", e)
            }
            
            // 2. SetRoutes（设置"找零面额/路由"）
            try {
                val routeSuccess = setRoutes(deviceID, "SPECTRAL_PAYOUT-0")
                if (!routeSuccess) {
                    Log.w(TAG, "纸币器：SetRoutes 失败，但继续执行后续配置")
                }
            } catch (e: Exception) {
                Log.e(TAG, "纸币器：SetRoutes 异常", e)
            }
            
            // 3. 设置基线库存（OpenConnection 成功后）
            try {
                val levelsResponse = readCurrentLevels(deviceID)
                val levels = levelsResponse.levels?.associate { it.value to it.stored } ?: emptyMap()
                amountTracker.setBaseline(deviceID, levels)
            } catch (e: Exception) {
                Log.w(TAG, "纸币器：设置基线库存失败，继续使用空库存", e)
                amountTracker.setBaseline(deviceID, emptyMap())  // 使用空库存
            }
        }
        devices["SMART_COIN_SYSTEM-1"]?.let { deviceID ->
            _coinAcceptorDeviceID.value = deviceID
            Log.d(TAG, "硬币器 deviceID: $deviceID")
            // 在 OpenConnection 成功后，立即执行设备配置
            // 1. SetInhibits（设置"可接收面额"）
            try {
                val inhibitSuccess = setInhibits(deviceID, "SMART_COIN_SYSTEM-1")
                if (!inhibitSuccess) {
                    Log.w(TAG, "硬币器：SetInhibits 失败，但继续执行后续配置")
                }
            } catch (e: Exception) {
                Log.e(TAG, "硬币器：SetInhibits 异常", e)
            }
            
            // 2. SetRoutes（设置"找零面额/路由"）
            try {
                val routeSuccess = setRoutes(deviceID, "SMART_COIN_SYSTEM-1")
                if (!routeSuccess) {
                    Log.w(TAG, "硬币器：SetRoutes 失败，但继续执行后续配置")
                }
            } catch (e: Exception) {
                Log.e(TAG, "硬币器：SetRoutes 异常", e)
            }
            
            // 3. 设置基线库存（OpenConnection 成功后）
            try {
                val levelsResponse = readCurrentLevels(deviceID)
                val levels = levelsResponse.levels?.associate { it.value to it.stored } ?: emptyMap()
                amountTracker.setBaseline(deviceID, levels)
            } catch (e: Exception) {
                Log.w(TAG, "硬币器：设置基线库存失败，继续使用空库存", e)
                amountTracker.setBaseline(deviceID, emptyMap())  // 使用空库存
            }
        }
        
        Log.d(TAG, "现金设备会话启动成功: 已注册设备数量=${devices.size}")
        return devices
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
        return try {
            val request = DispenseValueRequest(value = valueCents, countryCode = countryCode)
            
            // 构建完整 URL（用于日志）
            // 注意：baseUrl 应该从 CashDeviceClient 获取，但为了简化，这里使用默认值
            // 实际运行时，Retrofit 会自动使用配置的 baseUrl
            val baseUrl = "http://127.0.0.1:5000/api"
            val fullUrl = "$baseUrl/CashDevice/DispenseValue?deviceID=$deviceID"
            
            // 序列化请求体（用于日志）- 必须打印完整 JSON 用于验收
            // 使用默认配置，确保所有字段都被序列化
            val json = kotlinx.serialization.json.Json {
                encodeDefaults = true  // 确保默认值也被序列化
                ignoreUnknownKeys = false
            }
            val requestBodyJson = json.encodeToString(
                kotlinx.serialization.serializer<DispenseValueRequest>(), request
            )
            
            Log.d(TAG, "========== DispenseValue 请求开始 ==========")
            Log.d(TAG, "UI: 点击找零按钮, input=${valueCents / 100.0} EUR (${valueCents}分)")
            Log.d(TAG, "VM: requestDispense amountCents=$valueCents, currency=$countryCode")
            Log.d(TAG, "Repo: Dispense REQ /CashDevice/DispenseValue")
            Log.d(TAG, "deviceID: $deviceID")
            Log.d(TAG, "fullUrl: $fullUrl")
            Log.d(TAG, "RequestBody: $requestBodyJson")
            
            // 验证请求体包含 CountryCode（必须验证）
            if (!requestBodyJson.contains("CountryCode")) {
                Log.e(TAG, "ERROR: DispenseValue requestBody 缺少 CountryCode! requestBodyJson=$requestBodyJson")
                Log.e(TAG, "ERROR: request 对象内容: value=${request.value}, countryCode=${request.countryCode}")
            } else {
                Log.d(TAG, "✓ 验证通过: DispenseValue requestBody 包含 CountryCode")
            }
            
            Log.d(TAG, "--> POST /CashDevice/DispenseValue?deviceID=$deviceID")
            Log.d(TAG, "RequestBody: $requestBodyJson")
            
            val response = api.dispenseValue(deviceID, request)
            val httpCode = response.code()
            val bodyText = cleanResponseBody(response.body()?.string())
            
            Log.d(TAG, "<-- $httpCode ${if (response.isSuccessful) "OK" else "ERROR"}")
            Log.d(TAG, "rawResponseBody: $bodyText")
            
            if (response.isSuccessful) {
                Log.d(TAG, "找零成功: deviceID=$deviceID, valueCents=$valueCents (${valueCents / 100.0}元)")
                Log.d(TAG, "========== DispenseValue 请求成功 ==========")
                true
            } else {
                // 尝试解析错误原因（如果 body 包含 errorReason）
                val errorReason = try {
                    if (bodyText.isNotEmpty()) {
                        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        val errorObj = json.parseToJsonElement(bodyText)
                        if (errorObj is kotlinx.serialization.json.JsonObject) {
                            errorObj["errorReason"]?.let { 
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
                Log.e(TAG, "========== DispenseValue 请求失败 ==========")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "找零异常: deviceID=$deviceID", e)
            Log.e(TAG, "========== DispenseValue 请求异常 ==========")
            false
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
    suspend fun setInhibits(deviceID: String, deviceName: String): Boolean {
        return try {
            Log.d(TAG, "设置面额接收: deviceID=$deviceID, deviceName=$deviceName")
            
            // 获取可接收面额白名单
            val acceptableDenoms = DeviceDenominationConfig.getAcceptableDenominations(deviceName)
            if (acceptableDenoms.isEmpty()) {
                Log.w(TAG, "设备 $deviceName 没有可接收面额白名单，跳过 SetInhibits")
                return true  // 没有白名单，视为成功（不设置任何限制）
            }
            
            // 构建 SetInhibits 请求：白名单中的面额设置为 Inhibit=false（允许接收），其他面额设置为 Inhibit=true（禁止接收）
            // 注意：我们需要知道设备支持的所有面额，这里假设我们只配置白名单中的面额
            // 实际场景中，可能需要先获取设备支持的面额列表，然后对每个面额设置 Inhibit
            val inhibitItems = acceptableDenoms.map { denom: Int ->
                DenominationInhibitItem(
                    denomination = denom,
                    inhibit = false  // 白名单中的面额：允许接收
                )
            }
            
            val request = SetInhibitsRequest(denominations = inhibitItems)
            
            // 打印配置内容
            Log.d(TAG, "SetInhibits 配置: deviceID=$deviceID, 面额数=${inhibitItems.size}")
            inhibitItems.forEach { item: DenominationInhibitItem ->
                Log.d(TAG, "  面额 ${item.denomination} 分: Inhibit=${item.inhibit} (${if (item.inhibit) "禁止接收" else "允许接收"})")
            }
            
            val response = api.setInhibits(deviceID, request)
            val bodyText = cleanResponseBody(response.body()?.string())
            
            Log.d(TAG, "SetInhibits 响应: isSuccessful=${response.isSuccessful}, code=${response.code()}, body=$bodyText")
            
            if (response.isSuccessful) {
                Log.d(TAG, "SetInhibits 成功: deviceID=$deviceID, 配置了 ${inhibitItems.size} 个面额")
            } else {
                Log.e(TAG, "SetInhibits 失败: deviceID=$deviceID, code=${response.code()}, body=$bodyText")
            }
            
            response.isSuccessful
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
            
            Log.d(TAG, "SetRoutes 响应: isSuccessful=${response.isSuccessful}, code=${response.code()}, body=$bodyText")
            
            if (response.isSuccessful) {
                Log.d(TAG, "SetRoutes 成功: deviceID=$deviceID, 配置了 ${routeItems.size} 个面额")
            } else {
                Log.e(TAG, "SetRoutes 失败: deviceID=$deviceID, code=${response.code()}, body=$bodyText")
            }
            
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "设置面额路由异常: deviceID=$deviceID", e)
            false
        }
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
     * 从货币分配动态生成并应用 SetInhibits（设置可接收面额）
     * @param deviceID 设备ID
     * @param deviceName 设备名称（用于日志）
     * @return Boolean 是否成功
     */
    suspend fun applyInhibitsFromAssignments(deviceID: String, deviceName: String): Boolean {
        return try {
            Log.d(TAG, "从货币分配应用 SetInhibits: deviceID=$deviceID, deviceName=$deviceName")
            
            // 获取货币分配
            val assignments = fetchCurrencyAssignments(deviceID)
            
            if (assignments.isEmpty()) {
                Log.w(TAG, "设备 $deviceName 没有货币分配数据，跳过 SetInhibits")
                return true  // 没有数据，视为成功（不设置任何限制）
            }
            
            // 构建 SetInhibits 请求：允许接收所有面额（Inhibit=false）
            // 注意：Denomination 字符串格式为 "{value} {countryCode}"，例如 "500 EUR"
            val inhibitItems = assignments.map { assignment ->
                DenominationInhibitItem(
                    denomination = assignment.getDenominationString().hashCode(),  // 临时方案：使用字符串的 hashCode
                    inhibit = false  // 允许接收所有面额
                )
            }
            
            // 修正：Denomination 应该是字符串，但 DenominationInhibitItem 的 denomination 是 Int
            // 需要检查厂商文档，这里先使用 value 作为 denomination
            val inhibitItemsCorrected = assignments.map { assignment ->
                DenominationInhibitItem(
                    denomination = assignment.value,  // 使用 value（分）作为 denomination
                    inhibit = assignment.isInhibited  // 保持当前状态，或设置为 false 允许接收
                )
            }
            
            val request = SetInhibitsRequest(denominations = inhibitItemsCorrected)
            
            // 打印配置内容
            Log.d(TAG, "SetInhibits 配置（从货币分配生成）: deviceID=$deviceID, 面额数=${inhibitItemsCorrected.size}")
            inhibitItemsCorrected.take(5).forEach { item ->
                Log.d(TAG, "  面额 ${item.denomination} 分: Inhibit=${item.inhibit} (${if (item.inhibit) "禁止接收" else "允许接收"})")
            }
            
            val response = api.setInhibits(deviceID, request)
            val bodyText = cleanResponseBody(response.body()?.string())
            
            Log.d(TAG, "SetInhibits 响应: isSuccessful=${response.isSuccessful}, code=${response.code()}, body=$bodyText")
            
            if (response.isSuccessful) {
                Log.d(TAG, "SetInhibits 成功: deviceID=$deviceID, 配置了 ${inhibitItemsCorrected.size} 个面额")
            } else {
                Log.e(TAG, "SetInhibits 失败: deviceID=$deviceID, code=${response.code()}, body=$bodyText")
            }
            
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "从货币分配应用 SetInhibits 异常: deviceID=$deviceID", e)
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
    suspend fun setDenominationRoute(deviceID: String, valueCents: Int, currency: String = "EUR", route: Int, probeMode: Boolean = false, useRawJson: Boolean = false): Boolean {
        return try {
            Log.d(TAG, "========== SetDenominationRoute REQ ==========")
            Log.d(TAG, "deviceID: $deviceID")
            Log.d(TAG, "value: $valueCents")
            Log.d(TAG, "currency: $currency")
            Log.d(TAG, "route: $route (${if (route == 1) "RECYCLER可找零" else if (route == 0) "CASHBOX不可找零" else "其他($route)"})")
            Log.d(TAG, "probeMode: $probeMode")
            
            // ========== 验证 1: Route 值只能为 0 或 1 ==========
            if (route != 0 && route != 1) {
                Log.e(TAG, "SetDenominationRoute 验证失败: Route 值只能为 0（CASHBOX）或 1（RECYCLER），当前值=$route")
                return false
            }
            
            // ========== 验证 2: 仅 Spectral Payout 设备支持 Route 0/1 ==========
            val isSpectralPayout = deviceID.startsWith("SPECTRAL_PAYOUT", ignoreCase = true) || 
                                   deviceID.contains("SPECTRAL", ignoreCase = true) ||
                                   deviceID.contains("PAYOUT", ignoreCase = true)
            if (!isSpectralPayout) {
                Log.e(TAG, "SetDenominationRoute 验证失败: 仅 Spectral Payout 设备支持 Route 0/1，当前设备ID=$deviceID")
                return false
            }
            Log.d(TAG, "✓ 设备型号验证通过: Spectral Payout (deviceID=$deviceID)")
            
            // ========== 验证 3: 货币代码必须为 EUR ==========
            val finalCurrency = currency.ifEmpty { "EUR" }.uppercase()
            if (finalCurrency != "EUR") {
                Log.e(TAG, "SetDenominationRoute 验证失败: 目前仅支持 EUR 货币，当前值=$currency")
                return false
            }
            Log.d(TAG, "✓ 货币代码验证通过: $finalCurrency")
            
            // ========== 验证 4: 面额必须在设备允许的范围内 ==========
            val deviceName = if (isSpectralPayout) "SPECTRAL_PAYOUT-0" else "SMART_COIN_SYSTEM-1"
            val acceptableDenoms = DeviceDenominationConfig.getAcceptableDenominations(deviceName)
            if (!acceptableDenoms.contains(valueCents)) {
                Log.e(TAG, "SetDenominationRoute 验证失败: 面额 $valueCents 不在设备允许的范围内")
                Log.e(TAG, "  设备 $deviceName 允许的面额: $acceptableDenoms")
                return false
            }
            Log.d(TAG, "✓ 面额验证通过: $valueCents 在设备允许的范围内")
            
            // ========== 构建 Route 尝试列表（只尝试 0 和 1）==========
            val routesToTry = if (probeMode) {
                // 探测模式：如果失败，尝试另一个 route 值（1->0 或 0->1）
                listOf(route, if (route == 1) 0 else 1).distinct()
            } else {
                listOf(route)
            }
            Log.d(TAG, "Route 尝试列表: $routesToTry (仅支持 0 和 1)")
            
            var lastError: String? = null
            for (currentRoute in routesToTry) {
                val (response, requestBodyJsonForLog) = if (useRawJson) {
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
                        Pair(resp, rawJsonString)
                    } catch (e: Exception) {
                        val errorMsg = "原始 JSON 直发模式异常: ${e.javaClass.simpleName}, message=${e.message}"
                        Log.e(TAG, errorMsg, e)
                        lastError = "route=$currentRoute, 原始 JSON 直发模式异常: ${e.message}"
                        if (!probeMode) {
                            return false
                        }
                        continue
                    }
                } else {
                    // ========== DTO 序列化模式（正常模式 - 扁平结构）==========
                    val request = SetDenominationRouteRequestFlat(
                        value = valueCents,
                        countryCode = finalCurrency,
                        route = currentRoute
                    )
                    
                    // 序列化请求体（用于日志）- 必须打印完整 JSON 用于验收
                    // 注意：encodeDefaults = false 确保不输出默认值
                    val json = kotlinx.serialization.json.Json {
                        encodeDefaults = false
                        ignoreUnknownKeys = false
                    }
                    val requestBodyJson = json.encodeToString(
                        kotlinx.serialization.serializer<SetDenominationRouteRequestFlat>(), request
                    )
                    Log.d(TAG, "========== SetDenominationRoute 请求体（序列化后 - 扁平结构）==========")
                    Log.d(TAG, "requestBodyJson=$requestBodyJson (route=$currentRoute)")
                    
                    // 验证请求体包含三个顶层字段（必须验证）
                    val hasValue = requestBodyJson.contains("\"Value\"")
                    val hasCountryCode = requestBodyJson.contains("\"CountryCode\"")
                    val hasRoute = requestBodyJson.contains("\"Route\"")
                    if (!hasValue || !hasCountryCode || !hasRoute) {
                        Log.e(TAG, "ERROR: requestBody 缺少必要字段! hasValue=$hasValue, hasCountryCode=$hasCountryCode, hasRoute=$hasRoute")
                        Log.e(TAG, "requestBodyJson=$requestBodyJson")
                    } else {
                        Log.d(TAG, "✓ 验证通过: requestBody 包含三个顶层字段（Value、CountryCode、Route）")
                    }
                    
                    // 验证请求体不包含嵌套结构（ValueCountryCode 嵌套层）
                    if (requestBodyJson.contains("ValueCountryCode")) {
                        Log.e(TAG, "ERROR: requestBody 包含嵌套结构（ValueCountryCode），应使用扁平结构!")
                        Log.e(TAG, "requestBodyJson=$requestBodyJson")
                    } else {
                        Log.d(TAG, "✓ 验证通过: requestBody 使用扁平结构（不包含 ValueCountryCode 嵌套层）")
                    }
                    
                    // 验证请求体不包含多余字段（这些字段会导致 INVALID_INPUT）
                    if (requestBodyJson.contains("Fraud_Attempt_Value") || requestBodyJson.contains("Calibration_Failed_Value")) {
                        Log.e(TAG, "ERROR: requestBody 包含多余字段（Fraud_Attempt_Value 或 Calibration_Failed_Value），这会导致 INVALID_INPUT!")
                        Log.e(TAG, "requestBodyJson=$requestBodyJson")
                    } else {
                        Log.d(TAG, "✓ 验证通过: requestBody 不包含多余字段")
                    }
                    
                    // ========== 调用 SetDenominationRoute API ==========
                    // 注意：OkHttp Interceptor 会自动记录最终请求头和请求体（HttpLoggingInterceptor.Level.BODY）
                    // 这些日志会显示 OkHttp 真正发送的内容，用于验证字段名是否正确
                    Log.d(TAG, "准备调用 SetDenominationRoute API: deviceID=$deviceID")
                    Log.d(TAG, "Query 参数: deviceID=$deviceID (注意大小写：小写 d，大写 ID)")
                    Log.d(TAG, "Content-Type: application/json (已通过 @Headers 强制指定)")
                    Log.d(TAG, "Authorization: Bearer <token> (由 Interceptor 自动添加，注意 Bearer 后面有空格)")
                    
                    // 打印最终请求 URL（用于验证路径是否正确）
                    // 注意：baseUrl 默认值为 "http://127.0.0.1:5000/api/"，已包含 /api/
                    // Retrofit 接口路径为 "CashDevice/SetDenominationRoute"
                    // 最终 URL 应为：http://127.0.0.1:5000/api/CashDevice/SetDenominationRoute?deviceID=...
                    // 这与 Postman URL 一致：{{baseUrl}}/CashDevice/SetDenominationRoute?deviceID=...
                    // 最终请求 URL 将在 OkHttp Interceptor 日志中显示（请查看 --> POST ... 日志）
                    Log.d(TAG, "最终请求 URL 格式: http://<host>:<port>/api/CashDevice/SetDenominationRoute?deviceID=$deviceID")
                    
                    try {
                        val resp = api.setDenominationRoute(deviceID, request)
                        Pair(resp, requestBodyJson)
                    } catch (e: java.net.SocketTimeoutException) {
                        val errorMsg = "网络超时: ${e.message}"
                        Log.e(TAG, "SetDenominationRoute 网络超时: deviceID=$deviceID, value=$valueCents, route=$currentRoute, $errorMsg")
                        Log.d(TAG, "⚠ 注意：网络超时，但设备保持连接状态，可以重试")
                        lastError = "route=$currentRoute, 网络超时: ${e.message}"
                        if (!probeMode) {
                            return false
                        }
                        continue  // 探测模式：继续尝试下一个 route 值
                    } catch (e: retrofit2.HttpException) {
                        val errorMsg = "HTTP 错误: code=${e.code()}, message=${e.message()}"
                        Log.e(TAG, "SetDenominationRoute HTTP 错误: deviceID=$deviceID, value=$valueCents, route=$currentRoute, $errorMsg")
                        Log.d(TAG, "⚠ 注意：HTTP 错误，但设备保持连接状态，可以重试")
                        lastError = "route=$currentRoute, HTTP ${e.code()}: ${e.message()}"
                        if (!probeMode) {
                            return false
                        }
                        continue  // 探测模式：继续尝试下一个 route 值
                    } catch (e: Exception) {
                        val errorMsg = "异常: ${e.javaClass.simpleName}, message=${e.message}"
                        Log.e(TAG, "SetDenominationRoute 异常: deviceID=$deviceID, value=$valueCents, route=$currentRoute, $errorMsg", e)
                        Log.d(TAG, "⚠ 注意：发生异常，但设备保持连接状态，可以重试")
                        lastError = "route=$currentRoute, 异常: ${e.message}"
                        if (!probeMode) {
                            return false
                        }
                        continue  // 探测模式：继续尝试下一个 route 值
                    }
                }
                
                // ========== 处理 API 响应 ==========
                // 打印最终发送的 JSON（用于验证与 Postman 一致）
                Log.d(TAG, "========== SetDenominationRoute 最终发送的 JSON ==========")
                Log.d(TAG, "最终请求体（扁平三字段）: $requestBodyJsonForLog")
                Log.d(TAG, "验证：应包含 {\"Value\":...,\"CountryCode\":...,\"Route\":...} 格式")
                Log.d(TAG, "最终请求 URL 格式: http://<host>:<port>/api/CashDevice/SetDenominationRoute?deviceID=$deviceID")
                Log.d(TAG, "注意：完整 URL 将在 OkHttp Interceptor 日志中显示（请查看 --> POST ... 日志）")
                
                // 使用统一日志函数打印响应（包含 errorBody，只读一次）
                val errorBodyText = logApiResponse(TAG, "SetDenominationRoute", requestBodyJsonForLog, response)
                
                if (response.isSuccessful) {
                    // ========== 成功处理 ==========
                    val responseBody = response.body()?.string() ?: ""
                    Log.d(TAG, "========== SetDenominationRoute 成功 ==========")
                    Log.d(TAG, "deviceID: $deviceID")
                    Log.d(TAG, "value: $valueCents")
                    Log.d(TAG, "currency: $finalCurrency")
                    Log.d(TAG, "route: $currentRoute (${if (currentRoute == 1) "RECYCLER可找零" else "CASHBOX不可找零"})")
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
                            val expectedRoute = if (currentRoute == 1) "PAYOUT" else "CASHBOX"
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
                } else {
                    // ========== 失败处理 ==========
                    val httpCode = response.code()
                    val contentType = response.errorBody()?.contentType()?.toString() ?: "unknown"
                    lastError = "route=$currentRoute, http=$httpCode, Content-Type=$contentType, errorBody=$errorBodyText"
                    
                    Log.e(TAG, "========== SetDenominationRoute 失败 ==========")
                    Log.e(TAG, "deviceID: $deviceID")
                    Log.e(TAG, "value: $valueCents")
                    Log.e(TAG, "currency: $finalCurrency")
                    Log.e(TAG, "route: $currentRoute")
                    Log.e(TAG, "HTTP Code: $httpCode")
                    Log.e(TAG, "Content-Type: $contentType")
                    Log.e(TAG, "Error Body: $errorBodyText")
                    Log.d(TAG, "⚠ 注意：API 调用失败，但设备保持连接状态，可以重试或继续使用其他功能")
                    
                    // 如果不是探测模式，直接返回失败（但不触发断开连接）
                    if (!probeMode) {
                        return false
                    }
                    // 探测模式：继续尝试下一个 route 值
                }
            }
            
            // ========== 所有 route 值都失败 ==========
            Log.e(TAG, "========== SetDenominationRoute 所有 route 值都失败 ==========")
            Log.e(TAG, "deviceID: $deviceID")
            Log.e(TAG, "value: $valueCents")
            Log.e(TAG, "currency: $finalCurrency")
            Log.e(TAG, "尝试的 route 值: $routesToTry")
            Log.e(TAG, "最后错误: $lastError")
            Log.d(TAG, "⚠ 注意：所有 route 值都失败，但设备保持连接状态，可以重试或继续使用其他功能")
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
    suspend fun setDenominationInhibit(deviceID: String, valueCents: Int, currency: String = "EUR", inhibit: Boolean): Boolean {
        return try {
            Log.d(TAG, "========== SetDenominationInhibits REQ ==========")
            Log.d(TAG, "deviceID: $deviceID")
            Log.d(TAG, "value: $valueCents")
            Log.d(TAG, "currency: $currency")
            Log.d(TAG, "inhibit: $inhibit (${if (inhibit) "禁止接收" else "允许接收"})")
            
            // 确保 currency 不为空
            val finalCurrency = currency.ifEmpty { "EUR" }
            
            // 构建 ValueCountryCodes 字符串数组，格式："500 EUR"
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
                
                // 步骤 3: EnableAcceptor
                Log.d(TAG, "步骤 3: 启用接收器...")
                val enableSuccess = enableAcceptor(deviceID)
                if (!enableSuccess) {
                    Log.w(TAG, "启用接收器失败，但路由配置已生效")
                }
                
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
     * 轮询计数器（获取设备已收金额）- 已废弃，请使用 pollStoredValue
     * @deprecated 请使用 pollStoredValue（基于 GetStoredValue 和基线机制）
     */
    @Deprecated("请使用 pollStoredValue（基于 GetStoredValue 和基线机制）", ReplaceWith("pollStoredValue(deviceID)"))
    suspend fun pollCounters(deviceID: String): CountersResponse {
        return try {
            Log.d(TAG, "轮询计数器: deviceID=$deviceID (已废弃，请使用 pollStoredValue)")
            val counters = api.getCounters(deviceID)
            Log.d(TAG, "GetCounters 响应: deviceID=${counters.deviceID}, stackedTotalCents=${counters.stackedTotalCents} (${counters.totalAmount}元)")
            counters
        } catch (e: Exception) {
            Log.e(TAG, "轮询计数器异常: deviceID=$deviceID", e)
            // 返回空的计数器响应（容错处理）
            CountersResponse(deviceID = deviceID, error = e.message)
        }
    }
    
    /**
     * 获取金额跟踪器（用于获取总金额）
     */
    fun getAmountTracker(): CashAmountTracker {
        return amountTracker
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
