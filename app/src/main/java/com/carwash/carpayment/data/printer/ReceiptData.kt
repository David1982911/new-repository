package com.carwash.carpayment.data.printer

import java.util.Date

/**
 * 小票数据模型
 * 打印机模块自洽的数据模型，不依赖支付业务类
 */
data class ReceiptData(
    val invoiceId: String,
    val date: Date,
    val paymentLabel: String,  // 例如 "现金" / "刷卡支付"
    val programLabel: String,  // 例如 "基础版" / "标准版" / "高级版"
    val amountCents: Int,
    val headerTitle: String,
    val storeAddress: String
) {
    /**
     * 获取金额显示文本（欧元）
     */
    fun getAmountText(): String {
        return String.format("%.2f€", amountCents / 100.0)
    }
}
