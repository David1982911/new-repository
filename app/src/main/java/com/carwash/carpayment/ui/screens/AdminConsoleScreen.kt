package com.carwash.carpayment.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.carwash.carpayment.R

/**
 * Admin Console 主屏幕（V3.4 规范）
 * 
 * 功能模块：
 * - Orders（订单管理）
 * - Reports（报表管理）
 * - User Management（用户管理）
 */
@Composable
fun AdminConsoleScreen(
    onBack: () -> Unit,
    onNavigateToOrders: () -> Unit,
    onNavigateToReports: () -> Unit,
    onNavigateToUserManagement: () -> Unit,
    canAccessUserManagement: Boolean = false  // 只有 Admin 可以访问
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(16.dp)
    ) {
        // 标题栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.admin_console_title),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.back))
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // 功能模块卡片
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 订单管理
            AdminConsoleCard(
                title = stringResource(R.string.admin_orders_title),
                description = stringResource(R.string.admin_orders_description),
                onClick = onNavigateToOrders,
                modifier = Modifier.fillMaxWidth()
            )
            
            // 报表管理
            AdminConsoleCard(
                title = stringResource(R.string.admin_reports_title),
                description = stringResource(R.string.admin_reports_description),
                onClick = onNavigateToReports,
                modifier = Modifier.fillMaxWidth()
            )
            
            // 用户管理（仅 Admin 可见）
            if (canAccessUserManagement) {
                AdminConsoleCard(
                    title = stringResource(R.string.admin_users_title),
                    description = stringResource(R.string.admin_users_description),
                    onClick = onNavigateToUserManagement,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Admin Console 功能卡片
 */
@Composable
private fun AdminConsoleCard(
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = description,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
