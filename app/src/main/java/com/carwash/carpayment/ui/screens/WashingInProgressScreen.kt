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
    
    Log.d("WashingInProgressScreen", "渲染洗车进行中页面，支付成功: ${state.paymentSuccess}, 状态: ${flowState.status}, 错误: ${flowState.errorMessage}")
    
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
        
        // 洗车进行中标题（KIOSK 大字号）
        Text(
            text = stringResource(R.string.washing_in_progress),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        
        // 状态占位：加载指示器（KIOSK 大尺寸）
        CircularProgressIndicator(
            modifier = Modifier.size(100.dp),
            strokeWidth = 8.dp,
            color = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // 静态文案（KIOSK 大字号）
        Text(
            text = stringResource(R.string.washing_message),
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
