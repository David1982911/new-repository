package com.carwash.carpayment.data.cashdevice

/**
 * ⚠️ V3.2 新增：现金支付策略配置
 * 统一管理轮询间隔、超时次数等策略参数
 */
object CashPaymentConfig {
    /**
     * 设备状态轮询间隔（毫秒）
     * 用于 CashDeviceStatusMonitor 定期检查设备在线状态
     * ⚠️ V3.2 规范：严格控制在 350ms - 500ms
     */
    const val DEVICE_STATUS_POLL_INTERVAL = 400L  // ms (350-500ms 范围内)
    
    /**
     * 支付轮询间隔（毫秒）
     * 用于 PaymentViewModel 轮询 GetAllLevels 获取已收金额
     */
    const val PAYMENT_POLL_INTERVAL = 1000L  // ms
    
    /**
     * 支付轮询最大连续失败次数
     * 连续失败次数达到此值时，触发硬超时并进入退款流程
     */
    const val PAYMENT_POLL_MAX_FAILURES = 3
    
    /**
     * 支付硬超时时间（毫秒）
     * 支付流程总时长超过此值时，强制进入退款流程
     */
    const val PAYMENT_HARD_TIMEOUT = 180000L  // ms (3分钟)
}
