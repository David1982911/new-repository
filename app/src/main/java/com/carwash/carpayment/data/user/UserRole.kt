package com.carwash.carpayment.data.user

/**
 * 用户角色（V3.4 规范：RBAC）
 */
enum class UserRole {
    /**
     * 管理员：全部权限
     */
    ADMIN,
    
    /**
     * 操作员：Orders/Reports 只读+导出，不能访问账户管理
     */
    OPERATOR,
    
    /**
     * 技术人员：只能访问 Device Test
     */
    TECHNICIAN
}
