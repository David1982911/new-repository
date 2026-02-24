package com.carwash.carpayment.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carwash.carpayment.data.WashProgram
import com.carwash.carpayment.data.config.ProgramConfigRepository
import com.carwash.carpayment.data.config.WashProgramConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Wash Mode Test ViewModel
 */
class WashModeTestViewModel(application: Application) : AndroidViewModel(application) {
    
    private val programConfigRepository = ProgramConfigRepository(application)
    
    private val _programs = MutableStateFlow<List<WashProgram>>(emptyList())
    val programs: StateFlow<List<WashProgram>> = _programs.asStateFlow()
    
    init {
        loadPrograms()
    }
    
    private fun loadPrograms() {
        viewModelScope.launch {
            programConfigRepository.configFlow.map { config ->
                // 将 WashProgramConfig 转换为 WashProgram
                config.programs.map { configProgram ->
                    WashProgram(
                        id = configProgram.id,
                        name = configProgram.nameKey, // 使用 nameKey 作为临时名称，实际应该从资源获取
                        minutes = configProgram.minutes,
                        price = configProgram.price,
                        addons = configProgram.addons
                    )
                }
            }.collect { programs ->
                _programs.value = programs
            }
        }
    }
    
    fun updateProgram(updatedProgram: WashProgram) {
        viewModelScope.launch {
            // 将 WashProgram 转换为 WashProgramConfig 并更新
            val configProgram = WashProgramConfig(
                id = updatedProgram.id,
                nameKey = updatedProgram.name, // 临时处理，实际应该保存 nameKey
                minutes = updatedProgram.minutes,
                price = updatedProgram.price,
                addons = updatedProgram.addons
            )
            programConfigRepository.updateProgram(configProgram)
            loadPrograms()
        }
    }
    
    fun deleteProgram(programId: String) {
        viewModelScope.launch {
            programConfigRepository.deleteProgram(programId)
            loadPrograms()
        }
    }
}
