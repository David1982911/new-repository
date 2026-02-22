package com.carwash.carpayment.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.carwash.carpayment.R
import com.carwash.carpayment.data.user.User
import com.carwash.carpayment.data.user.UserRole
import com.carwash.carpayment.ui.viewmodel.AdminUsersViewModel
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Admin Users 管理屏幕（V3.4 规范）
 */
@Composable
fun AdminUsersScreen(
    viewModel: AdminUsersViewModel,
    onBack: () -> Unit
) {
    val users by viewModel.users.collectAsState()
    val message by viewModel.message.collectAsState()
    val showCreateDialog by viewModel.showCreateDialog.collectAsState()
    val showEditDialog by viewModel.showEditDialog.collectAsState()
    val editingUser by viewModel.editingUser.collectAsState()
    
    // 显示消息
    LaunchedEffect(message) {
        message?.let {
            // TODO: 使用 Snackbar 显示消息
            kotlinx.coroutines.delay(3000)
            viewModel.clearMessage()
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 标题栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.admin_users_title),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Row {
                Button(
                    onClick = { viewModel.showCreateDialog() },
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(stringResource(R.string.create))
                }
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.back))
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 用户列表
        if (users.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.no_users_found))
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(users) { user ->
                    UserItemCard(
                        user = user,
                        onEdit = { viewModel.showEditDialog(user) },
                        onDelete = { viewModel.deleteUser(user.userId) }
                    )
                }
            }
        }
    }
    
    // 创建用户对话框
    if (showCreateDialog) {
        CreateUserDialog(
            onDismiss = { viewModel.hideCreateDialog() },
            onCreate = { username, password, role ->
                viewModel.createUser(username, password, role)
            }
        )
    }
    
    // 编辑用户对话框
    if (showEditDialog && editingUser != null) {
        EditUserDialog(
            user = editingUser!!,
            onDismiss = { viewModel.hideEditDialog() },
            onUpdate = { role, isActive ->
                viewModel.updateUser(editingUser!!, role, isActive)
            },
            onChangePassword = { newPassword ->
                viewModel.changePassword(editingUser!!.userId, newPassword)
            }
        )
    }
}

/**
 * 用户项卡片
 */
@Composable
private fun UserItemCard(
    user: User,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user.username,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Role: ${user.role.name}",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Status: ${if (user.isActive) stringResource(R.string.active) else stringResource(R.string.inactive)}",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Row {
                TextButton(onClick = onEdit) {
                    Text(stringResource(R.string.edit))
                }
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.delete))
                }
            }
        }
    }
}

/**
 * 创建用户对话框
 */
@Composable
private fun CreateUserDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, UserRole) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var selectedRole by remember { mutableStateOf(UserRole.OPERATOR) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_user)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.username)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                )
                
                Text("Role:")
                UserRole.values().forEach { role ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedRole == role,
                            onClick = { selectedRole = role }
                        )
                        Text(role.name)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (username.isNotBlank() && password.isNotBlank()) {
                        onCreate(username, password, selectedRole)
                    }
                },
                enabled = username.isNotBlank() && password.isNotBlank()
            ) {
                Text(stringResource(R.string.create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * 编辑用户对话框
 */
@Composable
private fun EditUserDialog(
    user: User,
    onDismiss: () -> Unit,
    onUpdate: (UserRole?, Boolean?) -> Unit,
    onChangePassword: (String) -> Unit
) {
    var selectedRole by remember { mutableStateOf(user.role) }
    var isActive by remember { mutableStateOf(user.isActive) }
    var newPassword by remember { mutableStateOf("") }
    var showPasswordField by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${stringResource(R.string.edit_user)}: ${user.username}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Role:")
                UserRole.values().forEach { role ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedRole == role,
                            onClick = { selectedRole = role }
                        )
                        Text(role.name)
                    }
                }
                
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isActive,
                        onCheckedChange = { isActive = it }
                    )
                    Text(stringResource(R.string.active))
                }
                
                Divider()
                
                TextButton(onClick = { showPasswordField = !showPasswordField }) {
                    Text(stringResource(R.string.change_password))
                }
                
                if (showPasswordField) {
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text(stringResource(R.string.new_password)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onUpdate(selectedRole, isActive)
                    if (showPasswordField && newPassword.isNotBlank()) {
                        onChangePassword(newPassword)
                    }
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
