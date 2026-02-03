package com.carwash.carpayment

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.carwash.carpayment.ui.navigation.PaymentNavigation
import com.carwash.carpayment.ui.theme.CarpaymentTheme
import com.carwash.carpayment.ui.viewmodel.HomeViewModel
import com.carwash.carpayment.ui.viewmodel.LanguageViewModel
import java.util.Locale

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        private const val BUILD_MARK_TAG = "AppBuildMark"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate - 启动应用")
        
        // 打印 Build 标记（用于确认设备上运行的是否是最新 APK）
        Log.d(BUILD_MARK_TAG, AppBuildMark.getFullMark())
        
        // APP 前台锁定/防退出：监听应用前后台切换
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    Log.d(TAG, "应用切到后台，尝试拉回前台")
                    // 尝试拉回前台（尽力而为）
                    try {
                        val intent = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        startActivity(intent)
                        Log.d(TAG, "已尝试拉回前台")
                    } catch (e: Exception) {
                        Log.w(TAG, "拉回前台失败", e)
                    }
                }
                else -> {
                    // 其他事件不处理
                }
            }
        })
        
        enableEdgeToEdge()
        setContent {
            val languageViewModel: LanguageViewModel = viewModel()
            val currentLanguage by languageViewModel.currentLanguage.collectAsState()
            
            // 根据 LanguageViewModel 的状态更新 Locale
            val locale = remember(currentLanguage) { currentLanguage.locale }
            val configuration = LocalConfiguration.current
            val context = LocalContext.current
            
            // 创建更新后的 Configuration
            val updatedConfiguration = remember(locale, configuration) {
                Configuration(configuration).apply {
                    setLocale(locale)
                }
            }
            val updatedContext = remember(updatedConfiguration, context) {
                context.createConfigurationContext(updatedConfiguration)
            }
            
            // 使用 CompositionLocalProvider 提供更新的 Locale
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalConfiguration provides updatedConfiguration,
                androidx.compose.ui.platform.LocalContext provides updatedContext
            ) {
                CarpaymentTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        val navController = rememberNavController()
                        val homeViewModel: HomeViewModel = viewModel(
                            factory = ViewModelProvider.AndroidViewModelFactory.getInstance(application)
                        )
                        
                        PaymentNavigation(
                            navController = navController,
                            homeViewModel = homeViewModel,
                            languageViewModel = languageViewModel
                        )
                    }
                }
            }
        }
    }
    
    // 禁用返回键退出（除非在测试页点"退出按钮"才允许退出）
    // 注意：在新版本 Android 中，onBackPressed() 已废弃，但为了兼容性保留
    // 实际应该使用 OnBackPressedDispatcher，但为了简化，这里直接禁用
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        Log.d(TAG, "onBackPressed 被调用，但已禁用返回键退出功能")
        // 不调用 super.onBackPressed()，禁用返回键退出
        // 测试页的"返回"按钮会使用 navController.popBackStack() 在应用内导航
    }
    
    /**
     * 退出APP（仅在测试页调用）
     */
    fun exitApp() {
        Log.d(TAG, "========== 退出APP ==========")
        Log.d(TAG, "调用 finishAffinity() + exitProcess(0)")
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}