package com.carwash.carpayment.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextField
import androidx.compose.ui.text.input.KeyboardType
import com.carwash.carpayment.AppBuildMark
import com.carwash.carpayment.data.cashdevice.LevelEntry
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
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        // Build 标记（用于确认设备上运行的是否是最新 APK）
        Text(
            text = AppBuildMark.BUILD_MARK,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            lastStatus = billState.lastStatus,
            sessionAmountCents = billState.sessionAmountCents,
            sessionAmount = billState.sessionAmount,
            totalAmountCents = billState.totalAmountCents,
            totalAmount = billState.totalAmount,
            levels = billState.levels,
            isPayoutEnabled = billState.isPayoutEnabled,
            onConnect = { viewModel.connectBillAcceptor() },
            onDisconnect = { viewModel.disconnectBillAcceptor() },
            onEnable = { viewModel.enableBillAcceptor() },
            onDisable = { viewModel.disableBillAcceptor() },
            onEnablePayout = { viewModel.enablePayoutBill() },
            onDisablePayout = { viewModel.disablePayoutBill() },
            onDispense = { valueCents -> viewModel.dispenseBill(valueCents) }
        )
        
        // 硬币器测试区域
        DeviceTestCard(
            title = "硬币器 (SSP=16)",
            deviceID = coinState.deviceID,
            isConnected = coinState.isConnected,
            isEnabled = coinState.isEnabled,
            eventCount = coinState.eventCount,
            lastEvent = coinState.lastEvent,
            lastStatus = coinState.lastStatus,
            sessionAmountCents = coinState.sessionAmountCents,
            sessionAmount = coinState.sessionAmount,
            totalAmountCents = coinState.totalAmountCents,
            totalAmount = coinState.totalAmount,
            levels = coinState.levels,
            isPayoutEnabled = coinState.isPayoutEnabled,
            onConnect = { viewModel.connectCoinAcceptor() },
            onDisconnect = { viewModel.disconnectCoinAcceptor() },
            onEnable = { viewModel.enableCoinAcceptor() },
            onDisable = { viewModel.disableCoinAcceptor() },
            onEnablePayout = { viewModel.enablePayoutCoin() },
            onDisablePayout = { viewModel.disablePayoutCoin() },
            onDispense = { valueCents -> viewModel.dispenseCoin(valueCents) }
        )
        
        // 开始新会话按钮
        Button(
            onClick = { viewModel.startNewSession() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiary
            )
        ) {
            Text(
                text = "开始新会话（清零本次投入金额）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        
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
    lastStatus: String?,
    sessionAmountCents: Int,
    sessionAmount: Double,
    totalAmountCents: Int,
    totalAmount: Double,
    levels: List<LevelEntry>,
    isPayoutEnabled: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onEnablePayout: () -> Unit,
    onDisablePayout: () -> Unit,
    onDispense: (Int) -> Unit
) {
    var dispenseAmountText by remember { mutableStateOf("") }
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
            
            // 设备状态
            if (lastStatus != null) {
                Text(
                    text = "状态: $lastStatus",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            // 最后事件
            if (lastEvent != null) {
                Text(
                    text = "最后事件: $lastEvent",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            HorizontalDivider()
            
            // 实时金额显示（本次投入金额 sessionDelta + 设备总库存金额 total）
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "实时金额",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "本次投入: ${String.format("%.2f", sessionAmount)} € (${sessionAmountCents} 分)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "设备总库存: ${String.format("%.2f", totalAmount)} € (${totalAmountCents} 分)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            
            // 库存明细（按面额列表展示 Value + Stored）
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "库存明细",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (levels.isEmpty()) {
                        Text(
                            text = "无库存数据",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        levels.forEach { level ->
                            val amount = level.value * level.stored / 100.0
                            Text(
                                text = "面额 ${level.value} 分: ${level.stored} 张/枚 (${String.format("%.2f", amount)} €)${if (level.countryCode != null) " [${level.countryCode}]" else ""}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        // 如果是纸币器，显示钞箱提示
                        if (title.contains("纸币器")) {
                            Text(
                                text = "注意：当前 API 未区分钞箱（主钞箱/循环钞箱），仅显示总库存",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
            
            // 找零功能区
            if (isConnected) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "找零功能",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        
                        // 启用/禁用找零按钮
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = if (isPayoutEnabled) onDisablePayout else onEnablePayout,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isPayoutEnabled)
                                        MaterialTheme.colorScheme.error
                                    else
                                        MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text(if (isPayoutEnabled) "禁用找零" else "启用找零")
                            }
                        }
                        
                        // 找零状态
                        Text(
                            text = "找零状态: ${if (isPayoutEnabled) "已启用" else "已禁用"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        
                        // 测试找零输入框
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextField(
                                value = dispenseAmountText,
                                onValueChange = { 
                                    // 只允许输入数字
                                    if (it.all { it.isDigit() }) {
                                        dispenseAmountText = it
                                    }
                                },
                                label = { Text("找零金额（分）") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true
                            )
                            Button(
                                onClick = {
                                    val amount = dispenseAmountText.toIntOrNull()
                                    if (amount != null && amount > 0) {
                                        onDispense(amount)
                                        dispenseAmountText = ""
                                    }
                                },
                                enabled = dispenseAmountText.toIntOrNull() != null && dispenseAmountText.toIntOrNull()!! > 0
                            ) {
                                Text("测试找零")
                            }
                        }
                        
                        // 快捷找零按钮（1€/2€/5€）
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onDispense(100) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("1€")
                            }
                            Button(
                                onClick = { onDispense(200) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("2€")
                            }
                            Button(
                                onClick = { onDispense(500) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("5€")
                            }
                        }
                    }
                }
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
