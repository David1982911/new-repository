package com.carwash.carpayment.ui.screens
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    paymentViewModel: PaymentViewModel? = null
) {
    val programs by homeViewModel.programs.collectAsState()
    val washModes by homeViewModel.washModes.collectAsState(initial = emptyList())
    val machineStatus by homeViewModel.machineStatus.collectAsState()
    val currentLanguage by languageViewModel.currentLanguage.collectAsState()

    Log.d("HomeDebug", "SelectProgramScreen: washModes.size=${washModes.size}, programs.size=${programs.size}")

    val gateCheckResult: CarWashGateCheckResult? = if (paymentViewModel != null) {
        val result by paymentViewModel.gateCheckResult.collectAsState()
        result
    } else null

    // 隐藏入口：连续点击 Logo 5 次（保留，不影响当前UI）
    var logoClickCount by remember { mutableLongStateOf(0L) }
    var lastClickTime by remember { mutableLongStateOf(0L) }

    // 价格来源日志（保留）
    Log.d("ProgramPrice", "========== SelectProgramScreen 价格来源 ==========")
    Log.d("ProgramPrice", "source=HomeViewModel.programs (来自 ProgramConfigRepository.configFlow)")
    Log.d("ProgramPrice", "programs=${programs.map { "${it.id}: price=${it.price}€ (${(it.price * 100).toInt()}分)" }}")
    Log.d("ProgramPrice", "programsCount=${programs.size}")
    programs.forEach { program ->
        Log.d("ProgramPrice", "  - id=${program.id}, name=${program.name}, price=${program.price}€, priceCents=${(program.price * 100).toInt()}")
    }
    Log.d("ProgramPrice", "================================================")

    Box(modifier = Modifier.fillMaxSize()) {

        // 背景图片
        androidx.compose.foundation.Image(
            painter = painterResource(id = R.drawable.bg_fullscreen_1080x1920),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )

        // 10等份布局严格成立：顶部 1.2格，中间 8.2格，底部 0.6格
        Column(modifier = Modifier.fillMaxSize()) {

            // 顶部区：1.2格（12%）
            TopHeaderBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.12f),
                currentLanguage = currentLanguage,
                onEN = { languageViewModel.switchLanguage(com.carwash.carpayment.ui.viewmodel.AppLanguage.ENGLISH) },
                onDE = { languageViewModel.switchLanguage(com.carwash.carpayment.ui.viewmodel.AppLanguage.GERMAN) }
            )

            // 中间区：8.2格（82%）—— 4个套餐等高撑满
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.82f)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                val itemCount = if (washModes.isEmpty()) programs.size else washModes.size

                if (itemCount == 4) {
                    val gap = 12.dp
                    // 使用中间区全部高度来均分 4 张卡片（严格“8.2格”）
                    val cardHeight = (maxHeight - gap * 3) / 4

                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        if (washModes.isEmpty()) {
                            programs.take(4).forEach { program ->
                                ProgramCard(
                                    program = program,
                                    onClick = {
                                        Log.d("SelectProgramScreen", "点击选择程序，programId: ${program.id}")
                                        homeViewModel.selectProgram(program.id)
                                        onProgramSelected(program.id)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(cardHeight)
                                )
                            }
                        } else {
                            washModes.take(4).forEach { mode ->
                                WashModeCard(
                                    mode = mode,
                                    onClick = {
                                        Log.d("SelectProgramScreen", "点击选择模式，modeId: ${mode.id}")
                                        homeViewModel.selectProgram(mode.id.toString())
                                        onProgramSelected(mode.id.toString())
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(cardHeight),
                                    imageVerticalBias = 0.35f
                                )
                            }
                        }

                        // GateCheck 失败提示：如需不挤占4张卡片高度，可改为 Overlay 弹层
                        gateCheckResult?.let { result ->
                            if (result is CarWashGateCheckResult.Failed) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "⚠",
                                            fontSize = 48.sp,
                                            color = MaterialTheme.colorScheme.error
                                        )

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
                                            else -> result.message
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
                    }
                } else {
                    // 非4项：滚动
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (washModes.isEmpty()) {
                            programs.forEach { program ->
                                ProgramCard(
                                    program = program,
                                    onClick = {
                                        homeViewModel.selectProgram(program.id)
                                        onProgramSelected(program.id)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        } else {
                            washModes.forEach { mode ->
                                WashModeCard(
                                    mode = mode,
                                    onClick = {
                                        homeViewModel.selectProgram(mode.id.toString())
                                        onProgramSelected(mode.id.toString())
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    imageVerticalBias = 0.35f
                                )
                            }
                        }
                    }
                }
            }

            // 底部区：0.6格（6%）—— 状态栏
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.06f)
            ) {
                BottomHint(machineStatus = machineStatus)
            }
        }

        // ✅ BuildInfo 作为 Overlay：不占用 10等分布局高度
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 4.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            BuildInfoDisplay()
        }
    }
}

/**
 * 顶部区：标题 + 语言按钮（同一行）
 */
@Composable
private fun TopHeaderBar(
    modifier: Modifier,
    currentLanguage: com.carwash.carpayment.ui.viewmodel.AppLanguage,
    onEN: () -> Unit,
    onDE: () -> Unit
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.home_title),
                //style = MaterialTheme.typography.headlineLarge,
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                //color = MaterialTheme.colorScheme.onBackground,
                color = Color.White,
                        modifier = Modifier.weight(1f)
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onEN,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (currentLanguage == com.carwash.carpayment.ui.viewmodel.AppLanguage.ENGLISH) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.language_en),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onDE,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (currentLanguage == com.carwash.carpayment.ui.viewmodel.AppLanguage.GERMAN) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.language_de),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * 洗车模式卡片：标题/价格叠加到图片右侧 + 右侧渐变遮罩
 * imageVerticalBias：控制图片裁剪的“聚焦下移”，减少顶部白/浅灰区域
 */
@Composable
private fun WashModeCard(
    mode: com.carwash.carpayment.data.washmode.WashMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    imageVerticalBias: Float = 0.35f
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101418)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .background(Color(0xFF101418)) // ✅ 卡片内部底色：防露白
        ) {
            // ✅ 再铺一层底色（双保险，防图片透明）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF101418))
            )

            mode.imageResId?.let { imageResId ->
                androidx.compose.foundation.Image(
                    painter = painterResource(id = imageResId),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.Center
                )
            } ?: run {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF4FC3F7).copy(alpha = 0.25f))
                )
            }

            // 右侧渐变遮罩（保持不变）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0.00f to Color.Transparent,
                                0.55f to Color.Transparent,
                                0.75f to Color.Black.copy(alpha = 0.35f),
                                1.00f to Color.Black.copy(alpha = 0.65f),
                            ),
                            tileMode = TileMode.Clamp
                        )
                    )
            )

            val modeNameResId = when (mode.name) {
                "basic_wash" -> R.string.basic_wash
                "standard_wash" -> R.string.standard_wash
                "premium_wash" -> R.string.premium_wash
                "vip_wash" -> R.string.vip_wash
                else -> R.string.basic_wash
            }

            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .align(Alignment.CenterEnd)
                    .padding(end = 40.dp)
                    .widthIn(min = 140.dp, max = 360.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = stringResource(modeNameResId),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 1.sp,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    lineHeight = 34.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = String.format("%.0f €", mode.price),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.ExtraBold,
                    //color = Color.White,
                    color = Color(0xFFFFD700),
                            textAlign = TextAlign.End
                )
            }
        }


    }
}

/**
 * 兼容旧卡片：增加 modifier 参数
 */
@Composable
private fun ProgramCard(
    program: WashProgram,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = KioskCardSizes.CardMinHeight),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(KioskCardSizes.CardPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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

            Text(
                text = stringResource(R.string.duration_format, program.minutes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Divider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

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
 * 底部状态栏
 */
@Composable
private fun BottomHint(
    machineStatus: com.carwash.carpayment.ui.viewmodel.HomeViewModel.MachineStatus
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        val (statusText, statusColor) = when (machineStatus) {
            com.carwash.carpayment.ui.viewmodel.HomeViewModel.MachineStatus.Ready ->
                Pair(stringResource(R.string.status_ready), MaterialTheme.colorScheme.primary)
            com.carwash.carpayment.ui.viewmodel.HomeViewModel.MachineStatus.Fault ->
                Pair(stringResource(R.string.status_fault), MaterialTheme.colorScheme.error)
            com.carwash.carpayment.ui.viewmodel.HomeViewModel.MachineStatus.Washing ->
                Pair(stringResource(R.string.status_occupied), MaterialTheme.colorScheme.secondary)
            com.carwash.carpayment.ui.viewmodel.HomeViewModel.MachineStatus.Idle ->
                Pair(stringResource(R.string.status_unknown), MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Text(
            text = statusText,
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            color = statusColor
        )
    }
}

/**
 * Build信息（Overlay）
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

/**
 * 你原 TopBar/LanguageToggleButton 仍保留（当前页面未用）
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

            Text(
                text = stringResource(R.string.welcome_message),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            LanguageToggleButton(
                currentLanguage = currentLanguage,
                onClick = onLanguageToggle
            )
        }
    }
}

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
            .width(140.dp)
            .height(60.dp),
        shape = RoundedCornerShape(30.dp),
        color = if (currentLanguage == com.carwash.carpayment.ui.viewmodel.AppLanguage.GERMAN) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = deText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = if (currentLanguage == com.carwash.carpayment.ui.viewmodel.AppLanguage.GERMAN) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Text(
                text = "|",
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = enText,
                fontSize = 20.sp,
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
