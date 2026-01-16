package com.carwash.carpayment.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carwash.carpayment.R
import com.carwash.carpayment.data.transaction.Transaction
import com.carwash.carpayment.data.transaction.TransactionResult
import com.carwash.carpayment.ui.theme.KioskButtonSizes
import com.carwash.carpayment.ui.viewmodel.TransactionListViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 交易列表查看页面
 */
@Composable
fun TransactionListScreen(
    viewModel: TransactionListViewModel,
    onBack: () -> Unit
) {
    val transactions by viewModel.transactions.collectAsState()
    val transactionCount by viewModel.transactionCount.collectAsState()
    val totalAmount by viewModel.totalAmount.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    
    var isExportingCsv by remember { mutableStateOf(false) }
    var isExportingExcel by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    
    // 预先获取字符串资源（在 @Composable 上下文中）
    val exportNoDataText = stringResource(R.string.export_no_data)
    val exportCsvSuccessText = stringResource(R.string.export_csv_success)
    val exportCsvFailedText = stringResource(R.string.export_csv_failed)
    val exportExcelSuccessText = stringResource(R.string.export_excel_success)
    val exportExcelFailedText = stringResource(R.string.export_excel_failed)
    
    Log.d("TransactionListScreen", "渲染交易列表页面，交易数量: ${transactions.size}")
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        // 标题栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.transaction_list_title),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onBack) {
                Text(
                    text = "✕",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        
        // 统计信息
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.transaction_count),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = transactionCount.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.total_amount),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = String.format("%.2f €", totalAmount),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        
        // 交易列表
        if (transactions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_transactions),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(transactions) { transaction ->
                    TransactionItem(transaction = transaction)
                }
            }
        }
        
        // 导出消息提示
        exportMessage?.let { message ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (message.contains("erfolgreich", ignoreCase = true) || 
                                         message.contains("successfully", ignoreCase = true)) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                )
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = if (message.contains("erfolgreich", ignoreCase = true) || 
                                 message.contains("successfully", ignoreCase = true)) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    }
                )
            }
        }
        
        // 导出 CSV 按钮
        Button(
            onClick = {
                if (transactions.isEmpty()) {
                    exportMessage = exportNoDataText
                    return@Button
                }
                isExportingCsv = true
                exportMessage = null
                coroutineScope.launch {
                    val result = viewModel.exportToCsv()
                    isExportingCsv = false
                    exportMessage = if (result != null) {
                        exportCsvSuccessText
                    } else {
                        exportCsvFailedText
                    }
                    // 3 秒后清除消息
                    kotlinx.coroutines.delay(3000)
                    exportMessage = null
                }
            },
            enabled = !isExportingCsv && !isExportingExcel && transactions.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .height(KioskButtonSizes.MediumButtonHeight)
                .padding(top = 8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiary
            )
        ) {
            if (isExportingCsv) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onTertiary,
                    strokeWidth = 3.dp
                )
            } else {
                Text(
                    text = stringResource(R.string.button_export_csv),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        
        // 导出 Excel 按钮
        Button(
            onClick = {
                if (transactions.isEmpty()) {
                    exportMessage = exportNoDataText
                    return@Button
                }
                isExportingExcel = true
                exportMessage = null
                coroutineScope.launch {
                    val result = viewModel.exportToExcel()
                    isExportingExcel = false
                    exportMessage = if (result != null) {
                        exportExcelSuccessText
                    } else {
                        exportExcelFailedText
                    }
                    // 3 秒后清除消息
                    kotlinx.coroutines.delay(3000)
                    exportMessage = null
                }
            },
            enabled = !isExportingCsv && !isExportingExcel && transactions.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .height(KioskButtonSizes.MediumButtonHeight)
                .padding(top = 8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            if (isExportingExcel) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    strokeWidth = 3.dp
                )
            } else {
                Text(
                    text = stringResource(R.string.button_export_excel),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        
        // 返回按钮
        Button(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(KioskButtonSizes.MediumButtonHeight)
                .padding(top = 8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        ) {
            Text(
                text = stringResource(R.string.button_back),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * 交易项
 */
@Composable
private fun TransactionItem(transaction: Transaction) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = transaction.programName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = String.format("%.2f €", transaction.amount),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatTimestamp(transaction.timestamp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = getPaymentMethodText(transaction.paymentMethod),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = getResultText(transaction.result),
                        style = MaterialTheme.typography.bodyMedium,
                        color = getResultColor(transaction.result)
                    )
                }
            }
        }
    }
}

/**
 * 格式化时间戳
 */
private fun formatTimestamp(timestamp: Long): String {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return dateFormat.format(Date(timestamp))
}

/**
 * 获取支付方式文本
 */
@Composable
private fun getPaymentMethodText(method: com.carwash.carpayment.data.PaymentMethod): String {
    return when (method) {
        com.carwash.carpayment.data.PaymentMethod.CARD -> stringResource(R.string.payment_card_short)
        com.carwash.carpayment.data.PaymentMethod.CASH -> stringResource(R.string.payment_cash_short)
    }
}

/**
 * 获取结果文本
 */
@Composable
private fun getResultText(result: TransactionResult): String {
    return when (result) {
        TransactionResult.SUCCESS -> stringResource(R.string.transaction_result_success)
        TransactionResult.FAILED -> stringResource(R.string.transaction_result_failed)
        TransactionResult.CANCELLED -> stringResource(R.string.transaction_result_cancelled)
    }
}

/**
 * 获取结果颜色
 */
@Composable
private fun getResultColor(result: TransactionResult): Color {
    return when (result) {
        TransactionResult.SUCCESS -> MaterialTheme.colorScheme.primary
        TransactionResult.FAILED -> MaterialTheme.colorScheme.error
        TransactionResult.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
