package com.carwash.carpayment.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class CashPaymentViewModel : ViewModel() {
    private val _targetAmount = MutableStateFlow(0.0)
    private val _insertedAmount = MutableStateFlow(0.0)
    private val _remainingAmount = MutableStateFlow(0.0)
    
    val insertedAmount: StateFlow<Double> = _insertedAmount
    val remainingAmount: StateFlow<Double> = _remainingAmount
    
    init {
        // 监听目标金额和已投金额的变化，更新剩余金额
        viewModelScope.launch {
            combine(_targetAmount, _insertedAmount) { target, inserted ->
                (target - inserted).coerceAtLeast(0.0)
            }.collect { remaining ->
                _remainingAmount.value = remaining
            }
        }
    }

    fun setTargetAmount(amount: Double) {
        _targetAmount.value = amount
    }

    // 模拟投币更新（实际应通过 CashDevice 驱动更新）
    fun addInserted(amount: Double) {
        _insertedAmount.value += amount
        if (_insertedAmount.value >= _targetAmount.value) {
            // 支付成功，跳转
        }
    }
}
