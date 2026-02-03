package com.carwash.carpayment.data.printer

import android.content.Context
import android.util.Log
import com.carwash.carpayment.data.PaymentMethod
import com.carwash.carpayment.data.WashProgram
import com.carwash.carpayment.data.transaction.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

private const val TAG = "ReceiptPrintService"

/**
 * 打印结果
 */
sealed class PrintResult {
    data class Success(val invoiceId: String) : PrintResult()
    data class Failure(val reason: String) : PrintResult()
}

/**
 * 统一打印服务
 * 提供交易记录打印功能
 */
class ReceiptPrintService(
    private val context: Context,
    private val receiptPrinter: ReceiptPrinter,
    private val receiptSettingsRepository: ReceiptSettingsRepository
) {
    
    /**
     * 为交易记录打印小票
     * @param transaction 交易记录（包含支付方式、程序、金额等信息）
     * @return 打印结果
     */
    suspend fun printForTransaction(transaction: Transaction): PrintResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "支付成功 -> 触发打印: transactionId=${transaction.id}, amount=${transaction.amount}€")
            
            // 检查打印机是否已连接（增强检测 + 自动重试）
            if (!receiptPrinter.isConnected()) {
                // 先尝试重新连接一次
                Log.d(TAG, "打印机未连接，尝试重新连接...")
                val reconnected = receiptPrinter.tryReconnect()
                if (reconnected) {
                    Log.d(TAG, "重新连接成功，继续打印")
                } else {
                    // 等待一段时间后再检查
                    kotlinx.coroutines.delay(500)
                    if (!receiptPrinter.isConnected()) {
                        // 获取诊断信息（仅用于日志）
                        val diagnostics = receiptPrinter.getConnectionDiagnostics()
                        Log.w(TAG, "打印失败: Printer not connected. Diagnostics: $diagnostics")
                        // UI 消息简短，不包含诊断信息
                        val currentDevice = receiptPrinter.getCurrentUsbDevice()
                        val deviceInfo = if (currentDevice != null) {
                            "VID=${currentDevice.vendorId}, PID=${currentDevice.productId}"
                        } else {
                            "No device selected"
                        }
                        Log.w(TAG, "打印失败: Printer not connected. Device: $deviceInfo")
                        return@withContext PrintResult.Failure("PRINTER_NOT_CONNECTED")
                    }
                }
            }
            
            // 读取小票配置
            val settings = receiptSettingsRepository.getCurrentSettings()
            
            // 生成 Invoice ID
            val invoiceId = InvoiceIdGenerator.generate()
            
            // 映射支付方式显示文本
            val paymentLabel = when (transaction.paymentMethod) {
                PaymentMethod.CASH -> "现金"
                PaymentMethod.CARD -> "刷卡支付"
            }
            
            // 使用程序名称（不再硬编码）
            val programLabel = transaction.programName
            
            // 组装小票数据
            val receiptData = ReceiptData(
                invoiceId = invoiceId,
                date = Date(transaction.timestamp),
                paymentLabel = paymentLabel,
                programLabel = programLabel,
                amountCents = (transaction.amount * 100).toInt(),
                headerTitle = settings.headerTitle,
                storeAddress = settings.storeAddress
            )
            
            // 打印小票
            val printSuccess = receiptPrinter.print(receiptData)
            
            if (printSuccess) {
                Log.d(TAG, "打印成功: invoiceId=$invoiceId")
                return@withContext PrintResult.Success(invoiceId)
            } else {
                // 打印失败时，尝试获取更详细的错误信息
                val statusCode = try {
                    receiptPrinter.checkStatus()
                } catch (e: Exception) {
                    -999  // 状态检查异常
                }
                val reason = "Print failed (printer returned false, statusCode=$statusCode)"
                Log.e(TAG, "打印失败: $reason, invoiceId=$invoiceId")
                val currentDevice = receiptPrinter.getCurrentUsbDevice()
                val deviceInfo = if (currentDevice != null) {
                    "VID=${currentDevice.vendorId}, PID=${currentDevice.productId}"
                } else {
                    "No device"
                }
                Log.e(TAG, "打印失败: statusCode=$statusCode, invoiceId=$invoiceId, device=$deviceInfo")
                val errorCode = when (statusCode) {
                    -2 -> "PRINTER_CUTTER_ERROR"
                    -3 -> "PRINTER_OVERHEAT"
                    -4 -> "PRINTER_OFFLINE"
                    -5 -> "PRINTER_NO_PAPER"
                    -6 -> "PRINTER_COVER_OPEN"
                    -7, -8 -> "PRINTER_STATUS_QUERY_FAILED"
                    else -> "PRINTER_WRITE_FAILED"
                }
                return@withContext PrintResult.Failure(errorCode)
            }
            
        } catch (t: Throwable) {
            val reason = "Print exception: ${t.message ?: t::class.java.simpleName}"
            Log.e(TAG, "打印异常: $reason", t)
            return@withContext PrintResult.Failure("PRINTER_EXCEPTION")
        }
    }
    
    /**
     * 为支付状态打印小票（便捷方法，用于 PaymentViewModel）
     * @param program 洗车程序
     * @param paymentMethod 支付方式
     * @param amountCents 金额（分）
     * @return 打印结果
     */
    suspend fun printForPayment(
        program: WashProgram,
        paymentMethod: PaymentMethod,
        amountCents: Int
    ): PrintResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "支付成功 -> 触发打印: program=${program.name}, paymentMethod=$paymentMethod, amount=${amountCents / 100.0}€")
            
            // 检查打印机是否已连接（增强检测 + 自动重试）
            if (!receiptPrinter.isConnected()) {
                // 先尝试重新连接一次
                Log.d(TAG, "打印机未连接，尝试重新连接...")
                val reconnected = receiptPrinter.tryReconnect()
                if (reconnected) {
                    Log.d(TAG, "重新连接成功，继续打印")
                } else {
                    // 等待一段时间后再检查
                    kotlinx.coroutines.delay(500)
                    if (!receiptPrinter.isConnected()) {
                        // 获取诊断信息（仅用于日志）
                        val diagnostics = receiptPrinter.getConnectionDiagnostics()
                        Log.w(TAG, "打印失败: Printer not connected. Diagnostics: $diagnostics")
                        // UI 消息简短，不包含诊断信息
                        val currentDevice = receiptPrinter.getCurrentUsbDevice()
                        val deviceInfo = if (currentDevice != null) {
                            "VID=${currentDevice.vendorId}, PID=${currentDevice.productId}"
                        } else {
                            "No device selected"
                        }
                        Log.w(TAG, "打印失败: Printer not connected. Device: $deviceInfo")
                        return@withContext PrintResult.Failure("PRINTER_NOT_CONNECTED")
                    }
                }
            }
            
            // 读取小票配置
            val settings = receiptSettingsRepository.getCurrentSettings()
            
            // 生成 Invoice ID
            val invoiceId = InvoiceIdGenerator.generate()
            
            // 映射支付方式显示文本
            val paymentLabel = when (paymentMethod) {
                PaymentMethod.CASH -> "现金"
                PaymentMethod.CARD -> "刷卡支付"
            }
            
            // 使用程序名称
            val programLabel = program.name
            
            // 组装小票数据
            val receiptData = ReceiptData(
                invoiceId = invoiceId,
                date = Date(),
                paymentLabel = paymentLabel,
                programLabel = programLabel,
                amountCents = amountCents,
                headerTitle = settings.headerTitle,
                storeAddress = settings.storeAddress
            )
            
            // 打印小票
            val printSuccess = receiptPrinter.print(receiptData)
            
            if (printSuccess) {
                Log.d(TAG, "打印成功: invoiceId=$invoiceId")
                return@withContext PrintResult.Success(invoiceId)
            } else {
                // 打印失败时，尝试获取更详细的错误信息
                val statusCode = try {
                    receiptPrinter.checkStatus()
                } catch (e: Exception) {
                    -999  // 状态检查异常
                }
                val reason = "Print failed (printer returned false, statusCode=$statusCode)"
                Log.e(TAG, "打印失败: $reason, invoiceId=$invoiceId")
                val currentDevice = receiptPrinter.getCurrentUsbDevice()
                val deviceInfo = if (currentDevice != null) {
                    "VID=${currentDevice.vendorId}, PID=${currentDevice.productId}"
                } else {
                    "No device"
                }
                Log.e(TAG, "打印失败: statusCode=$statusCode, invoiceId=$invoiceId, device=$deviceInfo")
                val errorCode = when (statusCode) {
                    -2 -> "PRINTER_CUTTER_ERROR"
                    -3 -> "PRINTER_OVERHEAT"
                    -4 -> "PRINTER_OFFLINE"
                    -5 -> "PRINTER_NO_PAPER"
                    -6 -> "PRINTER_COVER_OPEN"
                    -7, -8 -> "PRINTER_STATUS_QUERY_FAILED"
                    else -> "PRINTER_WRITE_FAILED"
                }
                return@withContext PrintResult.Failure(errorCode)
            }
            
        } catch (t: Throwable) {
            val reason = "Print exception: ${t.message ?: t::class.java.simpleName}"
            Log.e(TAG, "打印异常: $reason", t)
            return@withContext PrintResult.Failure("PRINTER_EXCEPTION")
        }
    }
}
