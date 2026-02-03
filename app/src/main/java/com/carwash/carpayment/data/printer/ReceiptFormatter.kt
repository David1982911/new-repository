package com.carwash.carpayment.data.printer

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 小票内容格式化器
 * 将 ReceiptData 格式化为可打印的文本
 */
object ReceiptFormatter {
    
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.US)
    
    /**
     * 格式化小票内容
     * @param receiptData 小票数据
     * @return 格式化后的小票文本行列表
     */
    fun format(receiptData: ReceiptData): List<String> {
        val lines = mutableListOf<String>()
        
        // 左上角：Header Title（左对齐）
        lines.add(receiptData.headerTitle)
        lines.add("")
        
        // Address（多行，左对齐）
        val addressLines = receiptData.storeAddress.split("\n")
        addressLines.forEach { line ->
            lines.add(line)
        }
        lines.add("")
        
        // 分隔线
        lines.add("--------------------------------")
        lines.add("")
        
        // Invoice: {invoiceId}
        lines.add("Invoice: ${receiptData.invoiceId}")
        
        // Date: {dd.MM.yyyy}
        lines.add("Date: ${dateFormat.format(receiptData.date)}")
        
        // Payment: 现金 / 刷卡支付
        lines.add("Payment: ${receiptData.paymentLabel}")
        
        // Program: 基础版 / 标准版 / 高级版
        lines.add("Program: ${receiptData.programLabel}")
        
        // Amount: {3.50€}
        lines.add("Amount: ${receiptData.getAmountText()}")
        
        // 分隔线
        lines.add("")
        lines.add("--------------------------------")
        lines.add("")
        lines.add("")
        lines.add("")
        
        // 右下角：Thank you have a nice day（右对齐）
        // 通过添加空格实现右对齐效果
        val thankYouText = "Thank you have a nice day"
        // 假设小票宽度为 48 字符（80mm 热敏打印机通常为 48 字符）
        val receiptWidth = 48
        val padding = receiptWidth - thankYouText.length
        if (padding > 0) {
            lines.add(" ".repeat(padding) + thankYouText)
        } else {
            lines.add(thankYouText)
        }
        
        return lines
    }
}
