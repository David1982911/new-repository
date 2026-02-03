package com.carwash.carpayment.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carwash.carpayment.ui.viewmodel.PrinterTabViewModel

/**
 * 打印机 Tab UI
 */
@Composable
fun PrinterTab(
    viewModel: PrinterTabViewModel
) {
    val settings by viewModel.settings.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    
    // 生命周期管理：进入 Tab 时注册接收器，离开时取消注册
    DisposableEffect(Unit) {
        viewModel.onEnterScreen()
        onDispose {
            viewModel.onLeaveScreen()
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 小票配置区
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "小票配置",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                
                HorizontalDivider()
                
                // Header Title 输入框
                TextField(
                    value = settings.headerTitle,
                    onValueChange = { viewModel.updateHeaderTitle(it) },
                    label = { Text("Header Title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Welcome to ChuangLi store") }
                )
                
                // Store Address 输入框（多行）
                TextField(
                    value = settings.storeAddress,
                    onValueChange = { viewModel.updateStoreAddress(it) },
                    label = { Text("Store Address") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    minLines = 3,
                    placeholder = { Text("Room 2035, 2nd Floor, ...") }
                )
                
                // 保存按钮
                Button(
                    onClick = { viewModel.saveSettings() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = "保存配置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        
        // 打印机测试区
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isConnected)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "打印机测试",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                
                HorizontalDivider()
                
                // 连接状态
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "连接状态",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    
                    Surface(
                        color = if (isConnected)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = if (isConnected) "已连接" else "未连接",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = if (isConnected)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onError,
                            fontSize = 14.sp
                        )
                    }
                }
                
                // 错误信息
                if (errorMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMessage!!,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 14.sp
                        )
                    }
                }
                
                // 状态信息
                if (statusMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = statusMessage!!,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontSize = 14.sp
                        )
                    }
                }
                
                HorizontalDivider()
                
                // 连接/断开按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!isConnected) {
                        Button(
                            onClick = { viewModel.connectPrinter() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text("连接打印机")
                        }
                    } else {
                        Button(
                            onClick = { viewModel.disconnectPrinter() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("断开连接")
                        }
                    }
                }
                
                // 测试打印按钮
                Button(
                    onClick = { viewModel.testPrint() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isConnected,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text(
                        text = "测试打印",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
