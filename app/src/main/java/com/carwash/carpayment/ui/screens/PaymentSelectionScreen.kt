package com.carwash.carpayment.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.carwash.carpayment.R
import com.carwash.carpayment.ui.viewmodel.PaymentSelectionViewModel

@Composable
fun PaymentSelectionScreen(
    navController: NavController,
    programName: String,
    programPrice: Double,
    viewModel: PaymentSelectionViewModel = viewModel()
) {
    val isChecked by viewModel.isConfirmed.collectAsState()
    val isPayEnabled by viewModel.isPayEnabled.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 头部图片（占位，可使用默认图标）
        // 如果没有图片资源，使用占位颜色
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .background(MaterialTheme.colorScheme.primaryContainer)
        ) {
            // 可以在这里添加图片资源
        }

        Spacer(modifier = Modifier.height(24.dp)) // 8mm ≈ 24dp

        // 标题
        Text(
            text = stringResource(R.string.payment_choose_method),
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(24.dp)) // 8mm

        // 套餐信息卡片
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.payment_program_selected, programName),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.payment_total_price, programPrice),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        Spacer(modifier = Modifier.height(30.dp)) // 10mm

        // Card 支付图片按钮
        Image(
            painter = painterResource(id = R.drawable.ic_card_payment),
            contentDescription = stringResource(R.string.payment_card),
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clickable { 
                    // 处理卡支付逻辑
                    // 这里可以调用现有的卡支付逻辑
                }
        )

        Spacer(modifier = Modifier.height(30.dp)) // 10mm

        // Cash 支付图片按钮
        Image(
            painter = painterResource(id = R.drawable.ic_cash_payment),
            contentDescription = stringResource(R.string.payment_cash),
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clickable {
                    // 跳转到现金支付金额显示界面，传递目标金额
                    navController.navigate("cash_payment/${programPrice}")
                }
        )

        Spacer(modifier = Modifier.height(45.dp)) // 15mm

        // 确认复选框
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = { viewModel.updateConfirmation(it) }
            )
            Text(
                text = stringResource(R.string.payment_confirm),
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(60.dp)) // 20mm

        // 支付按钮图片
        // 如果没有图片资源，使用按钮代替
        Button(
            onClick = {
                // 执行支付（根据之前选择的支付方式，此处可跳转或直接处理）
            },
            enabled = isPayEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        ) {
            Text(stringResource(R.string.payment_pay))
        }
    }
}
