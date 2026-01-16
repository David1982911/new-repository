package com.carwash.carpayment.data.cashdevice

/**
 * Token 存储（内存缓存）
 */
object TokenStore {
    @Volatile
    private var token: String? = null
    
    /**
     * 获取 token
     */
    fun getToken(): String? = token
    
    /**
     * 设置 token
     */
    fun setToken(newToken: String?) {
        token = newToken
    }
    
    /**
     * 清空 token
     */
    fun clearToken() {
        token = null
    }
    
    /**
     * 检查是否有 token
     */
    fun hasToken(): Boolean = token != null
}
