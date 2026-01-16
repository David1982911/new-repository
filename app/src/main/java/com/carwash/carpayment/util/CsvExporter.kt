package com.carwash.carpayment.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.carwash.carpayment.data.transaction.Transaction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CSV 导出工具类
 */
object CsvExporter {
    
    private const val TAG = "CsvExporter"
    
    /**
     * 导出交易记录为 CSV
     * @return 文件路径，如果失败返回 null
     */
    suspend fun exportToCsv(
        context: Context,
        transactions: List<Transaction>,
        fileName: String? = null
    ): String? {
        if (transactions.isEmpty()) {
            Log.w(TAG, "没有交易记录可导出")
            return null
        }
        
        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
            val defaultFileName = "transactions_${dateFormat.format(Date())}.csv"
            val finalFileName = fileName ?: defaultFileName
            
            val csvContent = buildCsvContent(transactions)
            
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用 MediaStore
                saveToMediaStore(context, finalFileName, csvContent)
            } else {
                // Android 9 及以下使用传统文件系统
                saveToDownloads(context, finalFileName, csvContent)
            }
            
            if (uri != null) {
                Log.d(TAG, "CSV 导出成功: $finalFileName")
                return uri
            } else {
                Log.e(TAG, "CSV 导出失败")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "CSV 导出异常", e)
            return null
        }
    }
    
    /**
     * 构建 CSV 内容
     */
    private fun buildCsvContent(transactions: List<Transaction>): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        
        // CSV 头部（BOM for Excel compatibility）
        sb.append("\uFEFF")  // UTF-8 BOM
        sb.append("ID,时间,程序ID,程序名称,支付方式,金额(€),结果\n")
        
        // 数据行
        transactions.forEach { transaction ->
            sb.append("${transaction.id},")
            sb.append("${dateFormat.format(Date(transaction.timestamp))},")
            sb.append("${escapeCsvField(transaction.programId)},")
            sb.append("${escapeCsvField(transaction.programName)},")
            sb.append("${escapeCsvField(transaction.paymentMethod.name)},")
            sb.append("${transaction.amount},")
            sb.append("${escapeCsvField(transaction.result.name)}\n")
        }
        
        return sb.toString()
    }
    
    /**
     * 转义 CSV 字段（处理逗号、引号、换行）
     */
    private fun escapeCsvField(field: String): String {
        return if (field.contains(',') || field.contains('"') || field.contains('\n')) {
            "\"${field.replace("\"", "\"\"")}\""
        } else {
            field
        }
    }
    
    /**
     * 保存到 MediaStore (Android 10+)
     */
    private fun saveToMediaStore(
        context: Context,
        fileName: String,
        content: String
    ): String? {
        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                contentValues
            )
            
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    outputStream.write(content.toByteArray(Charsets.UTF_8))
                }
                it.toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存到 MediaStore 失败", e)
            null
        }
    }
    
    /**
     * 保存到 Downloads 目录 (Android 9 及以下)
     */
    private fun saveToDownloads(
        context: Context,
        fileName: String,
        content: String
    ): String? {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            
            val file = java.io.File(downloadsDir, fileName)
            file.writeText(content, Charsets.UTF_8)
            
            Log.d(TAG, "文件已保存: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "保存到 Downloads 失败", e)
            null
        }
    }
}
