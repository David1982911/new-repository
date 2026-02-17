package com.carwash.carpayment.data.washmode

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 洗车模式数据访问对象
 */
@Dao
interface WashModeDao {
    
    /**
     * 获取所有启用的洗车模式（按排序顺序）
     */
    @Query("SELECT * FROM wash_mode WHERE isActive = 1 ORDER BY sortOrder ASC")
    fun getAllActiveWashModes(): Flow<List<WashMode>>
    
    /**
     * 获取所有洗车模式（按排序顺序）
     */
    @Query("SELECT * FROM wash_mode ORDER BY sortOrder ASC")
    fun getAllWashModes(): Flow<List<WashMode>>
    
    /**
     * 根据ID获取洗车模式
     */
    @Query("SELECT * FROM wash_mode WHERE id = :id")
    suspend fun getWashModeById(id: Int): WashMode?
    
    /**
     * 插入洗车模式
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(washMode: WashMode)
    
    /**
     * 批量插入洗车模式
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(washModes: List<WashMode>)
    
    /**
     * 更新洗车模式
     */
    @Update
    suspend fun update(washMode: WashMode)
    
    /**
     * 删除洗车模式
     */
    @Delete
    suspend fun delete(washMode: WashMode)
    
    /**
     * 删除所有洗车模式
     */
    @Query("DELETE FROM wash_mode")
    suspend fun deleteAll()
    
    /**
     * 获取洗车模式数量
     */
    @Query("SELECT COUNT(*) FROM wash_mode")
    suspend fun count(): Int
}
