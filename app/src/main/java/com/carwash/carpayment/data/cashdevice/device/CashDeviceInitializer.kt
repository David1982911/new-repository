package com.carwash.carpayment.data.cashdevice.device

import android.util.Log
import com.carwash.carpayment.data.cashdevice.CashDeviceRepository

/**
 * ⚠️ V3.2 新增：统一设备连接管理器
 * 职责：仅在 Application 启动时调用一次 OpenConnection
 * 支付会话中严禁调用
 * 
 * ⚠️ 关键修复：OpenConnection 成功后，将 DeviceID 保存到 Repository
 */
class CashDeviceInitializer(
    private val billAcceptor: CashDevice,
    private val coinAcceptor: CashDevice,
    private val repository: CashDeviceRepository? = null  // 可选：用于保存设备ID
) {
    
    companion object {
        private const val TAG = "CashDeviceInitializer"
        
        // 设备端口和SSP地址配置
        private const val BILL_PORT = 0
        private const val BILL_SSP_ADDRESS = 0
        private const val COIN_PORT = 1
        private const val COIN_SSP_ADDRESS = 16
    }
    
    /**
     * 初始化设备连接
     * ⚠️ 仅在 Application.onCreate() 中调用一次
     * 失败不影响整体启动，仅记录日志
     * 
     * ⚠️ 关键修复：OpenConnection 成功后，将 DeviceID 保存到 Repository
     */
    suspend fun initialize() {
        Log.d(TAG, "========== 开始初始化现金设备连接 ==========")
        
        // 纸币器连接
        runCatching {
            val success = billAcceptor.openConnection(BILL_PORT, BILL_SSP_ADDRESS)
            if (success) {
                val deviceID = billAcceptor.getDeviceID()
                if (deviceID != null) {
                    Log.d(TAG, "✅ 纸币器连接成功: deviceID=$deviceID")
                    // ⚠️ 关键修复：保存设备ID到 Repository
                    repository?.setBillAcceptorDeviceID(deviceID)
                } else {
                    Log.w(TAG, "⚠️ 纸币器连接成功但 deviceID 为空")
                }
            } else {
                Log.w(TAG, "纸币器连接失败（不影响整体启动）")
            }
        }.onFailure { e ->
            Log.e(TAG, "纸币器连接异常（不影响整体启动）", e)
        }
        
        // 硬币器连接
        runCatching {
            val success = coinAcceptor.openConnection(COIN_PORT, COIN_SSP_ADDRESS)
            if (success) {
                val deviceID = coinAcceptor.getDeviceID()
                if (deviceID != null) {
                    Log.d(TAG, "✅ 硬币器连接成功: deviceID=$deviceID")
                    // ⚠️ 关键修复：保存设备ID到 Repository
                    repository?.setCoinAcceptorDeviceID(deviceID)
                } else {
                    Log.w(TAG, "⚠️ 硬币器连接成功但 deviceID 为空")
                }
            } else {
                Log.w(TAG, "硬币器连接失败（不影响整体启动）")
            }
        }.onFailure { e ->
            Log.e(TAG, "硬币器连接异常（不影响整体启动）", e)
        }
        
        Log.d(TAG, "========== 现金设备连接初始化完成 ==========")
    }
}
