package com.carwash.carpayment.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carwash.carpayment.data.WashProgram
import com.carwash.carpayment.data.config.ProgramConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * HomeViewModel - 管理首页（选择洗车程序）状态
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "HomeViewModel"
    }
    
    private val configRepository = ProgramConfigRepository(application)
    
    // 可用的洗车程序（从配置读取，通过StateFlow提供）
    private val _programs = MutableStateFlow<List<WashProgram>>(emptyList())
    val programs: StateFlow<List<WashProgram>> = _programs.asStateFlow()
    
    init {
        // 从配置读取程序列表
        viewModelScope.launch {
            configRepository.configFlow.collect { config ->
                _programs.value = config.programs.map { programConfig ->
                    WashProgram(
                        id = programConfig.id,
                        name = programConfig.nameKey,  // 实际应从资源获取，这里先用 key
                        minutes = programConfig.minutes,
                        price = programConfig.price,
                        addons = programConfig.addons
                    )
                }
            }
        }
    }
    
    // 选中的程序
    private val _selectedProgram = MutableStateFlow<WashProgram?>(null)
    val selectedProgram: StateFlow<WashProgram?> = _selectedProgram.asStateFlow()
    
    /**
     * 选择洗车程序
     */
    fun selectProgram(programId: String) {
        Log.d(TAG, "选择洗车程序，programId: $programId")
        viewModelScope.launch {
            val program = programs.value.find { it.id == programId }
            _selectedProgram.value = program
        }
    }
    
    /**
     * 重置选中的程序
     */
    fun resetSelectedProgram() {
        Log.d(TAG, "重置选中的程序")
        _selectedProgram.value = null
    }
    
    /**
     * 更新程序配置（供后台管理使用）
     */
    fun updateProgramConfig(programId: String, minutes: Int? = null, price: Double? = null, addons: List<String>? = null) {
        viewModelScope.launch {
            configRepository.updateProgram(programId, minutes, price, addons)
            Log.d(TAG, "程序配置已更新: $programId")
        }
    }
    
    /**
     * 重置配置为默认值
     */
    fun resetConfig() {
        viewModelScope.launch {
            configRepository.resetToDefault()
            Log.d(TAG, "配置已重置为默认值")
        }
    }
}
