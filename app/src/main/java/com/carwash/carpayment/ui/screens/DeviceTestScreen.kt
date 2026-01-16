package com.carwash.carpayment.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carwash.carpayment.ui.theme.KioskButtonSizes
import com.carwash.carpayment.ui.viewmodel.CashDeviceTestViewModel

/**
 * 现金设备测试屏幕
 * 用于测试纸币器（SSP=0）和硬币器（SSP=16）的连通性
 */
@Composable
fun DeviceTestScreen(
    viewModel: CashDeviceTestViewModel,
    onBack: () -> Unit
) {
    val billState by viewModel.billAcceptorState.collectAsState()
    val coinState by viewModel.coinAcceptorState.collectAsState()
    val logs by viewModel.testLogs.collectAsState()
    
    Log.d("DeviceTestScreen", "渲染设备测试屏幕")
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // 标题
        Text(
            text = "现金设备连通性测试",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // 纸币器测试区域
        DeviceTestCard(
            title = "纸币器 (SSP=0)",
            deviceID = billState.deviceID,
            isConnected = billState.isConnected,
            isEnabled = billState.isEnabled,
            eventCount = billState.eventCount,
            lastEvent = billState.lastEvent,
            onConnect = { viewModel.connectBillAcceptor() },
            onDisconnect = { viewModel.disconnectBillAcceptor() },
            onEnable = { viewModel.enableBillAcceptor() },
            onDisable = { viewModel.disableBillAcceptor() }
        )
        
        // 硬币器测试区域
        DeviceTestCard(
            title = "硬币器 (SSP=16)",
            deviceID = coinState.deviceID,
            isConnected = coinState.isConnected,
            isEnabled = coinState.isEnabled,
            eventCount = coinState.eventCount,
            lastEvent = coinState.lastEvent,
            onConnect = { viewModel.connectCoinAcceptor() },
            onDisconnect = { viewModel.disconnectCoinAcceptor() },
            onEnable = { viewModel.enableCoinAcceptor() },
            onDisable = { viewModel.disableCoinAcceptor() }
        )
        
        // 测试日志区域
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text(
                    text = "测试日志",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    logs.forEach { log ->
                        Text(
                            text = log,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
        
        // 返回按钮
        Button(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(KioskButtonSizes.MediumButtonHeight),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        ) {
            Text(
                text = "返回",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * 设备测试卡片
 */
@Composable
private fun DeviceTestCard(
    title: String,
    deviceID: String?,
    isConnected: Boolean,
    isEnabled: Boolean,
    eventCount: Int,
    lastEvent: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 标题和状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    color = if (isConnected) 
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.error,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = if (isConnected) "已连接" else "未连接",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 14.sp
                    )
                }
            }
            
            // 设备ID
            if (deviceID != null) {
                Text(
                    text = "DeviceID: $deviceID",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            
            // 事件统计
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "事件计数: $eventCount",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (isEnabled) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondary,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "已启用",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSecondary,
                            fontSize = 14.sp
                        )
                    }
                }
            }
            
            // 最后事件
            if (lastEvent != null) {
                Text(
                    text = "最后事件: $lastEvent",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            // 按钮组
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!isConnected) {
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.weight(1f),
                        enabled = !isConnected
                    ) {
                        Text("连接")
                    }
                } else {
                    Button(
                        onClick = onDisconnect,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("断开")
                    }
                    
                    Button(
                        onClick = if (isEnabled) onDisable else onEnable,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isEnabled) 
                                MaterialTheme.colorScheme.error 
                            else 
                                MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(if (isEnabled) "禁用" else "启用")
                    }
                }
            }
        }
    }
}
