package com.carwash.carpayment.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carwash.carpayment.data.cashdevice.CashDeviceClient
import com.carwash.carpayment.data.cashdevice.CashDeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 现金设备 ViewModel
 * 管理纸币器和硬币器的连接状态和收款
 */
class CashDeviceViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "CashDeviceViewModel"
    }
    
    private val api = CashDeviceClient.create(context = getApplication())
    private val probeApi = CashDeviceClient.createWithTimeout(context = getApplication(), timeoutSeconds = 3L)
    private val repository = CashDeviceRepository(api)
    
    // 纸币器状态
    private val _billAcceptorConnected = MutableStateFlow(false)
    val billAcceptorConnected: StateFlow<Boolean> = _billAcceptorConnected.asStateFlow()
    
    private val _billAcceptorDeviceID = MutableStateFlow<String?>(null)
    val billAcceptorDeviceID: StateFlow<String?> = _billAcceptorDeviceID.asStateFlow()
    
    // 硬币器状态
    private val _coinAcceptorConnected = MutableStateFlow(false)
    val coinAcceptorConnected: StateFlow<Boolean> = _coinAcceptorConnected.asStateFlow()
    
    private val _coinAcceptorDeviceID = MutableStateFlow<String?>(null)
    val coinAcceptorDeviceID: StateFlow<String?> = _coinAcceptorDeviceID.asStateFlow()
    
    // 收款金额（累加）
    private val _totalAmount = MutableStateFlow(0.0)
    val totalAmount: StateFlow<Double> = _totalAmount.asStateFlow()
    
    // 错误信息
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    init {
        // 监听 deviceID 变化
        viewModelScope.launch {
            repository.billAcceptorDeviceID.collect { deviceID ->
                _billAcceptorDeviceID.value = deviceID
                _billAcceptorConnected.value = deviceID != null
                Log.d(TAG, "纸币器 deviceID 更新: $deviceID")
            }
        }
        
        viewModelScope.launch {
            repository.coinAcceptorDeviceID.collect { deviceID ->
                _coinAcceptorDeviceID.value = deviceID
                _coinAcceptorConnected.value = deviceID != null
                Log.d(TAG, "硬币器 deviceID 更新: $deviceID")
            }
        }
    }
    
    /**
     * 初始化纸币器
     */
    fun initializeBillAcceptor(comPort: Int = 0) {
        viewModelScope.launch {
            try {
                _errorMessage.value = null
                Log.d(TAG, "开始初始化纸币器")
                val success = repository.initializeBillAcceptor(probeApi)
                if (!success) {
                    _errorMessage.value = "纸币器初始化失败"
                }
            } catch (e: Exception) {
                Log.e(TAG, "初始化纸币器异常", e)
                _errorMessage.value = "纸币器初始化异常: ${e.message}"
            }
        }
    }
    
    /**
     * 初始化硬币器
     */
    fun initializeCoinAcceptor(comPort: Int = 0) {
        viewModelScope.launch {
            try {
                _errorMessage.value = null
                Log.d(TAG, "开始初始化硬币器")
                val success = repository.initializeCoinAcceptor(probeApi)
                if (!success) {
                    _errorMessage.value = "硬币器初始化失败"
                }
            } catch (e: Exception) {
                Log.e(TAG, "初始化硬币器异常", e)
                _errorMessage.value = "硬币器初始化异常: ${e.message}"
            }
        }
    }
    
    /**
     * 断开纸币器连接
     */
    fun disconnectBillAcceptor() {
        viewModelScope.launch {
            val deviceID = _billAcceptorDeviceID.value
            if (deviceID != null) {
                repository.disconnectDevice(deviceID)
            }
        }
    }
    
    /**
     * 断开硬币器连接
     */
    fun disconnectCoinAcceptor() {
        viewModelScope.launch {
            val deviceID = _coinAcceptorDeviceID.value
            if (deviceID != null) {
                repository.disconnectDevice(deviceID)
            }
        }
    }
    
    /**
     * 获取设备状态（用于轮询收款信息）
     * TODO: 根据实际 API 响应解析收款金额
     */
    fun pollDeviceStatus() {
        viewModelScope.launch {
            try {
                val billDeviceID = _billAcceptorDeviceID.value
                val coinDeviceID = _coinAcceptorDeviceID.value
                
                // 轮询纸币器状态
                if (billDeviceID != null) {
                    val status = repository.getDeviceStatus(billDeviceID)
                    Log.d(TAG, "纸币器状态: $status")
                    // TODO: 解析状态中的收款信息，更新 _totalAmount
                }
                
                // 轮询硬币器状态
                if (coinDeviceID != null) {
                    val status = repository.getDeviceStatus(coinDeviceID)
                    Log.d(TAG, "硬币器状态: $status")
                    // TODO: 解析状态中的收款信息，更新 _totalAmount
                }
            } catch (e: Exception) {
                Log.e(TAG, "轮询设备状态异常", e)
            }
        }
    }
    
    /**
     * 重置收款金额
     */
    fun resetAmount() {
        _totalAmount.value = 0.0
        Log.d(TAG, "重置收款金额")
    }
    
    /**
     * 添加收款金额（用于测试或手动更新）
     */
    fun addAmount(amount: Double) {
        _totalAmount.value += amount
        Log.d(TAG, "添加收款金额: $amount, 总计: ${_totalAmount.value}")
    }
}
