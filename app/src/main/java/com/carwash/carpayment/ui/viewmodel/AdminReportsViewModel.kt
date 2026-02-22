package com.carwash.carpayment.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carwash.carpayment.data.transaction.TransactionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Admin Reports ViewModel（V3.4 规范）
 */
class AdminReportsViewModel(application: Application) : AndroidViewModel(application) {
    
    private val transactionRepository = TransactionRepository(application)
    
    /**
     * 报表统计数据
     */
    private val _reportStats = MutableStateFlow<ReportStats?>(null)
    val reportStats: StateFlow<ReportStats?> = _reportStats.asStateFlow()
    
    /**
     * 加载报表数据
     */
    fun loadReportStats() {
        viewModelScope.launch {
            val transactions = transactionRepository.getAllTransactions().first()
            
            val totalOrders = transactions.size
            val paidOrders = transactions.count { it.result == com.carwash.carpayment.data.transaction.TransactionResult.SUCCESS }
            val cancelledOrders = transactions.count { it.result == com.carwash.carpayment.data.transaction.TransactionResult.CANCELLED }
            val failedOrders = transactions.count { it.result == com.carwash.carpayment.data.transaction.TransactionResult.FAILED }
            
            val totalRevenue = transactions
                .filter { it.result == com.carwash.carpayment.data.transaction.TransactionResult.SUCCESS }
                .sumOf { it.amount }
            
            val cashTotal = transactions
                .filter { 
                    it.result == com.carwash.carpayment.data.transaction.TransactionResult.SUCCESS &&
                    it.paymentMethod == com.carwash.carpayment.data.PaymentMethod.CASH
                }
                .sumOf { it.amount }
            
            val posTotal = transactions
                .filter { 
                    it.result == com.carwash.carpayment.data.transaction.TransactionResult.SUCCESS &&
                    it.paymentMethod == com.carwash.carpayment.data.PaymentMethod.CARD
                }
                .sumOf { it.amount }
            
            _reportStats.value = ReportStats(
                totalOrders = totalOrders,
                paidOrders = paidOrders,
                cancelledOrders = cancelledOrders,
                failedOrders = failedOrders,
                totalRevenue = totalRevenue,
                cashTotal = cashTotal,
                posTotal = posTotal
            )
        }
    }
}

/**
 * 报表统计数据（V3.4 规范）
 */
data class ReportStats(
    val totalOrders: Int,
    val paidOrders: Int,
    val cancelledOrders: Int,
    val failedOrders: Int,
    val totalRevenue: Double,  // 总净收入（欧元）
    val cashTotal: Double,     // 现金总额（欧元）
    val posTotal: Double       // POS总额（欧元）
) {
    /**
     * 格式化总净收入
     */
    fun getFormattedTotalRevenue(): String {
        return String.format("%.2f€", totalRevenue)
    }
    
    /**
     * 格式化现金总额
     */
    fun getFormattedCashTotal(): String {
        return String.format("%.2f€", cashTotal)
    }
    
    /**
     * 格式化POS总额
     */
    fun getFormattedPosTotal(): String {
        return String.format("%.2f€", posTotal)
    }
}
