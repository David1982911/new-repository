package com.carwash.carpayment.data.cashdevice

import android.util.Log

/**
 * 现金设备会话管理器
 * 负责认证、获取已连接设备、打开连接（双设备：SPECTRAL_PAYOUT-0 纸币，SMART_COIN_SYSTEM-1 硬币）
 */
class CashSessionManager(
    private val api: CashDeviceApi
) {
    
    companion object {
        private const val TAG = "CashSessionManager"
        private const val DEFAULT_USERNAME = "admin"
        private const val DEFAULT_PASSWORD = "password"
    }
    
    /**
     * 预初始化会话（认证 + 打开双设备连接，但不启用收款）
     * App 启动时调用，只完成连接，不启用接收器
     * @return Map<String, String> 设备映射：deviceName -> deviceID
     */
    suspend fun prewarmSession(
        username: String = DEFAULT_USERNAME,
        password: String = DEFAULT_PASSWORD
    ): Map<String, String> {
        return openConnections(username, password, enableAcceptor = false)
    }
    
    /**
     * 启动会话（认证 + 打开双设备连接，但不启用收款）
     * 注意：启用收款应在 beginCashSession 中通过 EnableAcceptor API 单独调用
     * @return Map<String, String> 设备映射：deviceName -> deviceID
     *         例如：{"SPECTRAL_PAYOUT-0" -> "device-id-1", "SMART_COIN_SYSTEM-1" -> "device-id-2"}
     */
    suspend fun startSession(
        username: String = DEFAULT_USERNAME,
        password: String = DEFAULT_PASSWORD
    ): Map<String, String> {
        return openConnections(username, password, enableAcceptor = false)
    }
    
    /**
     * 打开连接（内部方法，支持参数化启用/禁用接收器）
     */
    private suspend fun openConnections(
        username: String,
        password: String,
        enableAcceptor: Boolean
    ): Map<String, String> {
        Log.d(TAG, "开始启动现金设备会话...")
        
        // 1. 认证
        Log.d(TAG, "步骤 1/3: 认证...")
        val authResponse = try {
            api.authenticate(AuthenticateRequest(username, password))
        } catch (e: Exception) {
            Log.e(TAG, "认证失败", e)
            throw Exception("认证失败: ${e.message}", e)
        }
        
        if (authResponse.token == null) {
            val errorMsg = authResponse.error ?: "认证失败：未返回 Token"
            Log.e(TAG, errorMsg)
            throw Exception(errorMsg)
        }
        Log.d(TAG, "认证成功: token=${authResponse.token.take(20)}...")
        
        // 2. 获取已连接的 USB 设备
        Log.d(TAG, "步骤 2/3: 获取已连接 USB 设备...")
        val usbDevices = try {
            val devices = api.getConnectedUSBDevices()
            Log.d(TAG, "已连接 USB 设备数量: ${devices.size}")
            devices.forEach { device ->
                Log.d(TAG, "  - Port=${device.actualPort}, DeviceName=${device.DeviceName}, DeviceID=${device.actualDeviceID}")
            }
            devices
        } catch (e: Exception) {
            Log.e(TAG, "获取已连接 USB 设备失败", e)
            throw Exception("获取已连接 USB 设备失败: ${e.message}", e)
        }
        
        if (usbDevices.isEmpty()) {
            Log.w(TAG, "未找到已连接的 USB 设备")
            throw Exception("未找到已连接的 USB 设备，请检查设备连接")
        }
        
        // 3. 打开连接（自动注册两个设备：Port=0 纸币，Port=1 硬币）
        Log.d(TAG, "步骤 3/3: 打开设备连接...")
        val devices = mutableMapOf<String, String>()
        
        for (usbDevice in usbDevices) {
            val port = usbDevice.actualPort
            if (port == null) {
                Log.w(TAG, "跳过无效设备: DeviceName=${usbDevice.DeviceName}, Port=null")
                continue
            }
            
            // Port=0 -> 纸币器 (SPECTRAL_PAYOUT-0, SspAddress=0)
            // Port=1 -> 硬币器 (SMART_COIN_SYSTEM-1, SspAddress=16)
            val sspAddress = when (port) {
                0 -> 0   // 纸币器
                1 -> 16  // 硬币器
                else -> {
                    Log.w(TAG, "跳过未知 Port: $port")
                    continue
                }
            }
            
            val deviceName = when (port) {
                0 -> "SPECTRAL_PAYOUT-0"
                1 -> "SMART_COIN_SYSTEM-1"
                else -> "UNKNOWN-$port"
            }
            
            try {
                Log.d(TAG, "打开连接: Port=$port, SspAddress=$sspAddress, DeviceName=$deviceName")
                val request = OpenConnectionRequest(
                    ComPort = port,
                    SspAddress = sspAddress,
                    DeviceID = usbDevice.actualDeviceID,
                    EnableAcceptor = enableAcceptor,
                    EnableAutoAcceptEscrow = enableAcceptor,  // 只有启用接收器时才自动接受
                    EnablePayout = false
                )
                
                val response = api.openConnection(request)
                
                val deviceID = response.deviceID
                if (deviceID == null) {
                    val errorMsg = response.error ?: "打开连接失败：未返回 DeviceID"
                    Log.e(TAG, "$deviceName 打开连接失败: $errorMsg")
                    continue
                }
                
                devices[deviceName] = deviceID
                Log.d(TAG, "$deviceName 打开连接成功: DeviceID=$deviceID")
            } catch (e: Exception) {
                Log.e(TAG, "$deviceName 打开连接异常", e)
                // 继续处理下一个设备，不中断整个会话
                continue
            }
        }
        
        if (devices.isEmpty()) {
            Log.e(TAG, "所有设备打开连接失败")
            throw Exception("所有设备打开连接失败，请检查设备连接")
        }
        
        Log.d(TAG, "现金设备会话启动成功: 已注册设备数量=${devices.size}")
        devices.forEach { (name, id) ->
            Log.d(TAG, "  - $name -> $id")
        }
        
        return devices
    }
}
