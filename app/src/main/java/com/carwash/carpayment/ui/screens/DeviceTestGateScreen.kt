package com.carwash.carpayment.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.carwash.carpayment.R

/**
 * 设备测试密码保护入口
 * 进入设备测试页面前必须输入密码 888888
 */
@Composable
fun DeviceTestGateScreen(
    onPasswordCorrect: () -> Unit,
    onCancel: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // 固定密码：888888
    val CORRECT_PASSWORD = "888888"
    
    // 获取字符串资源（在 @Composable 函数中）
    val passwordErrorText = stringResource(R.string.password_gate_error)
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.password_gate_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        
        TextField(
            value = password,
            onValueChange = {
                password = it
                errorMessage = null  // 清除错误信息
            },
            label = { Text(stringResource(R.string.password_gate_hint)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password
            ),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            isError = errorMessage != null
        )
        
        if (errorMessage != null) {
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text(stringResource(R.string.password_gate_cancel))
            }
            
            Button(
                onClick = {
                    if (password == CORRECT_PASSWORD) {
                        onPasswordCorrect()
                    } else {
                        errorMessage = passwordErrorText
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.password_gate_enter))
            }
        }
    }
}
