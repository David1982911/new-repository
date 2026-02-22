package com.carwash.carpayment.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.carwash.carpayment.R
import com.carwash.carpayment.ui.viewmodel.CashPaymentViewModel

@Composable
fun CashPaymentScreen(
    navController: NavController,
    targetAmount: Double, // 从导航参数传入
    viewModel: CashPaymentViewModel = viewModel()
) {
    val insertedAmount by viewModel.insertedAmount.collectAsState()
    val remainingAmount by viewModel.remainingAmount.collectAsState()

    // 初始化 ViewModel 的目标金额
    LaunchedEffect(targetAmount) {
        viewModel.setTargetAmount(targetAmount)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.payment_target, String.format("%.2f", targetAmount)),
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.payment_inserted, String.format("%.2f", insertedAmount)),
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.payment_remaining, String.format("%.2f", remainingAmount)),
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = { navController.popBackStack() }
        ) {
            Text(stringResource(R.string.button_cancel))
        }
    }
}
