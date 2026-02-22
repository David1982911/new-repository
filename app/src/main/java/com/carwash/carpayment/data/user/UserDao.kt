package com.carwash.carpayment.data.user

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * User DAO（V3.4 规范）
 */
@Dao
interface UserDao {
    /**
     * 获取所有用户
     */
    @Query("SELECT * FROM users ORDER BY createdAt DESC")
    fun getAllUsers(): Flow<List<UserEntity>>
    
    /**
     * 根据用户名查找用户
     */
    @Query("SELECT * FROM users WHERE username = :username LIMIT 1")
    suspend fun findByUsername(username: String): UserEntity?
    
    /**
     * 根据用户ID查找用户
     */
    @Query("SELECT * FROM users WHERE userId = :userId LIMIT 1")
    suspend fun findById(userId: String): UserEntity?
    
    /**
     * 插入用户
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(user: UserEntity)
    
    /**
     * 更新用户
     */
    @Update
    suspend fun update(user: UserEntity)
    
    /**
     * 删除用户
     */
    @Query("DELETE FROM users WHERE userId = :userId")
    suspend fun delete(userId: String)
    
    /**
     * 检查是否存在用户
     */
    @Query("SELECT COUNT(*) FROM users")
    suspend fun getUserCount(): Int
    
    /**
     * 检查是否存在指定用户名的用户
     */
    @Query("SELECT COUNT(*) FROM users WHERE username = :username")
    suspend fun existsByUsername(username: String): Int
}
