package com.carwash.carpayment.data.printer

import android.content.Context
import android.util.Log
import com.carwash.carpayment.data.PaymentMethod
import com.carwash.carpayment.data.WashProgram
import com.carwash.carpayment.data.transaction.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale

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
            
            // 生成交易ID
            val transactionId = InvoiceIdGenerator.generate()
            
            // 获取当前语言环境（用于多语言输出）
            val locale = context.resources.configuration.locales[0]
            
            // V3.4 规范：组装小票数据（新结构）
            val receiptData = ReceiptData(
                transactionId = transactionId,
                dateTime = Date(transaction.timestamp),
                terminalId = settings.terminalId.takeIf { settings.showTerminalId && it.isNotBlank() },
                paymentMethod = transaction.paymentMethod,  // V3.4 规范：POS 仅显示 Payment Card / Zahlungskarte
                programName = transaction.programName,
                unitPriceCents = (transaction.amount * 100).toLong(),
                amountPaidCents = (transaction.amount * 100).toLong(),
                changeCents = 0L,  // 交易记录中没有找零信息
                status = "SUCCESS",
                merchantName = settings.merchantName,
                address = settings.address.takeIf { settings.showAddress && !it.isBlank() },
                phone = settings.phone.takeIf { settings.showPhone && !it.isBlank() },
                showAddress = settings.showAddress,
                showPhone = settings.showPhone,
                showTerminalId = settings.showTerminalId
            )
            
            // V3.4 规范：打印小票（使用多语言格式化）
            val printSuccess = receiptPrinter.print(receiptData, locale)
            
            if (printSuccess) {
                Log.d(TAG, "V3.4 打印成功: transactionId=$transactionId")
                return@withContext PrintResult.Success(transactionId)
            } else {
                // 打印失败时，尝试获取更详细的错误信息
                val statusCode = try {
                    receiptPrinter.checkStatus()
                } catch (e: Exception) {
                    -999  // 状态检查异常
                }
                val reason = "Print failed (printer returned false, statusCode=$statusCode)"
                Log.e(TAG, "打印失败: $reason, transactionId=$transactionId")
                val currentDevice = receiptPrinter.getCurrentUsbDevice()
                val deviceInfo = if (currentDevice != null) {
                    "VID=${currentDevice.vendorId}, PID=${currentDevice.productId}"
                } else {
                    "No device"
                }
                Log.e(TAG, "打印失败: statusCode=$statusCode, transactionId=$transactionId, device=$deviceInfo")
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
     * V3.4 规范：PaymentAuthorized 立即打印，80mm 模板，多语言 EN/DE
     * @param program 洗车程序
     * @param paymentMethod 支付方式
     * @param amountCents 金额（分）
     * @param changeCents 找零金额（分），默认为 0
     * @return 打印结果
     */
    suspend fun printForPayment(
        program: WashProgram,
        paymentMethod: PaymentMethod,
        amountCents: Int,
        changeCents: Long = 0L
    ): PrintResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "V3.4 支付成功 -> 触发打印: program=${program.name}, paymentMethod=$paymentMethod, amount=${amountCents / 100.0}€, change=${changeCents / 100.0}€")
            
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
            
            // 生成交易ID
            val transactionId = InvoiceIdGenerator.generate()
            
            // 获取当前语言环境（用于多语言输出）
            val locale = context.resources.configuration.locales[0]
            Log.d(TAG, "当前语言环境: ${locale.language}")
            
            // V3.4 规范：组装小票数据（新结构）
            val receiptData = ReceiptData(
                transactionId = transactionId,
                dateTime = Date(),
                terminalId = settings.terminalId.takeIf { settings.showTerminalId && it.isNotBlank() },
                paymentMethod = paymentMethod,  // V3.4 规范：POS 仅显示 Payment Card / Zahlungskarte
                programName = program.name,
                unitPriceCents = amountCents.toLong(),
                amountPaidCents = amountCents.toLong(),
                changeCents = changeCents,
                status = "SUCCESS",
                merchantName = settings.merchantName,
                address = settings.address.takeIf { settings.showAddress && !it.isBlank() },
                phone = settings.phone.takeIf { settings.showPhone && !it.isBlank() },
                showAddress = settings.showAddress,
                showPhone = settings.showPhone,
                showTerminalId = settings.showTerminalId
            )
            
            // V3.4 规范：打印小票（使用多语言格式化）
            val printSuccess = receiptPrinter.print(receiptData, locale)
            
            if (printSuccess) {
                Log.d(TAG, "打印成功: transactionId=$transactionId")
                return@withContext PrintResult.Success(transactionId)
            } else {
                // 打印失败时，尝试获取更详细的错误信息
                val statusCode = try {
                    receiptPrinter.checkStatus()
                } catch (e: Exception) {
                    -999  // 状态检查异常
                }
                val reason = "Print failed (printer returned false, statusCode=$statusCode)"
                Log.e(TAG, "打印失败: $reason, transactionId=$transactionId")
                val currentDevice = receiptPrinter.getCurrentUsbDevice()
                val deviceInfo = if (currentDevice != null) {
                    "VID=${currentDevice.vendorId}, PID=${currentDevice.productId}"
                } else {
                    "No device"
                }
                Log.e(TAG, "打印失败: statusCode=$statusCode, transactionId=$transactionId, device=$deviceInfo")
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
