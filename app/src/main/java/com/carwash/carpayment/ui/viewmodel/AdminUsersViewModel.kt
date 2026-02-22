package com.carwash.carpayment.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carwash.carpayment.data.user.User
import com.carwash.carpayment.data.user.UserRepository
import com.carwash.carpayment.data.user.UserRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import android.util.Log

private const val TAG = "AdminUsersViewModel"

/**
 * Admin Users ViewModel（V3.4 规范）
 */
class AdminUsersViewModel(application: Application) : AndroidViewModel(application) {
    
    private val userRepository = UserRepository(application)
    
    /**
     * 所有用户
     */
    val users: StateFlow<List<User>> = userRepository.getAllUsers()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    /**
     * 操作结果消息
     */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    
    /**
     * 是否显示创建用户对话框
     */
    private val _showCreateDialog = MutableStateFlow(false)
    val showCreateDialog: StateFlow<Boolean> = _showCreateDialog.asStateFlow()
    
    /**
     * 是否显示编辑用户对话框
     */
    private val _showEditDialog = MutableStateFlow(false)
    val showEditDialog: StateFlow<Boolean> = _showEditDialog.asStateFlow()
    
    /**
     * 当前编辑的用户
     */
    private val _editingUser = MutableStateFlow<User?>(null)
    val editingUser: StateFlow<User?> = _editingUser.asStateFlow()
    
    /**
     * 显示创建用户对话框
     */
    fun showCreateDialog() {
        _showCreateDialog.value = true
    }
    
    /**
     * 隐藏创建用户对话框
     */
    fun hideCreateDialog() {
        _showCreateDialog.value = false
    }
    
    /**
     * 显示编辑用户对话框
     */
    fun showEditDialog(user: User) {
        _editingUser.value = user
        _showEditDialog.value = true
    }
    
    /**
     * 隐藏编辑用户对话框
     */
    fun hideEditDialog() {
        _editingUser.value = null
        _showEditDialog.value = false
    }
    
    /**
     * 创建用户
     */
    fun createUser(username: String, password: String, role: UserRole) {
        viewModelScope.launch {
            val result = userRepository.createUser(username, password, role)
            result.exceptionOrNull()?.let { error ->
                _message.value = "Failed to create user: ${error.message}"
                return@launch
            }
            _message.value = "User created successfully"
            _showCreateDialog.value = false
        }
    }
    
    /**
     * 更新用户
     */
    fun updateUser(user: User, newRole: UserRole? = null, newIsActive: Boolean? = null) {
        viewModelScope.launch {
            val updatedUser = user.copy(
                role = newRole ?: user.role,
                isActive = newIsActive ?: user.isActive,
                updatedAt = System.currentTimeMillis()
            )
            val result = userRepository.updateUser(updatedUser)
            result.exceptionOrNull()?.let { error ->
                _message.value = "Failed to update user: ${error.message}"
                return@launch
            }
            _message.value = "User updated successfully"
            _showEditDialog.value = false
        }
    }
    
    /**
     * 删除用户
     */
    fun deleteUser(userId: String) {
        viewModelScope.launch {
            val result = userRepository.deleteUser(userId)
            result.exceptionOrNull()?.let { error ->
                _message.value = "Failed to delete user: ${error.message}"
                return@launch
            }
            _message.value = "User deleted successfully"
        }
    }
    
    /**
     * 修改用户密码
     */
    fun changePassword(userId: String, newPassword: String) {
        viewModelScope.launch {
            val result = userRepository.changePassword(userId, newPassword)
            result.exceptionOrNull()?.let { error ->
                _message.value = "Failed to change password: ${error.message}"
                return@launch
            }
            _message.value = "Password changed successfully"
        }
    }
    
    /**
     * 清除消息
     */
    fun clearMessage() {
        _message.value = null
    }
}
