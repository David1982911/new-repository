package com.carwash.carpayment.data.user

import java.util.UUID

/**
 * 用户实体（V3.4 规范）
 * 
 * 字段定义：
 * - userId: UUID，系统生成
 * - username: String，管理员创建
 * - passwordHash: String，系统加密
 * - role: ADMIN / OPERATOR / TECHNICIAN，管理员设定
 * - isActive: Boolean，管理员控制
 * - createdAt: Long，系统生成
 */
data class User(
    val userId: String = UUID.randomUUID().toString(),
    val username: String,
    val passwordHash: String,
    val role: UserRole,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * 检查用户是否有指定权限
     */
    fun hasPermission(permission: Permission): Boolean {
        if (!isActive) return false
        
        return when (role) {
            UserRole.ADMIN -> true  // Admin 拥有全部权限
            UserRole.OPERATOR -> permission in OPERATOR_PERMISSIONS
            UserRole.TECHNICIAN -> permission in TECHNICIAN_PERMISSIONS
        }
    }
}

/**
 * 权限枚举（V3.4 规范）
 */
enum class Permission {
    /**
     * 进入 Admin Console
     */
    ACCESS_ADMIN_CONSOLE,
    
    /**
     * 订单管理（查看、搜索）
     */
    VIEW_ORDERS,
    
    /**
     * 报表管理（查看、导出）
     */
    VIEW_REPORTS,
    
    /**
     * 导出报表（CSV/PDF）
     */
    EXPORT_REPORTS,
    
    /**
     * 账户管理（创建、修改、删除用户）
     */
    MANAGE_USERS,
    
    /**
     * Device Test（访问设备测试页面）
     */
    ACCESS_DEVICE_TEST,
    
    /**
     * 手动退款
     */
    MANUAL_REFUND,
    
    /**
     * 关账
     */
    CLOSE_DAY
}

/**
 * Operator 权限列表
 */
private val OPERATOR_PERMISSIONS = setOf(
    Permission.ACCESS_ADMIN_CONSOLE,
    Permission.VIEW_ORDERS,
    Permission.VIEW_REPORTS,
    Permission.EXPORT_REPORTS
)

/**
 * Technician 权限列表
 */
private val TECHNICIAN_PERMISSIONS = setOf(
    Permission.ACCESS_DEVICE_TEST
)
