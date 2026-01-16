package com.carwash.carpayment

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate - 启动应用")
        
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
}