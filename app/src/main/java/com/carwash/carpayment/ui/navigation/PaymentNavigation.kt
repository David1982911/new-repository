package com.carwash.carpayment.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.carwash.carpayment.ui.screens.DeviceTestGateScreen
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
import com.carwash.carpayment.ui.viewmodel.PrinterTabViewModel
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
            val flowState by paymentViewModel.flowState.collectAsState()
            
            SelectProgramScreen(
                homeViewModel = homeViewModel,
                languageViewModel = languageViewModel,
                paymentViewModel = paymentViewModel,
                onProgramSelected = { programId ->
                    android.util.Log.d("PaymentNavigation", "选择程序: $programId，准备跳转到支付页面")
                    // 进入支付流程前暂停首页轮询
                    homeViewModel.pausePolling()
                    // 从 HomeViewModel 获取选中的程序并设置到 PaymentViewModel
                    // 注意：selectProgram() 内部会执行 GateCheck，只有通过才会进入支付流程
                    val selectedProgram = homeViewModel.selectedProgram.value
                    if (selectedProgram != null) {
                        paymentViewModel.selectProgram(selectedProgram)
                        // 注意：导航到支付页面由 GateCheck 结果决定，如果失败则不导航
                        // 这里先不立即导航，等待 GateCheck 结果
                    }
                },
                onShowTransactionList = {
                    android.util.Log.d("PaymentNavigation", "打开交易列表页面")
                    navController.navigate(Screen.TransactionList.route) {
                        popUpTo(Screen.SelectProgram.route) { inclusive = false }
                    }
                },
                onShowDeviceTest = {
                    android.util.Log.d("PaymentNavigation", "打开设备测试密码保护页面")
                    navController.navigate(Screen.DeviceTestGate.route) {
                        popUpTo(Screen.SelectProgram.route) { inclusive = false }
                    }
                }
            )
            
            // 监听 GateCheck 结果，只有通过时才导航到支付页面
            // 进入支付流程前暂停首页轮询
            androidx.compose.runtime.LaunchedEffect(flowState.status) {
                when (flowState.status) {
                    PaymentFlowStatus.SELECTING_METHOD -> {
                        // GateCheck 通过，状态已转为 SELECTING_METHOD，暂停首页轮询并导航到支付页面
                        android.util.Log.d("PaymentNavigation", "GateCheck 通过，暂停首页轮询并导航到支付页面")
                        homeViewModel.pausePolling()
                        navController.navigate(Screen.SelectPayment.route) {
                            popUpTo(Screen.SelectProgram.route) { inclusive = false }
                        }
                    }
                    PaymentFlowStatus.PAYING -> {
                        // 支付中，确保首页轮询已暂停
                        homeViewModel.pausePolling()
                    }
                    PaymentFlowStatus.NOT_STARTED -> {
                        // 回到首页，恢复轮询
                        homeViewModel.resumePolling()
                    }
                    else -> {
                        // 其他状态不处理
                    }
                }
            }
        }
        
        composable(Screen.SelectPayment.route) {
            val flowState by paymentViewModel.flowState.collectAsState()
            
            SelectPaymentScreen(
                viewModel = paymentViewModel,
                onPay = {
                    android.util.Log.d("PaymentNavigation", "开始支付处理，等待支付完成...")
                    // 不立即跳转，等待支付真正成功
                },
                onBackToHome = {
                    android.util.Log.d("PaymentNavigation", "返回首页")
                    val currentStatus = flowState.status
                    // ⚠️ SUCCESS 状态下：直接导航回 Home 并清栈，但必须重置状态
                    if (currentStatus == PaymentFlowStatus.SUCCESS) {
                        android.util.Log.d("PaymentNavigation", "SUCCESS 状态下返回：直接导航回 Home 并清栈")
                        // ⚠️ Step A: 在返回主界面时强制 reset 支付状态
                        paymentViewModel.resetForNewAttempt(reason = "NAVIGATE_HOME_AFTER_SUCCESS")
                        // 先导航离开支付页，再清空选择（避免白屏）
                        navController.popBackStack(Screen.SelectProgram.route, inclusive = false)
                        // 清空选择在 Home 页恢复/进入时做，不在支付页清空
                        homeViewModel.resumePolling() // 恢复首页轮询
                    } else {
                        // 非 SUCCESS 状态：正常重置流程
                        paymentViewModel.reset()
                        homeViewModel.resetSelectedProgram()
                        homeViewModel.resumePolling() // 恢复首页轮询
                        navController.popBackStack(Screen.SelectProgram.route, inclusive = false)
                    }
                }
            )
            
            // 监听支付状态，支付成功后自动返回主界面
            LaunchedEffect(flowState.status) {
                when (flowState.status) {
                    PaymentFlowStatus.SUCCESS -> {
                        android.util.Log.d("PaymentNavigation", "支付成功，3秒后返回主界面")
                        // 等待3秒后返回主界面（给用户看到成功提示的时间）
                        kotlinx.coroutines.delay(3000)
                        android.util.Log.d("PaymentNavigation", "返回主界面")
                        // ⚠️ 关键修复：在返回主界面时调用新交易重置，清空所有状态
                        paymentViewModel.resetForNewTransaction(reason = "AFTER_SUCCESS_RETURN_HOME")
                        // ⚠️ 关键修复：清空选择的套餐，允许用户再次选择
                        homeViewModel.resetSelectedProgram()
                        // 先导航离开支付页
                        navController.popBackStack(Screen.SelectProgram.route, inclusive = false)
                        // 恢复首页轮询
                        homeViewModel.resumePolling()
                    }
                    PaymentFlowStatus.CANCELLED -> {
                        android.util.Log.d("PaymentNavigation", "支付已取消，立即返回主界面")
                        // 立即返回主界面
                        paymentViewModel.reset()
                        homeViewModel.resetSelectedProgram()
                        homeViewModel.resumePolling() // 恢复首页轮询
                        navController.popBackStack(Screen.SelectProgram.route, inclusive = false)
                    }
                    PaymentFlowStatus.CANCELLED_REFUNDED -> {
                        // ⚠️ 关键修复：退款完成后才允许导航回首页（确定行为）
                        android.util.Log.d("PaymentNavigation", "支付已取消且退款完成，返回主界面")
                        android.util.Log.d("REFUND_DONE", "REFUND_DONE: 退款完成，允许导航回首页")
                        // 退款完成后返回主界面并重置状态
                        paymentViewModel.resetForNewTransaction(reason = "CANCEL_REFUNDED_RETURN_HOME")
                        homeViewModel.resetSelectedProgram()
                        homeViewModel.resumePolling() // 恢复首页轮询
                        navController.popBackStack(Screen.SelectProgram.route, inclusive = false)
                    }
                    PaymentFlowStatus.CANCELLED_REFUNDING -> {
                        // ⚠️ 关键修复：退款中，不允许导航回首页（必须等待退款完成）
                        android.util.Log.d("PaymentNavigation", "退款中，等待退款完成...")
                        // 不执行任何导航操作，等待状态变为 CANCELLED_REFUNDED
                    }
                    PaymentFlowStatus.FAILED -> {
                        android.util.Log.d("PaymentNavigation", "支付失败，保持在支付页面")
                        // 保持在当前页面，显示错误信息（由 SelectPaymentScreen 处理）
                        // 注意：失败时不恢复轮询，因为还在支付页面
                    }
                    PaymentFlowStatus.NOT_STARTED -> {
                        // 回到首页，恢复轮询
                        homeViewModel.resumePolling()
                    }
                    else -> {
                        // 其他状态不处理导航
                    }
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
                    homeViewModel.resumePolling() // 恢复首页轮询
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
        
        composable(Screen.DeviceTestGate.route) {
            DeviceTestGateScreen(
                onPasswordCorrect = {
                    android.util.Log.d("PaymentNavigation", "密码正确，进入设备测试页面")
                    navController.navigate(Screen.DeviceTest.route) {
                        popUpTo(Screen.DeviceTestGate.route) { inclusive = true }
                    }
                },
                onCancel = {
                    android.util.Log.d("PaymentNavigation", "取消进入设备测试页面")
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
            val printerTabViewModel: PrinterTabViewModel = viewModel(
                factory = ViewModelProvider.AndroidViewModelFactory.getInstance(
                    context.applicationContext as android.app.Application
                )
            )
            val activity = context as? com.carwash.carpayment.MainActivity
            DeviceTestScreen(
                viewModel = testViewModel,
                printerTabViewModel = printerTabViewModel,
                onBack = {
                    android.util.Log.d("PaymentNavigation", "返回首页")
                    navController.popBackStack()
                },
                onExitApp = {
                    android.util.Log.d("PaymentNavigation", "退出APP")
                    activity?.exitApp()
                }
            )
        }
    }
}
