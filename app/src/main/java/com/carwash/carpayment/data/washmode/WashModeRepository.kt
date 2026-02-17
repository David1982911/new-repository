package com.carwash.carpayment.data.washmode

import android.app.Application
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 洗车模式仓库
 */
class WashModeRepository(
    private val washModeDao: WashModeDao
) {
    
    companion object {
        private const val TAG = "WashModeRepository"
    }
    
    /**
     * 获取所有启用的洗车模式（按排序顺序）
     */
    fun getAllActiveWashModes(): Flow<List<WashMode>> {
        android.util.Log.d("HomeDebug", "WashModeRepository.getAllActiveWashModes: 开始查询")
        return washModeDao.getAllActiveWashModes()
    }
    
    /**
     * 获取所有洗车模式（按排序顺序）
     */
    fun getAllWashModes(): Flow<List<WashMode>> {
        android.util.Log.d("HomeDebug", "WashModeRepository.getAllWashModes: 开始查询")
        return washModeDao.getAllWashModes()
    }
    
    /**
     * 根据ID获取洗车模式
     */
    suspend fun getWashModeById(id: Int): WashMode? {
        return washModeDao.getWashModeById(id)
    }
    
    /**
     * 插入洗车模式
     */
    suspend fun insert(washMode: WashMode) {
        washModeDao.insert(washMode)
        Log.d(TAG, "插入洗车模式: ${washMode.name}")
    }
    
    /**
     * 批量插入洗车模式
     */
    suspend fun insertAll(washModes: List<WashMode>) {
        washModeDao.insertAll(washModes)
        Log.d(TAG, "批量插入洗车模式: ${washModes.size} 个")
    }
    
    /**
     * 更新洗车模式
     */
    suspend fun update(washMode: WashMode) {
        washModeDao.update(washMode)
        Log.d(TAG, "更新洗车模式: ${washMode.name}")
    }
    
    /**
     * 删除洗车模式
     */
    suspend fun delete(washMode: WashMode) {
        washModeDao.delete(washMode)
        Log.d(TAG, "删除洗车模式: ${washMode.name}")
    }
    
    /**
     * 检查是否有数据
     */
    suspend fun hasData(): Boolean {
        return washModeDao.getAllWashModes().first().isNotEmpty()
    }
    
    /**
     * 确保默认数据存在（如果数据库为空，则插入默认数据）
     */
    suspend fun ensureDefaultData() {
        val count = washModeDao.count()
        android.util.Log.d("HomeDebug", "WashModeRepository.ensureDefaultData: 当前记录数 = $count")
        
        if (count == 0) {
            android.util.Log.d("HomeDebug", "数据库为空，插入默认数据...")
            val defaultWashModes = listOf(
                WashMode(
                    name = "basic_wash",
                    description = "basic_desc",
                    price = 5.0,
                    sortOrder = 1,
                    durationMinutes = 10,
                    isActive = true,
                    imageResId = com.carwash.carpayment.R.drawable.banner_basic_fixed
                ),
                WashMode(
                    name = "standard_wash",
                    description = "standard_desc",
                    price = 10.0,
                    sortOrder = 2,
                    durationMinutes = 15,
                    isActive = true,
                    imageResId = com.carwash.carpayment.R.drawable.banner_standard_fixed
                ),
                WashMode(
                    name = "premium_wash",
                    description = "premium_desc",
                    price = 15.0,
                    sortOrder = 3,
                    durationMinutes = 20,
                    isActive = true,
                    imageResId = com.carwash.carpayment.R.drawable.banner_premium_fixed
                ),
                WashMode(
                    name = "vip_wash",
                    description = "vip_desc",
                    price = 20.0,
                    sortOrder = 4,
                    durationMinutes = 30,
                    isActive = true,
                    imageResId = com.carwash.carpayment.R.drawable.banner_vip_fixed
                )
            )
            washModeDao.insertAll(defaultWashModes)
            android.util.Log.d("HomeDebug", "✅ 默认数据插入完成: ${defaultWashModes.size} 条记录")
        } else {
            android.util.Log.d("HomeDebug", "数据库已有数据，跳过插入")
        }
    }
}
