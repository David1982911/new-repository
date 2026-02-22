package com.carwash.carpayment.data.printer

import com.carwash.carpayment.data.PaymentMethod
import java.util.Date

/**
 * 小票数据模型（V3.4 规范）
 * 打印机模块自洽的数据模型，不依赖支付业务类
 * 
 * V3.4 规范字段：
 * - transactionId: 唯一交易号
 * - dateTime: 交易完成时间
 * - terminalId: 终端编号（可选）
 * - paymentMethod: CASH / POS
 * - programName: 套餐名称
 * - unitPriceCents: 单价（分）
 * - amountPaidCents: 实际支付金额（分）
 * - changeCents: 找零金额（分）
 * - status: SUCCESS
 */
data class ReceiptData(
    /**
     * 交易ID（唯一交易号）
     */
    val transactionId: String,
    
    /**
     * 交易完成时间
     */
    val dateTime: Date,
    
    /**
     * 终端ID（可选）
     */
    val terminalId: String? = null,
    
    /**
     * 支付方式（CASH / POS）
     */
    val paymentMethod: PaymentMethod,
    
    /**
     * 套餐名称
     */
    val programName: String,
    
    /**
     * 单价（分）
     */
    val unitPriceCents: Long,
    
    /**
     * 实际支付金额（分）
     */
    val amountPaidCents: Long,
    
    /**
     * 找零金额（分）
     */
    val changeCents: Long = 0L,
    
    /**
     * 状态（SUCCESS）
     */
    val status: String = "SUCCESS",
    
    /**
     * 商户信息（从 ReceiptSettings 读取）
     */
    val merchantName: String,
    val address: String? = null,
    val phone: String? = null,
    val showAddress: Boolean = true,
    val showPhone: Boolean = true,
    val showTerminalId: Boolean = true,
    
    // 注意：兼容字段不能使用默认参数引用其他参数，需要通过 getter 提供
) {
    /**
     * 获取金额显示文本（欧元）
     */
    fun getAmountText(): String {
        return String.format("%.2f€", amountPaidCents / 100.0)
    }
    
    /**
     * 获取找零金额显示文本（欧元）
     */
    fun getChangeText(): String {
        return String.format("%.2f€", changeCents / 100.0)
    }
    
    /**
     * 获取单价显示文本（欧元）
     */
    fun getUnitPriceText(): String {
        return String.format("%.2f€", unitPriceCents / 100.0)
    }
    
    /**
     * 兼容字段：invoiceId（映射到 transactionId）
     */
    @Deprecated("使用 transactionId", ReplaceWith("transactionId"))
    val invoiceId: String
        get() = transactionId
    
    /**
     * 兼容字段：date（映射到 dateTime）
     */
    @Deprecated("使用 dateTime", ReplaceWith("dateTime"))
    val date: Date
        get() = dateTime
    
    /**
     * 兼容字段：paymentLabel（从 paymentMethod 推导，需要 locale）
     */
    @Deprecated("使用 paymentMethod", ReplaceWith("paymentMethod"))
    fun getPaymentLabel(locale: java.util.Locale): String {
        return when (paymentMethod) {
            PaymentMethod.CASH -> if (locale.language == "de") "Barzahlung" else "Cash"
            PaymentMethod.CARD -> if (locale.language == "de") "Zahlungskarte" else "Payment Card"
        }
    }
    
    /**
     * 兼容字段：programLabel（映射到 programName）
     */
    @Deprecated("使用 programName", ReplaceWith("programName"))
    val programLabel: String
        get() = programName
    
    /**
     * 兼容字段：amountCents（映射到 amountPaidCents）
     */
    @Deprecated("使用 amountPaidCents", ReplaceWith("amountPaidCents"))
    val amountCents: Int
        get() = amountPaidCents.toInt()
    
    /**
     * 兼容字段：headerTitle（映射到 merchantName）
     */
    @Deprecated("使用 merchantName", ReplaceWith("merchantName"))
    val headerTitle: String
        get() = merchantName
    
    /**
     * 兼容字段：storeAddress（映射到 address）
     */
    @Deprecated("使用 address", ReplaceWith("address"))
    val storeAddress: String
        get() = address ?: ""
}
