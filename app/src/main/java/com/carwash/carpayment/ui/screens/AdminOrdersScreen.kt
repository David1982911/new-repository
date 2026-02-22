package com.carwash.carpayment.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.carwash.carpayment.R
import com.carwash.carpayment.ui.viewmodel.AdminOrdersViewModel
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.sp

/**
 * Admin Orders 管理屏幕（V3.4 规范）
 */
@Composable
fun AdminOrdersScreen(
    viewModel: AdminOrdersViewModel,
    onBack: () -> Unit
) {
    val orders by viewModel.filteredOrders.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedOrder by viewModel.selectedOrder.collectAsState()
    
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
                text = stringResource(R.string.admin_orders_title),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.back))
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.setSearchQuery(it) },
            label = { Text(stringResource(R.string.search_orders)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = { /* 搜索已通过 onValueChange 触发 */ }
            )
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 订单列表
        if (orders.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.no_orders_found))
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(orders) { order ->
                    OrderItemCard(
                        order = order,
                        onClick = { viewModel.selectOrder(order) }
                    )
                }
            }
        }
    }
    
    // 订单详情对话框
    selectedOrder?.let { order ->
        OrderDetailDialog(
            order = order,
            onDismiss = { viewModel.clearSelection() }
        )
    }
}

/**
 * 订单项卡片
 */
@Composable
private fun OrderItemCard(
    order: com.carwash.carpayment.ui.viewmodel.OrderDisplayItem,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Order #${order.orderId}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = order.getFormattedAmount(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = order.programName,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = order.paymentMethod,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = order.getFormattedDateTime(),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // 状态标签
            Surface(
                color = when (order.state) {
                    "ORDER_COMPLETED" -> MaterialTheme.colorScheme.primaryContainer
                    "ORDER_FAILED" -> MaterialTheme.colorScheme.errorContainer
                    "ORDER_REFUNDED" -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = order.state,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontSize = 12.sp
                )
            }
        }
    }
}

/**
 * 订单详情对话框
 */
@Composable
private fun OrderDetailDialog(
    order: com.carwash.carpayment.ui.viewmodel.OrderDisplayItem,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${stringResource(R.string.order_details)} #${order.orderId}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DetailRow("Program", order.programName)
                DetailRow("Payment Method", order.paymentMethod)
                DetailRow("Amount", order.getFormattedAmount())
                DetailRow("Paid", order.getFormattedPaidAmount())
                if (order.changeCents > 0) {
                    DetailRow("Change", order.getFormattedChangeAmount())
                }
                DetailRow("State", order.state)
                DetailRow("Date/Time", order.getFormattedDateTime())
                order.failureReasonCode?.let {
                    DetailRow("Failure Reason", it)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$label:",
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp
        )
        Text(
            text = value,
            fontSize = 14.sp
        )
    }
}
