package com.carwash.carpayment.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carwash.carpayment.data.user.User
import com.carwash.carpayment.data.user.UserRepository
import com.carwash.carpayment.data.user.Permission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.util.Log

private const val TAG = "AuthViewModel"

/**
 * 认证 ViewModel（V3.4 规范：RBAC）
 */
class AuthViewModel(application: Application) : AndroidViewModel(application) {
    
    private val userRepository = UserRepository(application)
    
    /**
     * 当前登录用户
     */
    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()
    
    /**
     * 登录状态
     */
    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()
    
    /**
     * 登录错误信息
     */
    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()
    
    /**
     * 登录
     */
    fun login(username: String, password: String) {
        viewModelScope.launch {
            _loginError.value = null
            val result = userRepository.verifyPassword(username, password)
            result.fold(
                onSuccess = { user ->
                    _currentUser.value = user
                    _isAuthenticated.value = true
                    Log.d(TAG, "登录成功: username=${user.username}, role=${user.role}")
                },
                onFailure = { error ->
                    _loginError.value = error.message ?: "Login failed"
                    _isAuthenticated.value = false
                    Log.e(TAG, "登录失败: ${error.message}")
                }
            )
        }
    }
    
    /**
     * 登出
     */
    fun logout() {
        _currentUser.value = null
        _isAuthenticated.value = false
        _loginError.value = null
        Log.d(TAG, "用户已登出")
    }
    
    /**
     * 检查用户是否有指定权限
     */
    fun hasPermission(permission: Permission): Boolean {
        val user = _currentUser.value
        return user?.hasPermission(permission) ?: false
    }
    
    /**
     * 检查用户是否可以访问 Admin Console
     */
    fun canAccessAdminConsole(): Boolean {
        return hasPermission(Permission.ACCESS_ADMIN_CONSOLE)
    }
    
    /**
     * 检查用户是否可以访问 Device Test
     */
    fun canAccessDeviceTest(): Boolean {
        return hasPermission(Permission.ACCESS_DEVICE_TEST)
    }
}
