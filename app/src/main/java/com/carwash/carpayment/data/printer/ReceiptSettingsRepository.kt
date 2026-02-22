package com.carwash.carpayment.data.printer

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val TAG = "ReceiptSettingsRepository"
private const val DATA_STORE_NAME = "receipt_settings"

private val Context.receiptSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = DATA_STORE_NAME)

private object ReceiptSettingsKeys {
    // V3.4 新增字段
    val MERCHANT_NAME = stringPreferencesKey("merchant_name")
    val ADDRESS = stringPreferencesKey("address")
    val PHONE = stringPreferencesKey("phone")
    val TERMINAL_ID = stringPreferencesKey("terminal_id")
    val SHOW_ADDRESS = booleanPreferencesKey("show_address")
    val SHOW_PHONE = booleanPreferencesKey("show_phone")
    val SHOW_TERMINAL_ID = booleanPreferencesKey("show_terminal_id")
    
    // 兼容旧字段
    @Deprecated("使用 MERCHANT_NAME", ReplaceWith("MERCHANT_NAME"))
    val HEADER_TITLE = stringPreferencesKey("header_title")
    @Deprecated("使用 ADDRESS", ReplaceWith("ADDRESS"))
    val STORE_ADDRESS = stringPreferencesKey("store_address")
}

/**
 * 小票配置 Repository
 * 使用 DataStore 持久化存储小票配置
 */
class ReceiptSettingsRepository(private val context: Context) {
    
    /**
     * 获取小票配置（Flow）
     * V3.4 规范：支持新字段，兼容旧字段
     */
    val receiptSettings: Flow<ReceiptSettings> = context.receiptSettingsDataStore.data.map { preferences ->
        // 优先使用新字段，降级到旧字段（兼容性）
        val merchantName = preferences[ReceiptSettingsKeys.MERCHANT_NAME] 
            ?: preferences[ReceiptSettingsKeys.HEADER_TITLE] 
            ?: "Welcome to ChuangLi store"
        val address = preferences[ReceiptSettingsKeys.ADDRESS] 
            ?: preferences[ReceiptSettingsKeys.STORE_ADDRESS] 
            ?: "Room 2035, 2nd Floor, Chengyun Building, Shenzhou Industrial Zone, Bantian, Longgang District, Shenzhen"
        
        ReceiptSettings(
            merchantName = merchantName,
            address = address,
            phone = preferences[ReceiptSettingsKeys.PHONE] ?: "",
            terminalId = preferences[ReceiptSettingsKeys.TERMINAL_ID] ?: "",
            showAddress = preferences[ReceiptSettingsKeys.SHOW_ADDRESS] ?: true,
            showPhone = preferences[ReceiptSettingsKeys.SHOW_PHONE] ?: true,
            showTerminalId = preferences[ReceiptSettingsKeys.SHOW_TERMINAL_ID] ?: true
        )
    }
    
    /**
     * 保存小票配置
     * V3.4 规范：保存新字段，同时更新旧字段（兼容性）
     */
    suspend fun saveSettings(settings: ReceiptSettings) {
        try {
            context.receiptSettingsDataStore.edit { preferences ->
                // V3.4 新字段
                preferences[ReceiptSettingsKeys.MERCHANT_NAME] = settings.merchantName
                preferences[ReceiptSettingsKeys.ADDRESS] = settings.address
                preferences[ReceiptSettingsKeys.PHONE] = settings.phone
                preferences[ReceiptSettingsKeys.TERMINAL_ID] = settings.terminalId
                preferences[ReceiptSettingsKeys.SHOW_ADDRESS] = settings.showAddress
                preferences[ReceiptSettingsKeys.SHOW_PHONE] = settings.showPhone
                preferences[ReceiptSettingsKeys.SHOW_TERMINAL_ID] = settings.showTerminalId
                
                // 兼容旧字段（同时更新）
                preferences[ReceiptSettingsKeys.HEADER_TITLE] = settings.merchantName
                preferences[ReceiptSettingsKeys.STORE_ADDRESS] = settings.address
            }
            Log.d(TAG, "小票配置已保存: merchantName=${settings.merchantName}, address=${settings.address}, phone=${settings.phone}, terminalId=${settings.terminalId}")
        } catch (e: Exception) {
            Log.e(TAG, "保存小票配置失败", e)
            throw e
        }
    }
    
    /**
     * 获取当前小票配置（同步）
     */
    suspend fun getCurrentSettings(): ReceiptSettings {
        return receiptSettings.first()
    }
}
