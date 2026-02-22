package com.carwash.carpayment.data.config

import android.util.Log

/**
 * 超时配置管理（V3.4 规范）
 * 
 * 集中管理所有超时常量，避免硬编码。
 * 当前阶段仅提供默认值，后续可扩展为从 Device Test 或 SharedPreferences 读取。
 */
object TimeoutConfig {
    
    private const val TAG = "TimeoutConfig"
    
    /**
     * PLC 运行确认超时（毫秒）
     * 
     * V3.4 规范：在 MODE 写入成功后的 1500ms 内，必须检测到以下任一条件：
     * - 214 == 1（设备进入运行）
     * - 102 != 0（模式寄存器确认）
     * 
     * 若满足：OrderState = ORDER_COMPLETED
     * 若未满足（超时未检测到）：OrderState = ORDER_MANUAL
     * 
     * 默认值：1500ms（可在 Device Test → Timeouts 中配置）
     */
    var PLC_CONFIRM_TIMEOUT_MS: Long = 1500L
        private set
    
    /**
     * GateCheck 等待 240 软超时（秒）
     * 默认值：120秒
     */
    var GATECHECK_WAIT240_SOFT_TIMEOUT_SEC: Int = 120
    
    /**
     * GateCheck 等待 240 硬超时（秒）
     * 默认值：300秒
     */
    var GATECHECK_WAIT240_HARD_TIMEOUT_SEC: Int = 300
    
    /**
     * Start_Wait214 软超时（秒）
     * 默认值：10秒
     */
    var START_WAIT214_SOFT_TIMEOUT_SEC: Int = 10
    
    /**
     * Start_Wait214 硬超时（秒）
     * 默认值：30秒
     */
    var START_WAIT214_HARD_TIMEOUT_SEC: Int = 30
    
    /**
     * 现金支付投币超时（秒）
     * 默认值：60秒（软超时）、180秒（硬超时）
     */
    var CASH_PAYMENT_SOFT_TIMEOUT_SEC: Int = 60
    var CASH_PAYMENT_HARD_TIMEOUT_SEC: Int = 180
    
    /**
     * POS 支付交易超时（秒）
     * 默认值：30秒（软超时）、60秒（硬超时）
     */
    var POS_PAYMENT_SOFT_TIMEOUT_SEC: Int = 30
    var POS_PAYMENT_HARD_TIMEOUT_SEC: Int = 60
    
    /**
     * 更新 PLC 确认超时配置
     * 
     * @param timeoutMs 超时时间（毫秒），必须 > 0
     */
    fun updatePlcConfirmTimeout(timeoutMs: Long) {
        if (timeoutMs <= 0) {
            Log.w(TAG, "无效的超时值: $timeoutMs，使用默认值 1500ms")
            return
        }
        PLC_CONFIRM_TIMEOUT_MS = timeoutMs
        Log.d(TAG, "PLC 确认超时已更新: ${PLC_CONFIRM_TIMEOUT_MS}ms")
    }
    
    /**
     * 从 SharedPreferences 或配置中心加载超时配置
     * 当前阶段：使用默认值
     * 后续扩展：从 Device Test 或云端配置读取
     */
    fun loadFromConfig() {
        // TODO: 后续实现从 SharedPreferences 或配置中心读取
        Log.d(TAG, "使用默认超时配置")
    }
}
