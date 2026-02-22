package com.carwash.carpayment.data.printer

/**
 * 小票配置数据模型（V3.4 规范）
 * 
 * 商户信息配置：
 * - MerchantName: 必填，不可隐藏
 * - Address: 可选，可隐藏
 * - Phone: 可选，可隐藏
 * 
 * 打印字段控制：
 * - showAddress: 控制是否打印地址
 * - showPhone: 控制是否打印电话
 * - showTerminalId: 控制是否打印终端号
 */
data class ReceiptSettings(
    /**
     * 商户名称（必填，不可隐藏）
     * V3.4 规范：MerchantName 必须输入，不允许隐藏
     */
    val merchantName: String = "Welcome to ChuangLi store",
    
    /**
     * 地址（可选，可隐藏）
     */
    val address: String = "Room 2035, 2nd Floor, Chengyun Building, Shenzhou Industrial Zone, Bantian, Longgang District, Shenzhen",
    
    /**
     * 电话（可选，可隐藏）
     */
    val phone: String = "",
    
    /**
     * 终端ID（可选，可隐藏）
     */
    val terminalId: String = "",
    
    /**
     * 是否显示地址
     * V3.4 规范：默认开启，可在 Device Test → Printer 中配置
     */
    val showAddress: Boolean = true,
    
    /**
     * 是否显示电话
     * V3.4 规范：默认开启，可在 Device Test → Printer 中配置
     */
    val showPhone: Boolean = true,
    
    /**
     * 是否显示终端ID
     * V3.4 规范：默认开启，可在 Device Test → Printer 中配置
     */
    val showTerminalId: Boolean = true,
    
    /**
     * 兼容字段：headerTitle（映射到 merchantName）
     */
    @Deprecated("使用 merchantName", ReplaceWith("merchantName"))
    val headerTitle: String = merchantName,
    
    /**
     * 兼容字段：storeAddress（映射到 address）
     */
    @Deprecated("使用 address", ReplaceWith("address"))
    val storeAddress: String = address
)
