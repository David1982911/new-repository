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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onPay: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val flowState by viewModel.flowState.collectAsState()
    val selectedProgram = state.selectedProgram
    
    Log.d("SelectPaymentScreen", "渲染支付页面，已选程序: ${selectedProgram?.id}, 已选支付: ${state.selectedPaymentMethod}")
    
    if (selectedProgram == null) {
        // 如果没有选择程序，不应该进入此页面
        Log.w("SelectPaymentScreen", "未选择程序，无法显示支付页面")
        return
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
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
            onSelect = { viewModel.selectPaymentMethod(PaymentMethod.CARD) }
        )
        
        PaymentMethodCard(
            method = PaymentMethod.CASH,
            isSelected = state.selectedPaymentMethod == PaymentMethod.CASH,
            onSelect = { viewModel.selectPaymentMethod(PaymentMethod.CASH) }
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
                onCheckedChange = { viewModel.setPaymentConfirmed(it) }
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
        
        // 支付错误提示（如果有）
        flowState.errorMessage?.let { errorMsg ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = errorMsg,
                    modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            
            // 重试按钮（支付失败时显示）
            if (flowState.status == com.carwash.carpayment.data.payment.PaymentFlowStatus.FAILED) {
                Button(
                    onClick = {
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
                        text = "重试",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        
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
            enabled = state.selectedPaymentMethod != null && state.paymentConfirmed,
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
        }
    }
}

@Composable
private fun PaymentMethodCard(
    method: PaymentMethod,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
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
                onClick = onSelect
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
