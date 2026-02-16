package com.carwash.carpayment.data.cashdevice.device

import com.carwash.carpayment.data.cashdevice.LevelEntry

/**
 * ⚠️ V3.2 新增：现金设备统一接口
 * 设备层职责：只做收发，不重试、不重连、不缓存业务状态
 * 所有方法必须抛出异常，由状态管理层捕获并决策
 */
interface CashDevice {
    /**
     * 打开设备连接
     * ⚠️ 仅在 Application 启动时调用一次，支付会话中严禁调用
     * @param port 串口号（纸币器=0，硬币器=1）
     * @param sspAddress SSP 地址（纸币器=0，硬币器=16）
     * @return true 如果连接成功，false 如果连接失败
     * @throws Exception 连接异常（不捕获，直接抛出）
     */
    suspend fun openConnection(port: Int, sspAddress: Int): Boolean
    
    /**
     * 获取设备状态
     * ⚠️ 不捕获异常，直接抛出给调用层
     * @return DeviceStatus 设备状态
     * @throws Exception 获取状态异常（如网络超时、设备离线）
     */
    suspend fun getDeviceStatus(): DeviceStatus
    
    /**
     * 启用/禁用接收器
     * @param enable true 启用接收器，false 禁用接收器
     * @return true 如果操作成功，false 如果操作失败
     * @throws Exception 操作异常（不捕获，直接抛出）
     */
    suspend fun enableAcceptor(enable: Boolean): Boolean
    
    /**
     * 获取所有面额库存
     * ⚠️ 不捕获 SocketTimeoutException，直接抛出给调用层
     * @return List<Denomination> 面额库存列表
     * @throws Exception 获取库存异常（如网络超时、设备离线）
     */
    suspend fun getAllLevels(): List<Denomination>
    
    /**
     * 开始收款（启用接收器并开启自动接受）
     * @return true 如果操作成功，false 如果操作失败
     * @throws Exception 操作异常（不捕获，直接抛出）
     */
    suspend fun collect(): Boolean
    
    /**
     * 找零（按金额）
     * @param amountCents 找零金额（分）
     * @return DispenseResult 找零结果
     * @throws Exception 找零异常（不捕获，直接抛出）
     */
    suspend fun dispenseChange(amountCents: Int): DispenseResult
    
    /**
     * 获取找零状态
     * @return ChangeStatus 找零状态
     * @throws Exception 获取状态异常（不捕获，直接抛出）
     */
    suspend fun getChangeStatus(): ChangeStatus
    
    /**
     * 设备是否在线（只读属性）
     * 由 CashDeviceStatusMonitor 定期更新
     */
    val isOnline: Boolean

    /**
     * ⚠️ V3.2 新增：获取设备ID
     * @return 设备ID（如果已连接），否则返回 null
     */
    fun getDeviceID(): String?
}

/**
 * ⚠️ V3.2 新增：设备状态数据类
 */
data class DeviceStatus(
    val online: Boolean,  // 设备是否在线
    val state: String,    // 设备状态（如 "IDLE", "STARTED", "CONNECTED"）
    val error: String? = null  // 错误信息（如果有）
)

/**
 * ⚠️ V3.2 新增：面额数据类（与 LevelEntry 对应）
 */
data class Denomination(
    val value: Int,        // 面额（分）
    val stored: Int,       // 库存数量（张/枚）
    val countryCode: String = "EUR"  // 货币代码
)

/**
 * ⚠️ V3.2 新增：找零结果
 */
data class DispenseResult(
    val success: Boolean,      // 是否成功
    val amountDispensed: Int,  // 已找零金额（分）
    val remaining: Int,        // 剩余未找零金额（分）
    val error: String? = null  // 错误信息（如果有）
)

/**
 * ⚠️ V3.2 新增：找零状态
 */
data class ChangeStatus(
    val isProcessing: Boolean,  // 是否正在处理找零
    val amountRemaining: Int,   // 剩余未找零金额（分）
    val error: String? = null   // 错误信息（如果有）
)
