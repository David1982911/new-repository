package com.carwash.carpayment.data.printer

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random

/**
 * Invoice ID 生成器
 * 生成唯一的发票号码
 */
object InvoiceIdGenerator {
    
    private val random = Random()
    
    /**
     * 生成唯一的 Invoice ID
     * 格式：yyyyMMddHHmmssSSS + 4位随机数
     * 例如：2026012214302512345678
     */
    fun generate(): String {
        val dateFormat = SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.US)
        val timestamp = dateFormat.format(Date())
        val randomSuffix = String.format("%04d", random.nextInt(10000))
        return "$timestamp$randomSuffix"
    }
}
