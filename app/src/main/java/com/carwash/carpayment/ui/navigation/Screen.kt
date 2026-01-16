package com.carwash.carpayment.ui.navigation

/**
 * 导航路由定义
 */
sealed class Screen(val route: String) {
    data object SelectProgram : Screen("select_program")
    data object SelectPayment : Screen("select_payment")
    data object WashingInProgress : Screen("washing_in_progress")
    data object TransactionList : Screen("transaction_list")
    data object DeviceTest : Screen("device_test")
}
