package com.carwash.carpayment.data.user

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * User 数据库实体（V3.4 规范）
 */
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey
    val userId: String,
    val username: String,
    val passwordHash: String,
    val role: String,  // ADMIN, OPERATOR, TECHNICIAN
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * 转换为业务模型
     */
    fun toUser(): User {
        return User(
            userId = userId,
            username = username,
            passwordHash = passwordHash,
            role = UserRole.valueOf(role),
            isActive = isActive,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
    
    companion object {
        /**
         * 从业务模型创建实体
         */
        fun fromUser(user: User): UserEntity {
            return UserEntity(
                userId = user.userId,
                username = user.username,
                passwordHash = user.passwordHash,
                role = user.role.name,
                isActive = user.isActive,
                createdAt = user.createdAt,
                updatedAt = user.updatedAt
            )
        }
    }
}
