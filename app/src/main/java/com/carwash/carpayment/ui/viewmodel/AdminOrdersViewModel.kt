package com.carwash.carpayment.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carwash.carpayment.data.transaction.Transaction
import com.carwash.carpayment.data.transaction.TransactionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Admin Orders ViewModel（V3.4 规范）
 */
class AdminOrdersViewModel(application: Application) : AndroidViewModel(application) {
    
    private val transactionRepository = TransactionRepository(application)
    
    /**
     * 所有订单（从 Transaction 转换）
     */
    val orders: StateFlow<List<OrderDisplayItem>> = transactionRepository.getAllTransactions()
        .map { transactions ->
            transactions.map { it.toOrderDisplayItem() }
        }
        .stateIn(
            scope = viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    /**
     * 搜索关键词
     */
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    
    /**
     * 过滤后的订单
     */
    val filteredOrders: StateFlow<List<OrderDisplayItem>> = kotlinx.coroutines.flow.combine(
        orders,
        _searchQuery
    ) { ordersList, query ->
        if (query.isBlank()) {
            ordersList
        } else {
            ordersList.filter {
                it.orderId.contains(query, ignoreCase = true) ||
                it.programName.contains(query, ignoreCase = true) ||
                it.paymentMethod.contains(query, ignoreCase = true)
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    
    /**
     * 选中的订单（用于详情显示）
     */
    private val _selectedOrder = MutableStateFlow<OrderDisplayItem?>(null)
    val selectedOrder: StateFlow<OrderDisplayItem?> = _selectedOrder.asStateFlow()
    
    /**
     * 设置搜索关键词
     */
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }
    
    /**
     * 选择订单
     */
    fun selectOrder(order: OrderDisplayItem) {
        _selectedOrder.value = order
    }
    
    /**
     * 清除选中
     */
    fun clearSelection() {
        _selectedOrder.value = null
    }
}

/**
 * 订单显示项（V3.4 规范：从 Transaction 转换）
 */
data class OrderDisplayItem(
    val orderId: String,  // Transaction ID 转换为订单ID
    val createdAt: Long,
    val priceCents: Long,  // 金额（分）
    val paidCents: Long,   // 已支付金额（分）
    val changeCents: Long, // 找零金额（分），默认为 0
    val paymentMethod: String,  // CASH / POS
    val state: String,     // SUCCESS / FAILED / CANCELLED
    val failureReasonCode: String? = null,
    val operatorId: String? = null,
    val completedAt: Long? = null,
    val programName: String,
    val programId: String
) {
    /**
     * 格式化日期时间
     */
    fun getFormattedDateTime(locale: Locale = Locale.getDefault()): String {
        val format = SimpleDateFormat("dd.MM.yyyy HH:mm", locale)
        return format.format(Date(createdAt))
    }
    
    /**
     * 格式化金额（欧元）
     */
    fun getFormattedAmount(): String {
        return String.format("%.2f€", priceCents / 100.0)
    }
    
    /**
     * 格式化已支付金额（欧元）
     */
    fun getFormattedPaidAmount(): String {
        return String.format("%.2f€", paidCents / 100.0)
    }
    
    /**
     * 格式化找零金额（欧元）
     */
    fun getFormattedChangeAmount(): String {
        return String.format("%.2f€", changeCents / 100.0)
    }
}

/**
 * Transaction 转换为 OrderDisplayItem
 */
private fun Transaction.toOrderDisplayItem(): OrderDisplayItem {
    return OrderDisplayItem(
        orderId = id.toString(),
        createdAt = timestamp,
        priceCents = (amount * 100).toLong(),
        paidCents = (amount * 100).toLong(),  // Transaction 中 amount 即为已支付金额
        changeCents = 0L,  // Transaction 中没有找零信息，默认为 0
        paymentMethod = when (paymentMethod) {
            com.carwash.carpayment.data.PaymentMethod.CASH -> "CASH"
            com.carwash.carpayment.data.PaymentMethod.CARD -> "POS"
        },
        state = when (result) {
            com.carwash.carpayment.data.transaction.TransactionResult.SUCCESS -> "ORDER_COMPLETED"
            com.carwash.carpayment.data.transaction.TransactionResult.FAILED -> "ORDER_FAILED"
            com.carwash.carpayment.data.transaction.TransactionResult.CANCELLED -> "ORDER_REFUNDED"
        },
        failureReasonCode = if (result == com.carwash.carpayment.data.transaction.TransactionResult.FAILED) "UNKNOWN" else null,
        operatorId = null,  // Transaction 中没有操作员ID
        completedAt = if (result == com.carwash.carpayment.data.transaction.TransactionResult.SUCCESS) timestamp else null,
        programName = programName,
        programId = programId
    )
}
