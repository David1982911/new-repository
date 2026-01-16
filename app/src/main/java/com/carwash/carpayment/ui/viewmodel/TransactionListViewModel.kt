package com.carwash.carpayment.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carwash.carpayment.data.transaction.Transaction
import com.carwash.carpayment.data.transaction.TransactionRepository
import com.carwash.carpayment.util.CsvExporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * TransactionListViewModel - 管理交易列表
 */
class TransactionListViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "TransactionListViewModel"
    }
    
    private val repository = TransactionRepository(application)
    
    // 交易列表
    val transactions: StateFlow<List<Transaction>> = repository.getAllTransactions()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    // 交易总数
    val transactionCount: StateFlow<Int> = repository.getTransactionCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )
    
    // 总金额
    private val _totalAmount = MutableStateFlow(0.0)
    val totalAmount: StateFlow<Double> = _totalAmount.asStateFlow()
    
    init {
        viewModelScope.launch {
            repository.getTotalAmount().collect { amount ->
                _totalAmount.value = amount ?: 0.0
            }
        }
    }
    
    /**
     * 删除交易记录
     */
    fun deleteTransaction(transaction: Transaction) {
        viewModelScope.launch {
            repository.deleteTransaction(transaction)
            Log.d(TAG, "删除交易记录: ${transaction.id}")
        }
    }
    
    /**
     * 删除所有交易记录
     */
    fun deleteAllTransactions() {
        viewModelScope.launch {
            repository.deleteAllTransactions()
            Log.d(TAG, "删除所有交易记录")
        }
    }
    
    /**
     * 导出为 CSV
     * @return 文件路径，如果失败返回 null
     */
    suspend fun exportToCsv(fileName: String? = null): String? {
        val transactions = transactions.value
        return CsvExporter.exportToCsv(getApplication(), transactions, fileName)
    }
    
    /**
     * 导出为 CSV（Excel 可打开）
     * @return 文件路径，如果失败返回 null
     */
    suspend fun exportToExcel(fileName: String? = null): String? {
        val transactions = transactions.value
        // 改为导出 CSV，Excel 可以打开 CSV 文件
        val csvFileName = fileName?.replace(".xlsx", ".csv")?.replace(".xls", ".csv") 
            ?: "transactions_${System.currentTimeMillis()}.csv"
        return CsvExporter.exportToCsv(getApplication(), transactions, csvFileName)
    }
}
