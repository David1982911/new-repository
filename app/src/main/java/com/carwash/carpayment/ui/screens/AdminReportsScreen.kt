package com.carwash.carpayment.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.carwash.carpayment.R
import com.carwash.carpayment.ui.viewmodel.AdminReportsViewModel
import androidx.compose.ui.unit.sp

/**
 * Admin Reports 管理屏幕（V3.4 规范）
 */
@Composable
fun AdminReportsScreen(
    viewModel: AdminReportsViewModel,
    onBack: () -> Unit
) {
    val reportStats by viewModel.reportStats.collectAsState()
    
    LaunchedEffect(Unit) {
        viewModel.loadReportStats()
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 标题栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.admin_reports_title),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.back))
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        if (reportStats == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            // 订单统计
            ReportCard(
                title = stringResource(R.string.order_statistics),
                items = listOf(
                    stringResource(R.string.total_orders) to reportStats!!.totalOrders.toString(),
                    stringResource(R.string.paid_orders) to reportStats!!.paidOrders.toString(),
                    stringResource(R.string.cancelled_orders) to reportStats!!.cancelledOrders.toString(),
                    stringResource(R.string.failed_orders) to reportStats!!.failedOrders.toString()
                )
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 金额统计
            ReportCard(
                title = stringResource(R.string.revenue_statistics),
                items = listOf(
                    stringResource(R.string.total_revenue) to reportStats!!.getFormattedTotalRevenue(),
                    stringResource(R.string.cash_total) to reportStats!!.getFormattedCashTotal(),
                    stringResource(R.string.pos_total) to reportStats!!.getFormattedPosTotal()
                )
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 导出按钮（占位）
            Button(
                onClick = { /* TODO: 实现导出功能 */ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.export_to_csv))
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = { /* TODO: 实现导出功能 */ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.export_to_pdf))
            }
        }
    }
}

/**
 * 报表卡片
 */
@Composable
private fun ReportCard(
    title: String,
    items: List<Pair<String, String>>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            items.forEach { (label, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = label,
                        fontSize = 14.sp
                    )
                    Text(
                        text = value,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
