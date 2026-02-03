package com.carwash.carpayment.data.printer

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

private const val TAG = "ReceiptSettingsRepository"
private const val DATA_STORE_NAME = "receipt_settings"

private val Context.receiptSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = DATA_STORE_NAME)

private object ReceiptSettingsKeys {
    val HEADER_TITLE = stringPreferencesKey("header_title")
    val STORE_ADDRESS = stringPreferencesKey("store_address")
}

/**
 * 小票配置 Repository
 * 使用 DataStore 持久化存储小票配置
 */
class ReceiptSettingsRepository(private val context: Context) {
    
    /**
     * 获取小票配置（Flow）
     */
    val receiptSettings: Flow<ReceiptSettings> = context.receiptSettingsDataStore.data.map { preferences ->
        ReceiptSettings(
            headerTitle = preferences[ReceiptSettingsKeys.HEADER_TITLE] ?: "Welcome to ChuangLi store",
            storeAddress = preferences[ReceiptSettingsKeys.STORE_ADDRESS] 
                ?: "Room 2035, 2nd Floor, Chengyun Building, Shenzhou Industrial Zone, Bantian, Longgang District, Shenzhen"
        )
    }
    
    /**
     * 保存小票配置
     */
    suspend fun saveSettings(settings: ReceiptSettings) {
        try {
            context.receiptSettingsDataStore.edit { preferences ->
                preferences[ReceiptSettingsKeys.HEADER_TITLE] = settings.headerTitle
                preferences[ReceiptSettingsKeys.STORE_ADDRESS] = settings.storeAddress
            }
            Log.d(TAG, "小票配置已保存: headerTitle=${settings.headerTitle}, storeAddress=${settings.storeAddress}")
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
