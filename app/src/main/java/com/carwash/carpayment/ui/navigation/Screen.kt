package com.carwash.carpayment.ui.navigation

/**
 * 导航路由定义
 */
sealed class Screen(val route: String) {
    data object SelectProgram : Screen("select_program")
    data object SelectPayment : Screen("select_payment")
    data object WashingInProgress : Screen("washing_in_progress")
    data object TransactionList : Screen("transaction_list")
    data object DeviceTestGate : Screen("device_test_gate")
    data object DeviceTest : Screen("device_test")
    
    // V3.4: Admin Console 相关路由
    data object AdminConsoleLogin : Screen("admin_console_login")
    data object AdminConsole : Screen("admin_console")
    data object AdminOrders : Screen("admin_orders")
    data object AdminReports : Screen("admin_reports")
    data object AdminUsers : Screen("admin_users")
}
