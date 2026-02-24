package com.carwash.carpayment.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carwash.carpayment.AppBuildMark
import com.carwash.carpayment.R
import android.os.Process
import com.carwash.carpayment.data.cashdevice.CurrencyAssignment
import com.carwash.carpayment.data.cashdevice.CashAmountTracker
import com.carwash.carpayment.data.WashProgram
import com.carwash.carpayment.ui.viewmodel.CashDeviceTestViewModel
import com.carwash.carpayment.ui.viewmodel.PrinterTabViewModel
import com.carwash.carpayment.ui.viewmodel.WashModeTestViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 现金设备测试屏幕
 * 用于测试纸币器（SSP=0）和硬币器（SSP=16）的连通性
 */
@Composable
fun DeviceTestScreen(
    viewModel: CashDeviceTestViewModel,
    printerTabViewModel: PrinterTabViewModel = viewModel(),
    onBack: () -> Unit,
    onExitApp: () -> Unit
) {
    val billState by viewModel.billAcceptorState.collectAsState()
    val coinState by viewModel.coinAcceptorState.collectAsState()
    val logs by viewModel.testLogs.collectAsState()
    
    // 选项卡状态：0=纸币器, 1=硬币器, 2=打印机
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
                text = stringResource(R.string.device_test_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = AppBuildMark.BUILD_MARK,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 退出APP按钮
                Button(
                    onClick = {
                        Log.d("DeviceTestScreen", "退出APP按钮被点击")
                        onExitApp()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(
                        text = stringResource(R.string.device_test_exit_app),
                        fontSize = 12.sp
                    )
                }
            }
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
                text = { Text(stringResource(R.string.device_test_bill_acceptor)) }
            )
            Tab(
                selected = selectedTabIndex == 1,
                onClick = {
                    selectedTabIndex = 1
                    viewModel.switchToDevice(false) // 切换到硬币器
                },
                text = { Text(stringResource(R.string.device_test_coin_acceptor)) }
            )
            Tab(
                selected = selectedTabIndex == 2,
                onClick = {
                    selectedTabIndex = 2
                },
                text = { Text(stringResource(R.string.device_test_printer)) }
            )
            Tab(
                selected = selectedTabIndex == 3,
                onClick = {
                    selectedTabIndex = 3
                },
                text = { Text(stringResource(R.string.device_test_wash_mode)) }
            )
        }

        // 内容区域（根据选中的选项卡显示）
    Column(
        modifier = Modifier
            .fillMaxSize()
                .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            if (selectedTabIndex == 2) {
                // 打印机测试区域
                PrinterTab(viewModel = printerTabViewModel)
            } else if (selectedTabIndex == 3) {
                // Wash Mode 测试区域
                Box(modifier = Modifier.fillMaxSize()) {
                    WashModeTab()
                }
            } else if (selectedTabIndex == 0) {
        // 纸币器测试区域
                val isEditMode by viewModel.isEditMode.collectAsState()
                val pendingRoutes by viewModel.pendingRoutes.collectAsState()
                
        DeviceTestCard(
            title = "${stringResource(R.string.device_test_bill_acceptor)} (SSP=0)",
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
                    baselineCents = billState.baselineCents,
                    baselineAmount = billState.baselineAmount,
                    currentCents = billState.currentCents,
                    currentAmount = billState.currentAmount,
                    deltaCents = billState.deltaCents,
                    deltaAmount = billState.deltaAmount,
                    assignments = billState.assignments,
                    recentChanges = billState.recentChanges,
                    isBillAcceptor = true,
                    isEditMode = isEditMode,
                    pendingRoutes = pendingRoutes,
                    routeChanging = billState.routeChanging,
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
                    onCancelEdit = { viewModel.setEditMode(false) },
                    onResetBaseline = { deviceID -> viewModel.resetSessionBaseline(deviceID) }
                )
            } else {
        // 硬币器测试区域
        DeviceTestCard(
            title = "${stringResource(R.string.device_test_coin_acceptor)} (SSP=16)",
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
                    baselineCents = coinState.baselineCents,
                    baselineAmount = coinState.baselineAmount,
                    currentCents = coinState.currentCents,
                    currentAmount = coinState.currentAmount,
                    deltaCents = coinState.deltaCents,
                    deltaAmount = coinState.deltaAmount,
                    assignments = coinState.assignments,
                    recentChanges = coinState.recentChanges,
                    isBillAcceptor = false,
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
                    onSmartEmpty = { viewModel.smartEmptyCoin() },  // 硬币器支持 Smart Empty
                    onResetBaseline = { deviceID -> viewModel.resetSessionBaseline(deviceID) }
                )
            }

            // 测试日志区域（仅现金设备 Tab 显示）
            if (selectedTabIndex != 2 && selectedTabIndex != 3) {
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
                    text = stringResource(R.string.device_test_logs),
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
            }
        
            // 返回按钮
        Button(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
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
    baselineCents: Int = 0,        // 会话基线金额（分）
    baselineAmount: Double = 0.0,  // 会话基线金额（元）
    currentCents: Int = 0,         // 当前总收款金额（分）
    currentAmount: Double = 0.0,   // 当前总收款金额（元）
    deltaCents: Int = 0,           // 会话增量金额（分）
    deltaAmount: Double = 0.0,     // 会话增量金额（元）
    assignments: List<CurrencyAssignment>,
    recentChanges: List<CashAmountTracker.AmountChange>,
    isBillAcceptor: Boolean, // true=纸币器（支持路由切换），false=硬币器（不支持）
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
    onCancelEdit: (() -> Unit)? = null,  // 取消编辑（仅纸币器）
    onResetBaseline: ((String) -> Unit)? = null  // 重置会话基线（deviceID）
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
            }
            
            if (deviceID != null) {
                Text(
                    text = stringResource(R.string.device_test_device_id, deviceID),
                    style = MaterialTheme.typography.bodyMedium
                )
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
                            text = stringResource(R.string.device_test_denomination_list),
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
                                    text = if (isEditMode) stringResource(R.string.device_test_exit_edit) else stringResource(R.string.device_test_edit_route),
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
                                    text = stringResource(R.string.device_test_pending_changes, pendingRoutes.size),
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
                                            Text(stringResource(R.string.device_test_apply_settings, pendingRoutes.size), fontSize = 12.sp)
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
                                            Text(stringResource(R.string.device_test_cancel), fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    if (assignments.isEmpty()) {
                        Text(
                            text = stringResource(R.string.device_test_no_denomination_data),
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
                                    text = stringResource(R.string.device_test_host_enable),
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
                                    text = stringResource(R.string.device_test_route_to_payout),
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
                            text = stringResource(R.string.device_test_dispense),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )

                        // 找零状态（高对比）- 设备连接成功后自动启用
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "🟢 ${stringResource(R.string.device_test_dispense_enabled)}",
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
                                    Text(stringResource(R.string.device_test_smart_empty), fontSize = 11.sp)
                                }
                            }
                            if (onResetBaseline != null && deviceID != null) {
                                Button(
                                    onClick = {
                                        Log.d("DeviceTestScreen", "MARK ==== RESET BASELINE CLICK deviceID=$deviceID ====")
                                        onResetBaseline(deviceID)
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiary
                                    )
                                ) {
                                    Text(stringResource(R.string.device_test_reset_baseline), fontSize = 11.sp)
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
                                label = { Text(stringResource(R.string.device_test_dispense_amount)) },
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
                                Text(stringResource(R.string.device_test_dispense_test))
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

            // 底部按钮组：收款允许/禁止（连接/断开按钮已删除，设备在APP启动时自动连接）
            if (isConnected) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
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
                        Text(if (isEnabled) stringResource(R.string.device_test_disabled) else stringResource(R.string.device_test_enabled))
                    }
                }
            }
        }
    }
}

/**
 * Wash Mode Tab Component
 */
@Composable
fun WashModeTab(
    viewModel: WashModeTestViewModel = viewModel()
) {
    val programs by viewModel.programs.collectAsState()
    var editingProgram by remember { mutableStateOf<WashProgram?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.device_test_wash_mode),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        if (programs.isEmpty()) {
            Text("暂无洗车模式")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(programs) { program ->
                    WashModeItem(
                        program = program,
                        onEdit = { editingProgram = program },
                        onDelete = { showDeleteConfirm = program.id }
                    )
                }
            }
        }
    }

    // 编辑对话框
    if (editingProgram != null) {
        EditProgramDialog(
            program = editingProgram!!,
            onDismiss = { editingProgram = null },
            onSave = { updatedProgram -> 
                viewModel.updateProgram(updatedProgram)
                editingProgram = null
            }
        )
    }

    // 删除确认对话框
    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("确认删除") },
            text = { Text("确定要删除此洗车模式吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm?.let { programId ->
                            viewModel.deleteProgram(programId)
                        }
                        showDeleteConfirm = null
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun WashModeItem(
    program: WashProgram,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = program.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "价格: ${program.price}€ (${(program.price * 100).toInt()}分)",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onEdit,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("编辑")
                }
                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("删除")
                }
            }
        }
    }
}

@Composable
fun EditProgramDialog(
    program: WashProgram,
    onDismiss: () -> Unit,
    onSave: (WashProgram) -> Unit
) {
    var name by remember { mutableStateOf(program.name) }
    var priceText by remember { mutableStateOf(program.price.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑洗车模式") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = { Text("价格 (€)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val price = priceText.toDoubleOrNull()
                    if (name.isNotBlank() && price != null && price > 0) {
                        onSave(
                            program.copy(
                                name = name,
                                price = price
                            )
                        )
                    }
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
