package com.carwash.carpayment.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carwash.carpayment.AppBuildMark
import com.carwash.carpayment.data.cashdevice.CurrencyAssignment
import com.carwash.carpayment.data.cashdevice.CashAmountTracker
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
    
    // 选项卡状态：0=纸币器, 1=硬币器
    var selectedTabIndex by remember { mutableStateOf(0) }

    Log.d(
        "DeviceTestScreen",
        "渲染设备测试屏幕，当前选项卡: ${if (selectedTabIndex == 0) "纸币器" else "硬币器"}"
    )
    
    // 页面可见性控制：进入页面时启动轮询，退出页面时停止轮询
    DisposableEffect(Unit) {
        viewModel.setScreenVisible(true)
        onDispose {
            viewModel.setScreenVisible(false)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // 标题栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "现金设备连通性测试",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = AppBuildMark.BUILD_MARK,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 选项卡
        TabRow(
            selectedTabIndex = selectedTabIndex,
            modifier = Modifier.fillMaxWidth()
        ) {
            Tab(
                selected = selectedTabIndex == 0,
                onClick = {
                    selectedTabIndex = 0
                    viewModel.switchToDevice(true) // 切换到纸币器
                },
                text = { Text("纸币器") }
            )
            Tab(
                selected = selectedTabIndex == 1,
                onClick = {
                    selectedTabIndex = 1
                    viewModel.switchToDevice(false) // 切换到硬币器
                },
                text = { Text("硬币器") }
            )
        }

        // 内容区域（根据选中的选项卡显示）
    Column(
        modifier = Modifier
            .fillMaxSize()
                .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            if (selectedTabIndex == 0) {
        // 纸币器测试区域
                val isEditMode by viewModel.isEditMode.collectAsState()
                val pendingRoutes by viewModel.pendingRoutes.collectAsState()
                
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
                    assignments = billState.assignments,
                    recentChanges = billState.recentChanges,
                    isBillAcceptor = true,
                    isEditMode = isEditMode,
                    pendingRoutes = pendingRoutes,
                    routeChanging = billState.routeChanging,
            onConnect = { viewModel.connectBillAcceptor() },
            onDisconnect = { viewModel.disconnectBillAcceptor() },
            onEnable = { viewModel.enableBillAcceptor() },
                    onDisable = { viewModel.disableBillAcceptor() },
                    onDispense = { valueCents -> viewModel.dispenseBill(valueCents) },
                    onToggleRecyclable = { value, isRecyclable ->
                        viewModel.toggleDenominationRecyclable(value, isRecyclable)
                    },
                    onToggleHostEnable = { value, isEnabled ->
                        viewModel.toggleDenominationEnabled(value, isEnabled)
                    },
                    onSmartEmpty = { viewModel.smartEmptyBill() },
                    onSetEditMode = { enabled -> viewModel.setEditMode(enabled) },
                    onApplyPendingRoutes = { viewModel.applyPendingRoutes(pendingRoutes) },
                    onCancelEdit = { viewModel.setEditMode(false) }
                )
            } else {
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
                    assignments = coinState.assignments,
                    recentChanges = coinState.recentChanges,
                    isBillAcceptor = false,
            onConnect = { viewModel.connectCoinAcceptor() },
            onDisconnect = { viewModel.disconnectCoinAcceptor() },
            onEnable = { viewModel.enableCoinAcceptor() },
                    onDisable = { viewModel.disableCoinAcceptor() },
                    onDispense = { valueCents -> viewModel.dispenseCoin(valueCents) },
                    onToggleRecyclable = { value, isRecyclable ->
                        // 硬币器也支持 Route To Payout（虽然通常硬币器不支持找零，但可以显示状态）
                        viewModel.toggleDenominationRecyclableCoin(value, isRecyclable)
                    },
                    onToggleHostEnable = { value, isEnabled ->
                        // 硬币器也支持 Host Enable
                        viewModel.toggleDenominationEnabledCoin(value, isEnabled)
                    },
                    onSmartEmpty = { viewModel.smartEmptyCoin() }  // 硬币器支持 Smart Empty
                )
            }

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

            // 测试日志区域（修复：Card 正确闭合，不再把返回按钮塞进 Card 里）
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
        
            // 返回按钮（放在日志 Card 外面）
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

            Spacer(modifier = Modifier.height(8.dp))
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
    assignments: List<CurrencyAssignment>,
    recentChanges: List<CashAmountTracker.AmountChange>,
    isBillAcceptor: Boolean, // true=纸币器（支持路由切换），false=硬币器（不支持）
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onDispense: (Int) -> Unit,
    onToggleRecyclable: ((Int, Boolean) -> Unit)?,
    onToggleHostEnable: ((Int, Boolean) -> Unit)? = null,  // 切换 Host Enable（仅纸币器）
    onSmartEmpty: (() -> Unit)?,
    isEditMode: Boolean = false,  // 编辑模式（仅纸币器）
    pendingRoutes: Map<Int, Boolean> = emptyMap(),  // 待应用的路由变更（仅纸币器）
    routeChanging: Map<Int, Boolean> = emptyMap(),  // 正在切换路由的面额（仅纸币器）
    onSetEditMode: ((Boolean) -> Unit)? = null,  // 设置编辑模式（仅纸币器）
    onApplyPendingRoutes: (() -> Unit)? = null,  // 应用待应用的路由变更（仅纸币器）
    onCancelEdit: (() -> Unit)? = null  // 取消编辑（仅纸币器）
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
            // 标题和连接状态
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
            
            if (deviceID != null) {
                Text(
                    text = "DeviceID: $deviceID",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            
            // 事件统计 + 收款启用状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "事件计数: $eventCount",
                    style = MaterialTheme.typography.bodyMedium
                )

                    Surface(
                    color = if (isEnabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                        text = if (isEnabled) "允许收款" else "禁止收款",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        color = if (isEnabled) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onError,
                            fontSize = 14.sp
                        )
                    }
                }

            if (lastStatus != null) {
                Text(
                    text = "状态: $lastStatus",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (lastEvent != null) {
                Text(
                    text = "最后事件: $lastEvent",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            HorizontalDivider()

            // 实时金额
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
                        text = "本次会话已收金额: ${String.format("%.2f", sessionAmount)} € (${sessionAmountCents} 分)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "设备库存金额（总）: ${String.format("%.2f", totalAmount)} € (${totalAmountCents} 分)",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    if (recentChanges.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            text = "最近变化:",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        recentChanges.forEach { change ->
                            Text(
                                text = change.getDisplayText(),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (change.count > 0)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // 面额列表
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
                    // 面额列表标题 + 编辑模式开关（仅纸币器）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "面额列表",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (isBillAcceptor && onSetEditMode != null) {
                            Button(
                                onClick = { onSetEditMode(!isEditMode) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isEditMode)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Text(
                                    text = if (isEditMode) "退出编辑" else "编辑路由",
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                    
                    // 编辑模式提示和批量操作条
                    if (isEditMode && isBillAcceptor && pendingRoutes.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "待应用变更: ${pendingRoutes.size} 项",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (onApplyPendingRoutes != null) {
                                        Button(
                                            onClick = onApplyPendingRoutes,
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primary
                                            )
                                        ) {
                                            Text("应用设置 (${pendingRoutes.size}项变更)", fontSize = 12.sp)
                                        }
                                    }
                                    if (onCancelEdit != null) {
                                        Button(
                                            onClick = onCancelEdit,
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                                            )
                                        ) {
                                            Text("取消", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    if (assignments.isEmpty()) {
                        Text(
                            text = "无面额数据",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // Windows 风格矩阵展示
                        // 顶部：面额列标题
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // 第一列：行标签（固定宽度）
                            Box(
                                modifier = Modifier.width(120.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            // 面额列
                            assignments.forEach { assignment ->
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${assignment.value}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        
                        // 行1：Host Enable（是否允许接收该面额）
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 行标签
                            Box(
                                modifier = Modifier.width(120.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = "Host Enable",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            // 每个面额的 Host Enable 状态（可点击切换，仅纸币器）
                            assignments.forEach { assignment ->
                                val isHostEnabled = !assignment.isInhibited  // IsInhibited=false 表示允许接收
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (onToggleHostEnable != null) {
                                        // 可点击切换（纸币器和硬币器都支持）
                                        Button(
                                            onClick = {
                                                Log.d(
                                                    "DeviceTestScreen",
                                                    "MARK ==== HOST ENABLE CLICK value=${assignment.value} country=${assignment.countryCode ?: "N/A"} deviceID=$deviceID newEnabled=${!isHostEnabled} ===="
                                                )
                                                onToggleHostEnable(assignment.value, !isHostEnabled)
                                            },
                                            modifier = Modifier.size(40.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isHostEnabled)
                                                    MaterialTheme.colorScheme.primary
                                                else
                                                    MaterialTheme.colorScheme.error
                                            ),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text(
                                                text = if (isHostEnabled) "✅" else "❌",
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isHostEnabled)
                                                    MaterialTheme.colorScheme.onPrimary
                                                else
                                                    MaterialTheme.colorScheme.onError
                                            )
                                        }
                                    } else {
                                        // 只读显示（如果未提供回调）
                                        Text(
                                            text = if (isHostEnabled) "✅" else "❌",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontSize = 20.sp
                                        )
                                    }
                                }
                            }
                        }
                        
                        // 行2：Route To Payout（是否可找零）
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 行标签
                            Box(
                                modifier = Modifier.width(120.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = "Route To Payout",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            // 每个面额的 Route To Payout 状态（可点击切换，仅纸币器）
                            assignments.forEach { assignment ->
                                val pendingRecyclable = pendingRoutes[assignment.value]
                                // 根据 AcceptRoute 判断：RECYCLER/PAYOUT → ✅，CASHBOX → ❌
                                val currentRecyclable = when {
                                    assignment.acceptRoute == "PAYOUT" || assignment.acceptRoute == "RECYCLER" -> true
                                    assignment.acceptRoute == "CASHBOX" -> false
                                    else -> assignment.isAcceptRouteRecyclable  // 兜底：使用 isAcceptRouteRecyclable
                                }
                                val isRecyclable = if (isEditMode && pendingRecyclable != null) {
                                    pendingRecyclable
                                } else {
                                    currentRecyclable
                                }
                                val isRouteChanging = routeChanging[assignment.value] == true
                                
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (onToggleRecyclable != null) {
                                        // 可点击切换（纸币器和硬币器都支持）
                                        Button(
                                            onClick = {
                                                Log.d(
                                                    "DeviceTestScreen",
                                                    "MARK ==== TOGGLE ROUTE CLICKED deviceID=$deviceID value=${assignment.value} country=${assignment.countryCode ?: "N/A"} channel=${assignment.channel ?: "N/A"} newRecyclable=${!isRecyclable} ===="
                                                )
                                                onToggleRecyclable(assignment.value, !isRecyclable)
                                            },
                                            enabled = !isRouteChanging,  // loading 时禁用按钮
                                            modifier = Modifier.size(40.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isRecyclable)
                                                    MaterialTheme.colorScheme.primary
                                                else
                                                    MaterialTheme.colorScheme.error
                                            ),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            if (isRouteChanging) {
                                                Text(
                                                    text = "...",
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            } else {
                                                Text(
                                                    text = if (isRecyclable) "✅" else "❌",
                                                    fontSize = 20.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isRecyclable)
                                                        MaterialTheme.colorScheme.onPrimary
                                                    else
                                                        MaterialTheme.colorScheme.onError
                                                )
                                            }
                                        }
                                    } else {
                                        // 只读显示（如果未提供回调）
                                        Text(
                                            text = if (isRecyclable) "✅" else "❌",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontSize = 20.sp
                                        )
                                    }
                                }
                            }
                        }
                        
                        // 详细信息（可选，折叠显示）
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            text = "详细信息",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                        assignments.forEach { assignment ->
                            val countryCodeText = assignment.countryCode ?: "EUR"
                            val storedText = if (isBillAcceptor && assignment.storedInCashbox > 0) {
                                "Stored: ${assignment.stored} (主钞箱: ${assignment.storedInCashbox}, 循环鼓: ${assignment.storedInRecycler})"
                            } else {
                                "Stored: ${assignment.stored}"
                            }
                            Text(
                                text = "${assignment.value} $countryCodeText | $storedText | 通道: ${assignment.channel ?: "N/A"}",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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

                        // 找零状态（高对比）- 设备连接成功后自动启用
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "🟢 找零已启用（设备连接后自动启用）",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // SmartEmpty 按钮（纸币器和硬币器都支持）
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (onSmartEmpty != null) {
                                Button(
                                    onClick = {
                                        Log.d("DeviceTestScreen", "MARK ==== SMART EMPTY CLICK deviceID=$deviceID ====")
                                        onSmartEmpty()
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondary
                                    )
                                ) {
                                    Text("SmartEmpty", fontSize = 11.sp)
                                }
                            }
                        }

                        // 测试找零输入框（输入元，转换为分）
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextField(
                                value = dispenseAmountText,
                                onValueChange = {
                                    // 允许输入数字和小数点（用于输入元，如 20.5）
                                    if (it.all { ch -> ch.isDigit() || ch == '.' }) {
                                        dispenseAmountText = it
                                    }
                                },
                                label = { Text("找零金额（元，如 20）") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                placeholder = { Text("例如：20") }
                            )
                            Button(
                                onClick = {
                                    // 将元转换为分：20 EUR -> 2000 cents
                                    val amountEur = dispenseAmountText.toDoubleOrNull()
                                    if (amountEur != null && amountEur > 0) {
                                        val amountCents = (amountEur * 100).toInt()
                                        Log.d("DeviceTestScreen", "UI: 点击找零按钮, input=$amountEur EUR -> $amountCents cents")
                                        onDispense(amountCents)
                                        dispenseAmountText = ""
                                    }
                                },
                                enabled = {
                                    val amountEur = dispenseAmountText.toDoubleOrNull()
                                    amountEur != null && amountEur > 0
                                }()
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
                            ) { Text("1€") }

                            Button(
                                onClick = { onDispense(200) },
                                modifier = Modifier.weight(1f)
                            ) { Text("2€") }

                            Button(
                                onClick = { onDispense(500) },
                                modifier = Modifier.weight(1f)
                            ) { Text("5€") }
                        }
                    }
                }
            }

            // 底部按钮组：连接/断开 + 收款允许/禁止
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!isConnected) {
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.weight(1f),
                        enabled = true
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
                        Text(if (isEnabled) "禁止收款" else "允许收款")
                    }
                }
            }
        }
    }
}
