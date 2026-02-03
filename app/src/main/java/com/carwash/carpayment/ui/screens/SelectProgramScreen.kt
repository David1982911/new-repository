package com.carwash.carpayment.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import com.carwash.carpayment.BuildConfig
import com.carwash.carpayment.R
import com.carwash.carpayment.data.WashProgram
import com.carwash.carpayment.data.carwash.CarWashGateCheckResult
import com.carwash.carpayment.ui.theme.KioskCardSizes
import com.carwash.carpayment.ui.viewmodel.HomeViewModel
import com.carwash.carpayment.ui.viewmodel.LanguageViewModel
import com.carwash.carpayment.ui.viewmodel.PaymentViewModel

/**
 * 页面1: 首页（选择洗车程序）
 */
@Composable
fun SelectProgramScreen(
    homeViewModel: HomeViewModel,
    languageViewModel: LanguageViewModel,
    onProgramSelected: (String) -> Unit,
    onShowTransactionList: () -> Unit = {},
    onShowDeviceTest: () -> Unit = {},
    paymentViewModel: PaymentViewModel? = null  // 可选，用于显示 GateCheck 错误
) {
    val programs by homeViewModel.programs.collectAsState()
    val currentLanguage by languageViewModel.currentLanguage.collectAsState()
    val gateCheckResult: CarWashGateCheckResult? = if (paymentViewModel != null) {
        val result by paymentViewModel.gateCheckResult.collectAsState()
        result
    } else {
        null
    }
    
    // 隐藏入口：连续点击 Logo 5 次
    var logoClickCount by remember { mutableLongStateOf(0L) }
    var lastClickTime by remember { mutableLongStateOf(0L) }
    
    // ⚠️ 打点：打印价格来源（定位UI显示的价格到底从哪里来）
    Log.d("ProgramPrice", "========== SelectProgramScreen 价格来源 ==========")
    Log.d("ProgramPrice", "source=HomeViewModel.programs (来自 ProgramConfigRepository.configFlow)")
    Log.d("ProgramPrice", "programs=${programs.map { "${it.id}: price=${it.price}€ (${(it.price * 100).toInt()}分)" }}")
    Log.d("ProgramPrice", "programsCount=${programs.size}")
    programs.forEach { program ->
        Log.d("ProgramPrice", "  - id=${program.id}, name=${program.name}, price=${program.price}€, priceCents=${(program.price * 100).toInt()}")
    }
    Log.d("ProgramPrice", "================================================")
    
    Log.d("SelectProgramScreen", "渲染首页，程序数量: ${programs.size}, 当前语言: $currentLanguage")
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 顶部：Logo + 欢迎语 + 语言切换
        TopBar(
            currentLanguage = currentLanguage,
            onLanguageToggle = { languageViewModel.toggleLanguage() },
            onLogoClick = {
                val currentTime = System.currentTimeMillis()
                // 如果距离上次点击超过 2 秒，重置计数
                if (currentTime - lastClickTime > 2000) {
                    logoClickCount = 0
                }
                lastClickTime = currentTime
                logoClickCount++
                
                Log.d("SelectProgramScreen", "Logo 点击次数: $logoClickCount")
                
                // 连续点击 5 次，打开交易列表
                if (logoClickCount >= 5) {
                    logoClickCount = 0
                    Log.d("SelectProgramScreen", "隐藏入口触发，打开交易列表")
                    onShowTransactionList()
                }
            },
            onLogoLongPress = {
                Log.d("SelectProgramScreen", "Logo 长按，打开设备测试")
                onShowDeviceTest()
            }
        )
        
        // 中间：洗车程序卡片列表
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            
            programs.forEach { program ->
                ProgramCard(
                    program = program,
                    onClick = {
                        Log.d("SelectProgramScreen", "点击选择程序，programId: ${program.id}")
                        homeViewModel.selectProgram(program.id)
                        onProgramSelected(program.id)
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        // GateCheck 失败提示（如果存在）
        gateCheckResult?.let { result ->
            if (result is CarWashGateCheckResult.Failed) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "⚠",
                            fontSize = 48.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                        // 根据错误原因映射到 stringResource
                        val configuration = LocalConfiguration.current
                        val currentLang = configuration.locales[0].language
                        val errorTitle = when (result.reason) {
                            com.carwash.carpayment.data.carwash.CarWashGateCheckFailureReason.COMMUNICATION_FAILED -> {
                                Log.e("I18N_CHECK", "showError=COMMUNICATION_FAILED lang=$currentLang")
                                Log.d("BUILD_MARK", "I18N_CarWash_Error_Resource_OK_20260128")
                                stringResource(R.string.error_carwash_comm_failed_title)
                            }
                            com.carwash.carpayment.data.carwash.CarWashGateCheckFailureReason.NOT_CONNECTED -> {
                                Log.e("I18N_CHECK", "showError=NOT_CONNECTED lang=$currentLang")
                                stringResource(R.string.error_device_not_connected)
                            }
                            else -> result.message  // 向后兼容
                        }
                        val errorBody = when (result.reason) {
                            com.carwash.carpayment.data.carwash.CarWashGateCheckFailureReason.COMMUNICATION_FAILED -> {
                                stringResource(R.string.error_carwash_comm_failed_body)
                            }
                            else -> stringResource(R.string.error_check_485_wiring)
                        }
                        Text(
                            text = errorTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = errorBody,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
        
        // 底部提示
        BottomHint()
        
        // 构建信息显示（开发阶段可见）
        BuildInfoDisplay()
    }
}

/**
 * 顶部栏：Logo + 欢迎语 + 语言切换
 */
@Composable
private fun TopBar(
    currentLanguage: com.carwash.carpayment.ui.viewmodel.AppLanguage,
    onLanguageToggle: () -> Unit,
    onLogoClick: () -> Unit = {},
    onLogoLongPress: () -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Logo占位（KIOSK 大尺寸，隐藏入口：连续点击 5 次打开交易列表，长按打开设备测试）
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onLogoClick)
                    .pointerInput(Unit) {
                        detectTapGestures(onLongPress = { _: Offset -> onLogoLongPress() })
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "CW",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
            
            // 欢迎语（KIOSK 大字号）
            Text(
                text = stringResource(R.string.welcome_message),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            
            // 语言切换按钮
            LanguageToggleButton(
                currentLanguage = currentLanguage,
                onClick = onLanguageToggle
            )
        }
    }
}

/**
 * 语言切换按钮（KIOSK 大尺寸，易于触摸）
 */
@Composable
private fun LanguageToggleButton(
    currentLanguage: com.carwash.carpayment.ui.viewmodel.AppLanguage,
    onClick: () -> Unit
) {
    val (deText, enText) = Pair(
        stringResource(R.string.language_de),
        stringResource(R.string.language_en)
    )
    
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(140.dp)  // 从 80.dp 增大到 140.dp
            .height(60.dp),  // 从 40.dp 增大到 60.dp
        shape = RoundedCornerShape(30.dp),  // 从 20.dp 增大到 30.dp，保持圆角比例
        color = if (currentLanguage == com.carwash.carpayment.ui.viewmodel.AppLanguage.GERMAN) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),  // 增加内边距，扩大点击热区
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = deText,
                fontSize = 20.sp,  // 从 14.sp 增大到 20.sp
                fontWeight = FontWeight.Bold,
                color = if (currentLanguage == com.carwash.carpayment.ui.viewmodel.AppLanguage.GERMAN) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Text(
                text = "|",
                fontSize = 20.sp,  // 从 14.sp 增大到 20.sp
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = enText,
                fontSize = 20.sp,  // 从 14.sp 增大到 20.sp
                fontWeight = FontWeight.Bold,
                color = if (currentLanguage == com.carwash.carpayment.ui.viewmodel.AppLanguage.ENGLISH) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}

/**
 * 洗车程序卡片
 */
@Composable
private fun ProgramCard(
    program: WashProgram,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = KioskCardSizes.CardMinHeight),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(KioskCardSizes.CardPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 程序名称和价格
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = program.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = String.format("%.2f €", program.price),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            // 时长
            Text(
                text = stringResource(R.string.duration_format, program.minutes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Divider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            
            // 增值服务列表
            if (program.addons.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.addon_services_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                program.addons.forEach { addon ->
                    Text(
                        text = "• $addon",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * 底部提示
 */
@Composable
private fun BottomHint() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = stringResource(R.string.select_program_hint),
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 构建信息显示（开发阶段可见）
 */
@Composable
private fun BuildInfoDisplay() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Text(
            text = "Build ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TIME}",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}
