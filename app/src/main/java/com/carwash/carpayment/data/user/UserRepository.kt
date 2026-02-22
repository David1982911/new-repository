package com.carwash.carpayment.data.user

import android.content.Context
import android.util.Log
import com.carwash.carpayment.data.transaction.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

private const val TAG = "UserRepository"

/**
 * User Repository（V3.4 规范）
 */
class UserRepository(context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val userDao = database.userDao()
    
    /**
     * 获取所有用户
     */
    fun getAllUsers(): Flow<List<User>> {
        return userDao.getAllUsers().map { entities ->
            entities.map { it.toUser() }
        }
    }
    
    /**
     * 根据用户名查找用户
     */
    suspend fun findByUsername(username: String): User? {
        return userDao.findByUsername(username)?.toUser()
    }
    
    /**
     * 根据用户ID查找用户
     */
    suspend fun findById(userId: String): User? {
        return userDao.findById(userId)?.toUser()
    }
    
    /**
     * 创建用户
     */
    suspend fun createUser(username: String, password: String, role: UserRole): Result<User> {
        return try {
            // 检查用户名是否已存在
            if (userDao.existsByUsername(username) > 0) {
                return Result.failure(Exception("Username already exists"))
            }
            
            val passwordHash = hashPassword(password)
            val user = User(
                username = username,
                passwordHash = passwordHash,
                role = role
            )
            userDao.insert(UserEntity.fromUser(user))
            Log.d(TAG, "用户创建成功: username=$username, role=${role.name}")
            Result.success(user)
        } catch (e: Exception) {
            Log.e(TAG, "创建用户失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 更新用户
     */
    suspend fun updateUser(user: User): Result<Unit> {
        return try {
            userDao.update(UserEntity.fromUser(user.copy(updatedAt = System.currentTimeMillis())))
            Log.d(TAG, "用户更新成功: userId=${user.userId}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "更新用户失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 删除用户
     */
    suspend fun deleteUser(userId: String): Result<Unit> {
        return try {
            userDao.delete(userId)
            Log.d(TAG, "用户删除成功: userId=$userId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "删除用户失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 验证用户密码
     */
    suspend fun verifyPassword(username: String, password: String): Result<User> {
        return try {
            val user = userDao.findByUsername(username)
            if (user == null) {
                return Result.failure(Exception("User not found"))
            }
            
            if (!user.isActive) {
                return Result.failure(Exception("User is inactive"))
            }
            
            val passwordHash = hashPassword(password)
            if (user.passwordHash != passwordHash) {
                return Result.failure(Exception("Invalid password"))
            }
            
            Result.success(user.toUser())
        } catch (e: Exception) {
            Log.e(TAG, "验证密码失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 修改用户密码
     */
    suspend fun changePassword(userId: String, newPassword: String): Result<Unit> {
        return try {
            val userEntity = userDao.findById(userId)
            if (userEntity == null) {
                return Result.failure(Exception("User not found"))
            }
            
            val newPasswordHash = hashPassword(newPassword)
            val updatedUser = userEntity.copy(
                passwordHash = newPasswordHash,
                updatedAt = System.currentTimeMillis()
            )
            userDao.update(updatedUser)
            Log.d(TAG, "密码修改成功: userId=$userId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "修改密码失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 密码哈希（SHA-256）
     * V3.4: 简单的密码加密，生产环境应使用更安全的方案（如 bcrypt）
     */
    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(password.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
