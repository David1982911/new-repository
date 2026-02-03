package com.carwash.carpayment.data.config

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 程序配置存储键
 */
private val CONFIG_KEY = stringPreferencesKey("wash_program_config")

/**
 * DataStore 扩展属性
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "program_config")

/**
 * 洗车程序配置仓库
 * 负责程序配置的本地存储和读取
 */
class ProgramConfigRepository(private val context: Context) {
    
    companion object {
        private const val TAG = "ProgramConfigRepository"
        
        // 默认配置（价格统一调整为 5€/10€/15€，方便测试）
        private val DEFAULT_CONFIG = WashProgramConfigList(
            programs = listOf(
                WashProgramConfig(
                    id = "quick",
                    nameKey = "program_quick",
                    minutes = 5,
                    price = 5.00,  // 基础套餐：5 欧元（500 分）
                    addons = listOf("Basisreinigung", "Trocknung")
                ),
                WashProgramConfig(
                    id = "standard",
                    nameKey = "program_standard",
                    minutes = 10,
                    price = 10.00,  // 标准套餐：10 欧元（1000 分）
                    addons = listOf("Basisreinigung", "Trocknung", "Felgenreinigung", "Wachsbehandlung")
                ),
                WashProgramConfig(
                    id = "premium",
                    nameKey = "program_premium",
                    minutes = 15,
                    price = 15.00,  // 高级套餐：15 欧元（1500 分）
                    addons = listOf(
                        "Basisreinigung",
                        "Trocknung",
                        "Felgenreinigung",
                        "Wachsbehandlung",
                        "Innenraumreinigung",
                        "Reifenpflege"
                    )
                )
            )
        )
    }
    
    /**
     * 获取配置流
     */
    val configFlow: Flow<WashProgramConfigList> = context.dataStore.data.map { preferences ->
        val configJson = preferences[CONFIG_KEY]
        if (configJson != null) {
            try {
                val loadedConfig = Json.decodeFromString<WashProgramConfigList>(configJson)
                Log.d(TAG, "========== ProgramConfigRepository 从 DataStore 加载配置 ==========")
                Log.d(TAG, "source=DataStore (本地持久化存储)")
                Log.d(TAG, "loadedConfig.programs=${loadedConfig.programs.map { "${it.id}: price=${it.price}€ (${(it.price * 100).toInt()}分)" }}")
                Log.d(TAG, "⚠️ 注意：如果这里显示旧价格，说明 DataStore 中保存了旧配置，需要清除缓存或重置")
                Log.d(TAG, "================================================================")
                loadedConfig
            } catch (e: Exception) {
                Log.e(TAG, "解析配置失败，使用默认配置", e)
                Log.d(TAG, "========== ProgramConfigRepository 使用默认配置 ==========")
                Log.d(TAG, "source=DEFAULT_CONFIG (硬编码默认值)")
                Log.d(TAG, "DEFAULT_CONFIG.programs=${DEFAULT_CONFIG.programs.map { "${it.id}: price=${it.price}€ (${(it.price * 100).toInt()}分)" }}")
                Log.d(TAG, "======================================================")
                DEFAULT_CONFIG
            }
        } else {
            // 首次使用，保存默认配置
            Log.d(TAG, "========== ProgramConfigRepository 首次使用，保存默认配置 ==========")
            Log.d(TAG, "source=DEFAULT_CONFIG (硬编码默认值，首次保存到 DataStore)")
            Log.d(TAG, "DEFAULT_CONFIG.programs=${DEFAULT_CONFIG.programs.map { "${it.id}: price=${it.price}€ (${(it.price * 100).toInt()}分)" }}")
            Log.d(TAG, "==============================================================")
            saveConfig(DEFAULT_CONFIG)
            DEFAULT_CONFIG
        }
    }
    
    /**
     * 获取当前配置
     */
    suspend fun getConfig(): WashProgramConfigList {
        return configFlow.first()
    }
    
    /**
     * 保存配置
     */
    suspend fun saveConfig(config: WashProgramConfigList) {
        try {
            val json = Json.encodeToString(config)
            context.dataStore.edit { preferences ->
                preferences[CONFIG_KEY] = json
            }
            Log.d(TAG, "配置已保存: ${config.programs.size} 个程序")
        } catch (e: Exception) {
            Log.e(TAG, "保存配置失败", e)
        }
    }
    
    /**
     * 更新单个程序的配置
     */
    suspend fun updateProgram(programId: String, minutes: Int? = null, price: Double? = null, addons: List<String>? = null) {
        val currentConfig = getConfig()
        val updatedPrograms = currentConfig.programs.map { program ->
            if (program.id == programId) {
                program.copy(
                    minutes = minutes ?: program.minutes,
                    price = price ?: program.price,
                    addons = addons ?: program.addons
                )
            } else {
                program
            }
        }
        saveConfig(WashProgramConfigList(updatedPrograms))
        Log.d(TAG, "程序配置已更新: $programId")
    }
    
    /**
     * 重置为默认配置
     */
    suspend fun resetToDefault() {
        saveConfig(DEFAULT_CONFIG)
        Log.d(TAG, "配置已重置为默认值")
    }
}
