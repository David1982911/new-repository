package com.carwash.carpayment.data.cashdevice.device

import android.util.Log
import com.carwash.carpayment.data.cashdevice.CashDeviceApi
import com.carwash.carpayment.data.cashdevice.LevelEntry
import com.carwash.carpayment.data.cashdevice.OpenConnectionRequest
import com.carwash.carpayment.data.cashdevice.OpenConnectionResponse
import okhttp3.ResponseBody
import retrofit2.HttpException

/**
 * ⚠️ V3.2 新增：纸币器设备驱动实现
 * 职责：只做收发，不重试、不重连、不缓存业务状态
 * 所有方法必须抛出异常，由状态管理层捕获并决策
 */
class SpectralPayoutDevice(
    private val api: CashDeviceApi,
    private val deviceID: String? = null  // 设备ID（从 OpenConnection 响应中获取）
) : CashDevice {
    
    companion object {
        private const val TAG = "SpectralPayoutDevice"
        private const val DEFAULT_PORT = 0
        private const val DEFAULT_SSP_ADDRESS = 0
    }
    
    @Volatile
    private var _isOnline: Boolean = false
    
    @Volatile
    private var _currentDeviceID: String? = deviceID
    
    // ⚠️ V3.2 新增：缓存最后一次 GetDeviceStatus 结果
    @Volatile
    private var _lastDeviceStatus: DeviceStatus? = null
    
    override val isOnline: Boolean
        get() = _isOnline
    
    /**
     * ⚠️ V3.2 新增：获取最后一次缓存的设备状态
     * 用于状态管理层判断设备健康状态
     */
    fun getLastDeviceStatus(): DeviceStatus? = _lastDeviceStatus
    
    /**
     * ⚠️ V3.2 新增：获取设备ID（用于兼容旧代码）
     */
    override fun getDeviceID(): String? = _currentDeviceID
    
    override suspend fun openConnection(port: Int, sspAddress: Int): Boolean {
        Log.d(TAG, "openConnection: port=$port, sspAddress=$sspAddress")
        
        try {
            val request = OpenConnectionRequest(
                ComPort = port,
                SspAddress = sspAddress,
                DeviceID = _currentDeviceID,
                EnableAcceptor = false,  // 连接时不启用接收器
                EnableAutoAcceptEscrow = false,
                EnablePayout = false
            )
            
            val response: OpenConnectionResponse = api.openConnection(request)
            
            if (response.error != null) {
                Log.e(TAG, "openConnection 失败: error=${response.error}")
                _isOnline = false
                throw Exception("OpenConnection 失败: ${response.error}")
            }
            
            // 保存设备ID
            _currentDeviceID = response.deviceID
            _isOnline = response.IsOpen == true && response.DeviceError.isNullOrEmpty()
            
            Log.d(TAG, "openConnection 成功: deviceID=${_currentDeviceID}, isOnline=$_isOnline")
            return _isOnline
        } catch (e: HttpException) {
            Log.e(TAG, "openConnection HTTP 异常: code=${e.code()}, message=${e.message()}")
            _isOnline = false
            throw Exception("OpenConnection HTTP 异常: ${e.code()}", e)
        } catch (e: Exception) {
            Log.e(TAG, "openConnection 异常", e)
            _isOnline = false
            throw e
        }
    }
    
    override suspend fun getDeviceStatus(): DeviceStatus {
        val deviceID = _currentDeviceID ?: throw IllegalStateException("设备未连接，deviceID=null")
        
        try {
            val statusList = api.getDeviceStatus(deviceID)
            
            if (statusList.isEmpty()) {
                _isOnline = false
                return DeviceStatus(online = false, state = "UNKNOWN", error = "设备状态数组为空")
            }
            
            val latestStatus = statusList.last()
            val state = latestStatus.actualState ?: "UNKNOWN"
            val isOnline = state.uppercase() in listOf("IDLE", "STARTED", "CONNECTED", "DISABLED")
            
            // ⚠️ V3.2 关键修复：缓存最后一次 GetDeviceStatus 结果
            val deviceStatus = DeviceStatus(
                online = isOnline,
                state = state,
                error = null
            )
            _isOnline = isOnline
            _lastDeviceStatus = deviceStatus
            
            return deviceStatus
        } catch (e: HttpException) {
            Log.e(TAG, "getDeviceStatus HTTP 异常: code=${e.code()}, message=${e.message()}")
            _isOnline = false
            throw Exception("GetDeviceStatus HTTP 异常: ${e.code()}", e)
        } catch (e: Exception) {
            Log.e(TAG, "getDeviceStatus 异常", e)
            _isOnline = false
            throw e
        }
    }
    
    override suspend fun enableAcceptor(enable: Boolean): Boolean {
        val deviceID = _currentDeviceID ?: throw IllegalStateException("设备未连接，deviceID=null")
        
        try {
            val response: retrofit2.Response<ResponseBody> = if (enable) {
                api.enableAcceptor(deviceID)
            } else {
                api.disableAcceptor(deviceID)
            }
            
            val success = response.isSuccessful
            Log.d(TAG, "enableAcceptor: deviceID=$deviceID, enable=$enable, success=$success")
            
            if (!success) {
                throw Exception("EnableAcceptor 失败: code=${response.code()}")
            }
            
            return success
        } catch (e: HttpException) {
            Log.e(TAG, "enableAcceptor HTTP 异常: code=${e.code()}, message=${e.message()}")
            throw Exception("EnableAcceptor HTTP 异常: ${e.code()}", e)
        } catch (e: Exception) {
            Log.e(TAG, "enableAcceptor 异常", e)
            throw e
        }
    }
    
    override suspend fun getAllLevels(): List<Denomination> {
        val deviceID = _currentDeviceID ?: throw IllegalStateException("设备未连接，deviceID=null")
        
        try {
            val levelsResponse = api.getAllLevels(deviceID)
            val levels = levelsResponse.levels ?: emptyList()
            
            // 转换为 Denomination 列表
            return levels.map { level ->
                Denomination(
                    value = level.value,
                    stored = level.stored,
                    countryCode = level.countryCode ?: "EUR"
                )
            }
        } catch (e: HttpException) {
            Log.e(TAG, "getAllLevels HTTP 异常: code=${e.code()}, message=${e.message()}")
            // ⚠️ V3.2 要求：不捕获 SocketTimeoutException，直接抛出
            throw Exception("GetAllLevels HTTP 异常: ${e.code()}", e)
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "getAllLevels 超时异常", e)
            // ⚠️ V3.2 要求：不捕获 SocketTimeoutException，直接抛出
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "getAllLevels 异常", e)
            throw e
        }
    }
    
    override suspend fun collect(): Boolean {
        val deviceID = _currentDeviceID ?: throw IllegalStateException("设备未连接，deviceID=null")
        
        try {
            // 启用接收器
            val enableSuccess = enableAcceptor(true)
            if (!enableSuccess) {
                throw Exception("启用接收器失败")
            }
            
            // 设置自动接受（API 直接接受 Boolean）
            val autoAcceptResponse = api.setAutoAccept(deviceID, true)
            
            val success = autoAcceptResponse.isSuccessful
            Log.d(TAG, "collect: deviceID=$deviceID, success=$success")
            
            if (!success) {
                throw Exception("设置自动接受失败: code=${autoAcceptResponse.code()}")
            }
            
            return success
        } catch (e: Exception) {
            Log.e(TAG, "collect 异常", e)
            throw e
        }
    }
    
    override suspend fun dispenseChange(amountCents: Int): DispenseResult {
        val deviceID = _currentDeviceID ?: throw IllegalStateException("设备未连接，deviceID=null")
        
        try {
            val request = com.carwash.carpayment.data.cashdevice.DispenseValueRequest(
                value = amountCents,
                countryCode = "EUR"
            )
            
            val response = api.dispenseValue(deviceID, request)
            val bodyText = response.body()?.string() ?: ""
            
            if (response.isSuccessful) {
                Log.d(TAG, "dispenseChange 成功: deviceID=$deviceID, amountCents=$amountCents")
                return DispenseResult(
                    success = true,
                    amountDispensed = amountCents,
                    remaining = 0,
                    error = null
                )
            } else {
                val error = "DispenseValue 失败: code=${response.code()}, body=$bodyText"
                Log.e(TAG, error)
                return DispenseResult(
                    success = false,
                    amountDispensed = 0,
                    remaining = amountCents,
                    error = error
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "dispenseChange 异常", e)
            throw e
        }
    }
    
    override suspend fun getChangeStatus(): ChangeStatus {
        // ⚠️ TODO: 实现获取找零状态（如果 API 支持）
        return ChangeStatus(
            isProcessing = false,
            amountRemaining = 0,
            error = null
        )
    }
}
