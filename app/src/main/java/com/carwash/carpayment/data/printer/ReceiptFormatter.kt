package com.carwash.carpayment.data.printer

import com.carwash.carpayment.data.PaymentMethod
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 小票内容格式化器（V3.4 规范：80mm 模板，多语言 EN/DE）
 * 将 ReceiptData 格式化为可打印的文本
 */
object ReceiptFormatter {
    
    private const val RECEIPT_WIDTH = 48  // 80mm 热敏打印机每行最大字符数
    private const val SEPARATOR_LINE = "================================================"  // 48 字符
    private const val DIVIDER_LINE = "------------------------------------------------"  // 48 字符
    
    /**
     * 格式化小票内容（V3.4 规范：80mm 模板，多语言）
     * @param receiptData 小票数据
     * @param locale 当前语言环境（用于多语言输出）
     * @return 格式化后的小票文本行列表
     */
    fun format(receiptData: ReceiptData, locale: Locale = Locale.getDefault()): List<String> {
        val lines = mutableListOf<String>()
        val isGerman = locale.language == "de"
        
        // 日期时间格式化（根据 Locale）
        val dateTimeFormat = if (isGerman) {
            SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMAN)
        } else {
            SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ENGLISH)
        }
        val dateTimeStr = dateTimeFormat.format(receiptData.dateTime)
        
        // 顶部分隔线
        lines.add(SEPARATOR_LINE)
        lines.add("")
        
        // 商户名称（必填，不可隐藏）
        lines.add(receiptData.merchantName)
        lines.add("")
        
        // 地址（可选，根据 showAddress 控制）
        if (receiptData.showAddress && !receiptData.address.isNullOrBlank()) {
            val addressLines = receiptData.address.split("\n")
            addressLines.forEach { line ->
                if (line.isNotBlank()) {
                    lines.add(line)
                }
            }
            lines.add("")
        }
        
        // 电话（可选，根据 showPhone 控制）
        if (receiptData.showPhone && !receiptData.phone.isNullOrBlank()) {
            lines.add(receiptData.phone)
            lines.add("")
        }
        
        // 顶部分隔线
        lines.add(SEPARATOR_LINE)
        lines.add("")
        
        // Transaction ID
        val transactionIdLabel = if (isGerman) "Transaktions-ID" else "Transaction ID"
        lines.add("${transactionIdLabel.padEnd(18)}: ${receiptData.transactionId}")
        
        // Date/Time
        val dateTimeLabel = if (isGerman) "Datum/Uhrzeit" else "Date/Time"
        lines.add("${dateTimeLabel.padEnd(18)}: $dateTimeStr")
        
        // Terminal ID（可选，根据 showTerminalId 控制）
        if (receiptData.showTerminalId && !receiptData.terminalId.isNullOrBlank()) {
            val terminalIdLabel = if (isGerman) "Terminal-ID" else "Terminal ID"
            lines.add("${terminalIdLabel.padEnd(18)}: ${receiptData.terminalId}")
        }
        
        // Payment Method（V3.4 规范：POS 仅显示 Payment Card / Zahlungskarte）
        val paymentMethodLabel = if (isGerman) "Zahlungsmethode" else "Payment Method"
        val paymentMethodValue = when (receiptData.paymentMethod) {
            PaymentMethod.CASH -> if (isGerman) "Barzahlung" else "Cash"
            PaymentMethod.CARD -> if (isGerman) "Zahlungskarte" else "Payment Card"
            else -> receiptData.paymentMethod.toString()  // 处理未知类型
        }
        lines.add("${paymentMethodLabel.padEnd(18)}: $paymentMethodValue")
        
        // 分隔线
        lines.add("")
        lines.add(DIVIDER_LINE)
        lines.add("")
        
        // Program Name
        val programNameLabel = if (isGerman) "Programmname" else "Program Name"
        lines.add("${programNameLabel.padEnd(18)}: ${receiptData.programName}")
        
        // Unit Price
        val unitPriceLabel = if (isGerman) "Preis" else "Unit Price"
        lines.add("${unitPriceLabel.padEnd(18)}: ${receiptData.getUnitPriceText()} EUR")
        
        // 分隔线
        lines.add("")
        lines.add(DIVIDER_LINE)
        lines.add("")
        
        // Amount Paid
        val amountPaidLabel = if (isGerman) "Bezahlt" else "Amount Paid"
        lines.add("${amountPaidLabel.padEnd(18)}: ${receiptData.getAmountText()} EUR")
        
        // Change Given（如果有找零）
        if (receiptData.changeCents > 0) {
            val changeLabel = if (isGerman) "Rückgeld" else "Change Given"
            lines.add("${changeLabel.padEnd(18)}: ${receiptData.getChangeText()} EUR")
        }
        
        // 分隔线
        lines.add("")
        lines.add(DIVIDER_LINE)
        lines.add("")
        
        // Status
        val statusLabel = if (isGerman) "Status" else "Status"
        val statusValue = if (isGerman) "ZAHLUNG ERFOLGREICH" else "PAYMENT SUCCESS"
        lines.add("${statusLabel.padEnd(18)}: $statusValue")
        
        // 底部分隔线
        lines.add("")
        lines.add(SEPARATOR_LINE)
        lines.add("")
        
        return lines
    }
    
    /**
     * 兼容方法：使用默认 Locale
     */
    @Deprecated("使用 format(receiptData, locale) 指定语言", ReplaceWith("format(receiptData, Locale.getDefault())"))
    fun format(receiptData: ReceiptData): List<String> {
        return format(receiptData, Locale.getDefault())
    }
}
