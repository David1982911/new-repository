package com.carwash.carpayment.ui.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PaymentSelectionViewModel : ViewModel() {
    private val _isConfirmed = MutableStateFlow(false)
    val isConfirmed: StateFlow<Boolean> = _isConfirmed

    val isPayEnabled: StateFlow<Boolean> = _isConfirmed // 简单情况，如需其他条件可扩展

    fun updateConfirmation(checked: Boolean) {
        _isConfirmed.value = checked
    }
}
