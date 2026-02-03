package com.carwash.carpayment.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carwash.carpayment.R
import com.carwash.carpayment.data.carwash.CarWashStartState
import com.carwash.carpayment.data.carwash.CarWashStartFailureReason
import com.carwash.carpayment.ui.theme.KioskButtonSizes
import com.carwash.carpayment.ui.viewmodel.PaymentViewModel

/**
 * 页面3: 洗车进行中（静态文案 + 状态占位）
 */
@Composable
fun WashingInProgressScreen(
    viewModel: PaymentViewModel,
    onFinish: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val flowState by viewModel.flowState.collectAsState()
    val carWashStartState by viewModel.carWashStartState.collectAsState()
    
    Log.d("WashingInProgressScreen", "渲染洗车进行中页面，支付成功: ${state.paymentSuccess}, 状态: ${flowState.status}, 错误: ${flowState.errorMessage}, 洗车启动状态: ${carWashStartState?.javaClass?.simpleName}")
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.weight(1f))
        
        // 支付成功提示
        if (state.paymentSuccess) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "✓",
                        fontSize = 64.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.payment_success),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(48.dp))
        
        // 洗车启动状态标题（根据状态机状态动态显示）
        val statusTitle = when (carWashStartState) {
            is CarWashStartState.WaitingPreviousCarLeave -> "等待前车离开..."
            is CarWashStartState.WaitingCarInPosition -> "等待车辆到位..."
            is CarWashStartState.WaitingDeviceReady -> "等待设备就绪..."
            is CarWashStartState.SendingMode -> "指令已发送，设备准备启动…"
            is CarWashStartState.ConfirmingStart -> "指令已发送，设备准备启动…"
            is CarWashStartState.Success -> "洗车程序已启动"
            is CarWashStartState.Refunding -> "启动失败，正在退款..."
            null -> stringResource(R.string.washing_in_progress)
        }
        
        Text(
            text = statusTitle,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        
        // 状态占位：加载指示器（KIOSK 大尺寸）
        // 只有在成功状态时不显示加载指示器
        if (carWashStartState !is CarWashStartState.Success) {
            CircularProgressIndicator(
                modifier = Modifier.size(100.dp),
                strokeWidth = 8.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            // 成功状态显示对勾
            Text(
                text = "✓",
                fontSize = 100.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // 状态详情文案（根据状态机状态动态显示）
        val statusMessage = when (val state = carWashStartState) {
            is CarWashStartState.WaitingPreviousCarLeave -> "正在检查前车是否已离开..."
            is CarWashStartState.WaitingCarInPosition -> "请将车辆停放在指定位置..."
            is CarWashStartState.WaitingDeviceReady -> "正在检查设备状态..."
            is CarWashStartState.SendingMode -> "正在发送启动指令..."
            is CarWashStartState.ConfirmingStart -> "正在确认设备启动状态..."
            is CarWashStartState.Success -> "洗车程序已成功启动，请稍候..."
            is CarWashStartState.Refunding -> {
                val reason = when (state.reason) {
                    CarWashStartFailureReason.PREVIOUS_CAR_NOT_LEFT -> "前车未离开，请等待"
                    CarWashStartFailureReason.CAR_NOT_IN_POSITION -> "车辆未到位，请将车辆停放在指定位置"
                    CarWashStartFailureReason.DEVICE_NOT_READY -> "设备未就绪，请稍后再试"
                    CarWashStartFailureReason.SEND_MODE_FAILED -> "发送命令失败，请重试"
                    CarWashStartFailureReason.NOT_ENTERED_AUTO_STATUS -> "未进入自动状态，请重试"
                }
                "启动失败: $reason"
            }
            null -> stringResource(R.string.washing_message)
        }
        
        Text(
            text = statusMessage,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        
        // 状态占位提示（后续接入真实状态，KIOSK 明确提示）
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.washing_status_placeholder),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        // 错误提示（如果有）
        flowState.errorMessage?.let { errorMsg ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "⚠",
                        fontSize = 48.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = errorMsg,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            
            // 重试按钮
            Button(
                onClick = {
                    Log.d("WashingInProgressScreen", "重试支付")
                    viewModel.retryPayment()
                    // 返回支付页面
                    onFinish()
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
                    text = "重试",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        
        // 完成按钮（KIOSK 大按钮）
        Button(
            onClick = {
                Log.d("WashingInProgressScreen", "完成流程，重置状态")
                viewModel.reset()
                onFinish()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(KioskButtonSizes.LargeButtonHeight),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        ) {
            Text(
                text = stringResource(R.string.button_finish),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
