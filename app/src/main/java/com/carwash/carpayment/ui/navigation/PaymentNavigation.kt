package com.carwash.carpayment.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.carwash.carpayment.ui.screens.DeviceTestScreen
import com.carwash.carpayment.ui.screens.SelectPaymentScreen
import com.carwash.carpayment.ui.screens.SelectProgramScreen
import com.carwash.carpayment.ui.screens.TransactionListScreen
import com.carwash.carpayment.ui.screens.WashingInProgressScreen
import com.carwash.carpayment.data.payment.PaymentFlowStatus
import com.carwash.carpayment.ui.viewmodel.CashDeviceTestViewModel
import com.carwash.carpayment.ui.viewmodel.HomeViewModel
import com.carwash.carpayment.ui.viewmodel.LanguageViewModel
import com.carwash.carpayment.ui.viewmodel.PaymentViewModel
import com.carwash.carpayment.ui.viewmodel.TransactionListViewModel

/**
 * 支付流程导航配置
 */
@Composable
fun PaymentNavigation(
    navController: NavHostController,
    homeViewModel: HomeViewModel,
    languageViewModel: LanguageViewModel
) {
    val context = LocalContext.current
    val paymentViewModel: PaymentViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(
            context.applicationContext as android.app.Application
        )
    )
    
    NavHost(
        navController = navController,
        startDestination = Screen.SelectProgram.route
    ) {
        composable(Screen.SelectProgram.route) {
            SelectProgramScreen(
                homeViewModel = homeViewModel,
                languageViewModel = languageViewModel,
                onProgramSelected = { programId ->
                    android.util.Log.d("PaymentNavigation", "选择程序: $programId，跳转到支付页面")
                    // 从 HomeViewModel 获取选中的程序并设置到 PaymentViewModel
                    val selectedProgram = homeViewModel.selectedProgram.value
                    if (selectedProgram != null) {
                        paymentViewModel.selectProgram(selectedProgram)
                    }
                    navController.navigate(Screen.SelectPayment.route) {
                        popUpTo(Screen.SelectProgram.route) { inclusive = false }
                    }
                },
                onShowTransactionList = {
                    android.util.Log.d("PaymentNavigation", "打开交易列表页面")
                    navController.navigate(Screen.TransactionList.route) {
                        popUpTo(Screen.SelectProgram.route) { inclusive = false }
                    }
                },
                onShowDeviceTest = {
                    android.util.Log.d("PaymentNavigation", "打开设备测试页面")
                    navController.navigate(Screen.DeviceTest.route) {
                        popUpTo(Screen.SelectProgram.route) { inclusive = false }
                    }
                }
            )
        }
        
        composable(Screen.SelectPayment.route) {
            val flowState by paymentViewModel.flowState.collectAsState()
            
            SelectPaymentScreen(
                viewModel = paymentViewModel,
                onPay = {
                    android.util.Log.d("PaymentNavigation", "开始支付处理，等待支付完成...")
                    // 不立即跳转，等待支付真正成功
                }
            )
            
            // 监听支付状态，支付成功后自动跳转
            LaunchedEffect(flowState.status) {
                if (flowState.status == PaymentFlowStatus.SUCCESS) {
                    android.util.Log.d("PaymentNavigation", "支付成功，跳转到洗车进行中页面")
                    navController.navigate(Screen.WashingInProgress.route) {
                        popUpTo(Screen.SelectPayment.route) { inclusive = false }
                    }
                } else if (flowState.status == PaymentFlowStatus.FAILED) {
                    android.util.Log.d("PaymentNavigation", "支付失败，保持在支付页面")
                    // 保持在当前页面，显示错误信息（由 SelectPaymentScreen 处理）
                }
            }
        }
        
        composable(Screen.WashingInProgress.route) {
            WashingInProgressScreen(
                viewModel = paymentViewModel,
                onFinish = {
                    android.util.Log.d("PaymentNavigation", "洗车完成，返回首页")
                    // 重置状态并返回首页
                    paymentViewModel.reset()
                    homeViewModel.resetSelectedProgram()
                    navController.popBackStack(Screen.SelectProgram.route, inclusive = false)
                }
            )
        }
        
        composable(Screen.TransactionList.route) {
            val context = LocalContext.current
            val transactionListViewModel: TransactionListViewModel = viewModel(
                factory = ViewModelProvider.AndroidViewModelFactory.getInstance(
                    context.applicationContext as android.app.Application
                )
            )
            TransactionListScreen(
                viewModel = transactionListViewModel,
                onBack = {
                    android.util.Log.d("PaymentNavigation", "返回首页")
                    navController.popBackStack()
                }
            )
        }
        
        composable(Screen.DeviceTest.route) {
            val context = LocalContext.current
            val testViewModel: CashDeviceTestViewModel = viewModel(
                factory = ViewModelProvider.AndroidViewModelFactory.getInstance(
                    context.applicationContext as android.app.Application
                )
            )
            DeviceTestScreen(
                viewModel = testViewModel,
                onBack = {
                    android.util.Log.d("PaymentNavigation", "返回首页")
                    navController.popBackStack()
                }
            )
        }
    }
}
