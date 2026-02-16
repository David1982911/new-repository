package com.carwash.carpayment.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.activity.compose.BackHandler
import com.carwash.carpayment.R
import com.carwash.carpayment.data.PaymentMethod
import com.carwash.carpayment.ui.theme.KioskButtonSizes
import com.carwash.carpayment.ui.viewmodel.PaymentViewModel

/**
 * 页面2: 选择支付方式
 */
@Composable
fun SelectPaymentScreen(
    viewModel: PaymentViewModel,
    onPay: () -> Unit,
    onBackToHome: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val flowState by viewModel.flowState.collectAsState()
    val noteRejectionMessage by viewModel.noteRejectionMessage.collectAsState()
    val selectedProgram = state.selectedProgram
    
    // Snackbar 宿主状态
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    
    // ⚠️ 关键修复：取消确认对话框状态（基于状态机状态）
    val showCancelConfirmDialog = flowState.status == com.carwash.carpayment.data.payment.PaymentFlowStatus.SHOW_CANCEL_CONFIRM
    val isRefunding = flowState.status == com.carwash.carpayment.data.payment.PaymentFlowStatus.CANCELLED_REFUNDING
    
    // ⚠️ V3.3 规范：根据订单状态控制返回键
    // 订单生成后（进入 PAYING），禁止返回
    // 订单创建以后，在订单未结束以前，不允许返回首页等操作
    val backEnabled = flowState.isBackEnabled && !showCancelConfirmDialog
    
    // ⚠️ 关键修复：取消确认对话框显示时，禁止返回键关闭（避免误操作）
    BackHandler(enabled = showCancelConfirmDialog) {
        Log.d("SelectPaymentScreen", "取消确认对话框显示时，返回键被拦截（禁止关闭）")
        // 不执行任何操作，保持对话框显示
    }
    
    // ⚠️ V3.3 规范：根据 isBackEnabled 控制返回键
    // 如果返回被禁用，拦截返回键（不执行任何操作）
    BackHandler(enabled = !backEnabled && !showCancelConfirmDialog) {
        Log.w("SelectPaymentScreen", "❌ 订单处于终态或已创建但未结束，返回键被拦截")
        Log.w("SelectPaymentScreen", "当前状态: ${flowState.status}, isBackEnabled=$backEnabled")
        // 可选：给用户一个提示（例如 Toast 或 Snackbar）
    }
    
    // ⚠️ SUCCESS 状态下：BackHandler 直接导航回 Home 并清栈，不调用 cancelPaymentAndReturnHome（避免重置状态）
    // 注意：SUCCESS 状态下 isBackEnabled=false，所以这个 BackHandler 实际上不会触发
    // 但保留此逻辑作为备用（如果未来规范允许 SUCCESS 状态下返回）
    BackHandler(enabled = flowState.status == com.carwash.carpayment.data.payment.PaymentFlowStatus.SUCCESS && backEnabled && !showCancelConfirmDialog) {
        Log.d("SelectPaymentScreen", "SUCCESS 状态下 BackHandler：直接导航回 Home 并清栈")
        onBackToHome()
    }
    
    // 监听拒收消息并显示大字体对话框
    LaunchedEffect(noteRejectionMessage) {
        noteRejectionMessage?.let { hint ->
            Log.w("SelectPaymentScreen", "显示拒收提示: messageKey=${hint.messageKey}, acceptedDenominations=${hint.acceptedDenominations}")
            // 5秒后自动清除（由 ViewModel 控制）
        }
    }
    
    Log.d("SelectPaymentScreen", "渲染支付页面，已选程序: ${selectedProgram?.id}, 已选支付: ${state.selectedPaymentMethod}")
    
    // ⚠️ 关键修复：如果 selectedProgram 为 null，显示提示并导航回首页，而不是白屏
    if (selectedProgram == null) {
        Log.w("SelectPaymentScreen", "⚠️ 未选择程序，导航回首页（避免白屏）")
        // 显示提示信息并提供返回按钮
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "请选择套餐或返回首页",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
                Button(
                    onClick = {
                        Log.d("SelectPaymentScreen", "返回首页（未选择程序）")
                        onBackToHome()
                    }
                ) {
                    Text(stringResource(R.string.button_back))
                }
            }
        }
        // 自动导航回首页（延迟执行，避免立即导航导致 UI 闪烁）
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(100)
            onBackToHome()
        }
        return
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
        // 返回首页按钮（顶部，始终显示）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            Button(
                onClick = {
                    Log.d("SelectPaymentScreen", "返回首页")
                    // ⚠️ V3.3 规范：根据 isBackEnabled 控制返回操作
                    if (!backEnabled) {
                        Log.w("SelectPaymentScreen", "❌ 订单处于终态或已创建但未结束，不允许返回首页")
                        Log.w("SelectPaymentScreen", "当前状态: ${flowState.status}, isBackEnabled=$backEnabled")
                        return@Button
                    }
                    
                    // ⚠️ SUCCESS 状态下：直接导航回 Home 并清栈，不调用 cancelPaymentAndReturnHome（避免重置状态）
                    if (flowState.status == com.carwash.carpayment.data.payment.PaymentFlowStatus.SUCCESS) {
                        Log.d("SelectPaymentScreen", "SUCCESS 状态下返回：直接导航回 Home 并清栈")
                        onBackToHome()
                    } else if (flowState.status == com.carwash.carpayment.data.payment.PaymentFlowStatus.CANCELLED_REFUNDING) {
                        // 退款中，禁用按钮
                        Log.d("SelectPaymentScreen", "退款中，禁止取消")
                    } else {
                        // ⚠️ 关键修复：统一取消入口
                        viewModel.onUserCancelRequested(source = "PAYMENT")
                    }
                },
                enabled = backEnabled && !isRefunding,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text(
                    text = if (isRefunding) "Refunding..." else stringResource(R.string.button_back),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        
        // 标题（KIOSK 大字号）
        Text(
            text = stringResource(R.string.select_payment_title),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // 显示已选择的程序信息
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.selected_program, selectedProgram.name),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(R.string.total_price, 
                        String.format("%.2f €", selectedProgram.price)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // 支付方式选择
        PaymentMethodCard(
            method = PaymentMethod.CARD,
            isSelected = state.selectedPaymentMethod == PaymentMethod.CARD,
            onSelect = { 
                // ⚠️ SUCCESS 状态下禁止选择支付方式
                if (flowState.status != com.carwash.carpayment.data.payment.PaymentFlowStatus.SUCCESS) {
                    viewModel.selectPaymentMethod(PaymentMethod.CARD)
                }
            },
            enabled = flowState.status != com.carwash.carpayment.data.payment.PaymentFlowStatus.SUCCESS  // ⚠️ SUCCESS 状态下禁用支付方式选择
        )
        
        PaymentMethodCard(
            method = PaymentMethod.CASH,
            isSelected = state.selectedPaymentMethod == PaymentMethod.CASH,
            onSelect = { 
                // ⚠️ SUCCESS 状态下禁止选择支付方式
                if (flowState.status != com.carwash.carpayment.data.payment.PaymentFlowStatus.SUCCESS) {
                    viewModel.selectPaymentMethod(PaymentMethod.CASH)
                }
            },
            enabled = flowState.status != com.carwash.carpayment.data.payment.PaymentFlowStatus.SUCCESS  // ⚠️ SUCCESS 状态下禁用支付方式选择
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // 支付确认复选框
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Checkbox(
                checked = state.paymentConfirmed,
                onCheckedChange = { 
                    // ⚠️ SUCCESS 状态下禁止修改支付确认状态
                    if (flowState.status != com.carwash.carpayment.data.payment.PaymentFlowStatus.SUCCESS) {
                        viewModel.setPaymentConfirmed(it)
                    }
                },
                enabled = flowState.status != com.carwash.carpayment.data.payment.PaymentFlowStatus.SUCCESS  // ⚠️ SUCCESS 状态下禁用复选框
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.payment_confirmation),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
        }
        
        // 未勾选时的提示（KIOSK 明确提示）
        if (!state.paymentConfirmed) {
            Text(
                text = stringResource(R.string.rules_confirmation_required),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // 支付状态提示
        when (flowState.status) {
            com.carwash.carpayment.data.payment.PaymentFlowStatus.PAYING -> {
                // 支付处理中
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 3.dp
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = when (state.selectedPaymentMethod) {
                                    PaymentMethod.CARD -> stringResource(R.string.payment_card_processing)
                                    PaymentMethod.CASH -> stringResource(R.string.cash_payment_processing)
                                    else -> stringResource(R.string.payment_processing)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        
                        // UI 在 PAYING 时显示：已收金额、还差多少、若超额显示预计找零、零钱不足提示
                        if (state.selectedPaymentMethod == PaymentMethod.CASH) {
                            Spacer(modifier = Modifier.height(12.dp))
                            // 安全处理：确保金额不为负数
                            val paidAmount = maxOf(0, flowState.paidAmountCents)
                            val targetAmount = maxOf(0, flowState.targetAmountCents)
                            val difference = targetAmount - paidAmount
                            
                            // 已投入金额（确保非负）
                            Text(
                                text = stringResource(R.string.cash_paid_amount, String.format("%.2f", paidAmount / 100.0)),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            
                            // 还差多少 / 预计找零（确保非负显示）
                            if (difference > 0) {
                                Text(
                                    text = stringResource(R.string.cash_remaining_amount, String.format("%.2f", difference / 100.0)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else if (difference < 0) {
                                val changeAmount = -difference  // 已经是正数
                                Text(
                                    text = stringResource(R.string.cash_change_amount, String.format("%.2f", changeAmount / 100.0)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                            
                            // 目标金额（确保非负）
                            if (targetAmount > 0) {
                                Text(
                                    text = stringResource(R.string.cash_target_amount, String.format("%.2f", targetAmount / 100.0)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
                
                // 取消付款按钮（支付处理中时显示）
                Button(
                    onClick = {
                        Log.d("SelectPaymentScreen", "取消支付")
                        viewModel.cancelPayment()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(KioskButtonSizes.MediumButtonHeight)
                        .padding(bottom = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(
                        text = stringResource(R.string.cancel_payment),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            com.carwash.carpayment.data.payment.PaymentFlowStatus.SUCCESS -> {
                // 支付成功（短暂显示后跳转）
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = "✅ ${stringResource(R.string.payment_success_redirecting)}",
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            com.carwash.carpayment.data.payment.PaymentFlowStatus.FAILED -> {
                // 支付失败
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "❌ ${stringResource(R.string.payment_failed)}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        flowState.errorMessage?.let { errorCode ->
                            Spacer(modifier = Modifier.height(8.dp))
                            // 根据错误码映射到 stringResource
                            val configuration = LocalConfiguration.current
                            val currentLang = configuration.locales[0].language
                            val errorText = when {
                                errorCode.startsWith("PAYMENT_TIMEOUT:") -> {
                                    val timeoutSeconds = errorCode.substringAfter(":").toIntOrNull() ?: 60
                                    Log.e("I18N_CHECK", "showError=PAYMENT_TIMEOUT lang=$currentLang timeoutSeconds=$timeoutSeconds")
                                    stringResource(R.string.error_payment_timeout_body, timeoutSeconds)
                                }
                                errorCode == "COMMUNICATION_FAILED" -> {
                                    Log.e("I18N_CHECK", "showError=COMMUNICATION_FAILED lang=$currentLang")
                                    stringResource(R.string.error_carwash_comm_failed_body)
                                }
                                errorCode == "NOT_CONNECTED" -> {
                                    Log.e("I18N_CHECK", "showError=NOT_CONNECTED lang=$currentLang")
                                    stringResource(R.string.error_device_not_connected)
                                }
                                else -> {
                                    Log.e("I18N_CHECK", "showError=$errorCode lang=$currentLang")
                                    // 其他错误码：如果是已知的错误码，映射到stringResource；否则显示通用错误
                                    when (errorCode) {
                                        "DEVICE_FAULT" -> stringResource(R.string.error_device_fault)
                                        "CHANGE_INSUFFICIENT", "CHANGE_NO_SMALL_DENOMS", "CHANGE_ALGO_FAILED" -> stringResource(R.string.error_change_insufficient)
                                        "CHANGE_FAILED", "CHANGE_CALC_FAILED" -> stringResource(R.string.error_change_failed)
                                        "CHANGE_LEVELS_UNAVAILABLE" -> stringResource(R.string.error_change_levels_unavailable)
                                        "COUNTERS_PARSE_FAILED", "COUNTERS_READ_FAILED" -> stringResource(R.string.error_counters_read_failed)
                                        else -> stringResource(R.string.payment_failed)  // 默认显示"支付失败"
                                    }
                                }
                            }
                            Text(
                                text = errorText,
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                
                // 重试按钮（支付失败时显示）
                Button(
                    onClick = {
                        // ⚠️ 关键修复：添加 UI_RETRY_CLICKED 日志
                        android.util.Log.d("UI_RETRY_CLICKED", "UI_RETRY_CLICKED")
                        Log.d("SelectPaymentScreen", "重试支付")
                        viewModel.retryPayment()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(KioskButtonSizes.MediumButtonHeight)
                        .padding(bottom = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = stringResource(R.string.payment_failed_retry),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            else -> {
                // 其他状态不显示特殊提示
            }
        } // 结束 when (flowState.status)
        
        // 支付按钮（KIOSK 大按钮）
        Button(
            onClick = {
                if (state.selectedPaymentMethod == null) {
                    Log.w("SelectPaymentScreen", "未选择支付方式")
                    return@Button
                }
                if (!state.paymentConfirmed) {
                    Log.w("SelectPaymentScreen", "未确认规则")
                    return@Button
                }
                Log.d("SelectPaymentScreen", "开始处理支付")
                viewModel.processPayment()
                // 不立即调用 onPay()，等待支付成功后由 PaymentNavigation 自动跳转
            },
            enabled = state.selectedPaymentMethod != null && state.paymentConfirmed && flowState.status != com.carwash.carpayment.data.payment.PaymentFlowStatus.SUCCESS,  // ⚠️ SUCCESS 状态下禁用支付按钮
            modifier = Modifier
                .fillMaxWidth()
                .height(KioskButtonSizes.LargeButtonHeight),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            if (state.isPaymentProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 4.dp
                )
            } else {
                Text(
                    text = stringResource(R.string.button_pay),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        } // 结束 Button（支付按钮）
    } // 结束 Column
    
    // Snackbar 宿主（用于显示其他提示）
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter)
    )
    
    // ⚠️ 关键修复：取消确认对话框（大、醒目、覆盖当前页面）
    if (showCancelConfirmDialog) {
        AlertDialog(
            onDismissRequest = {
                // ⚠️ 关键修复：禁止点击遮罩自动关闭（dismissOnClickOutside=false）
                // 用户必须明确选择"继续支付"或"确认取消"
                // 不执行任何操作，保持对话框显示
            },
            modifier = Modifier.fillMaxWidth(0.9f),  // ⚠️ 宽度：占屏幕 75%~90%
            title = {
                Text(
                    text = stringResource(R.string.cancel_payment_title),
                    style = MaterialTheme.typography.headlineLarge.copy(fontSize = 28.sp),  // ⚠️ 标题大字号（28sp）
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .padding(vertical = 16.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.cancel_payment_body),
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp),  // ⚠️ 正文清晰（20sp）
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    val paidAmount = flowState.paidAmountCents / 100.0
                    if (paidAmount > 0) {
                        Text(
                            text = "Amount to refund: €${String.format("%.2f", paidAmount)}",
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                // ⚠️ 关键修复：两个大按钮、间距大、对比强烈
                Button(
                    onClick = {
                        // 确认取消并退款，执行退款
                        coroutineScope.launch {
                            viewModel.confirmCancelAndRefund()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)  // ⚠️ 大按钮
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error  // ⚠️ 对比强烈（错误色）
                    )
                ) {
                    Text(
                        text = stringResource(R.string.cancel_payment_confirm),
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                // ⚠️ 关键修复：两个大按钮、间距大、对比强烈
                Button(
                    onClick = {
                        // 用户选择继续支付，回到 PAYING 状态
                        viewModel.onUserCancelDismissed()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)  // ⚠️ 大按钮
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary  // ⚠️ 对比强烈（主色）
                    )
                ) {
                    Text(
                        text = stringResource(R.string.cancel_payment_continue),
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        )
    }
    
    // 大字体拒收提示对话框（5秒自动关闭）
    noteRejectionMessage?.let { hint ->
        AlertDialog(
            onDismissRequest = { /* 不允许手动关闭，等待5秒自动关闭 */ },
            title = {
                Text(
                    text = stringResource(R.string.cash_note_rejected_title),
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 24.sp),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            },
                    text = {
                        Text(
                            text = when (hint.messageKey) {
                                "cash_note_rejected_with_denominations" -> {
                                    stringResource(R.string.cash_note_rejected_with_denominations, hint.acceptedDenominations ?: "")
                                }
                                "cash_payment_unavailable" -> {
                                    stringResource(R.string.cash_payment_unavailable)
                                }
                                else -> {
                                    stringResource(R.string.cash_note_rejected)
                                }
                            },
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            },
            confirmButton = {
                // 不显示按钮，5秒后自动关闭
            },
            containerColor = MaterialTheme.colorScheme.errorContainer,
            titleContentColor = MaterialTheme.colorScheme.onErrorContainer,
            textContentColor = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp)
        )
    }
} // 结束 Box
} // 结束 SelectPaymentScreen

@Composable
private fun PaymentMethodCard(
    method: PaymentMethod,
    isSelected: Boolean,
    onSelect: () -> Unit,
    enabled: Boolean = true  // ⚠️ 支持禁用状态
) {
    val onClickHandler: () -> Unit = remember(enabled, onSelect) {
        if (enabled) {
            onSelect
        } else {
            { /* 禁用时不响应点击 */ }
        }
    }
    
    Card(
        onClick = onClickHandler,
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (!enabled) {
                MaterialTheme.colorScheme.surfaceVariant  // 禁用时使用灰色
            } else if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(
                4.dp,
                MaterialTheme.colorScheme.primary
            )
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onClickHandler
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = stringResource(
                    if (method == PaymentMethod.CARD) R.string.payment_card
                    else R.string.payment_cash
                ),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}


