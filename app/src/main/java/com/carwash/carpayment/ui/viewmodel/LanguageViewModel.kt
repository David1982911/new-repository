package com.carwash.carpayment.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 语言类型
 */
enum class AppLanguage(val locale: Locale) {
    GERMAN(Locale("de", "DE")),
    ENGLISH(Locale.ENGLISH)
}

/**
 * LanguageViewModel - 管理应用语言状态
 */
class LanguageViewModel : ViewModel() {
    
    companion object {
        private const val TAG = "LanguageViewModel"
    }
    
    private val _currentLanguage = MutableStateFlow<AppLanguage>(AppLanguage.GERMAN)
    val currentLanguage: StateFlow<AppLanguage> = _currentLanguage.asStateFlow()
    
    /**
     * 切换语言
     */
    fun switchLanguage(language: AppLanguage) {
        Log.d(TAG, "切换语言: $language")
        _currentLanguage.value = language
    }
    
    /**
     * 切换语言（在两种语言间切换）
     */
    fun toggleLanguage() {
        val newLanguage = when (_currentLanguage.value) {
            AppLanguage.GERMAN -> AppLanguage.ENGLISH
            AppLanguage.ENGLISH -> AppLanguage.GERMAN
        }
        switchLanguage(newLanguage)
    }
}
