package com.carwash.carpayment.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import java.util.Locale

/**
 * 语言切换工具类
 * 用于在运行时切换应用语言
 */
object LocaleHelper {
    
    /**
     * 设置应用语言
     */
    fun setLocale(context: Context, locale: Locale): Context {
        return updateResources(context, locale)
    }
    
    /**
     * 更新资源配置
     */
    private fun updateResources(context: Context, locale: Locale): Context {
        val res: Resources = context.resources
        val configuration: Configuration = res.configuration
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocale(locale)
            return context.createConfigurationContext(configuration)
        } else {
            @Suppress("DEPRECATION")
            configuration.locale = locale
            @Suppress("DEPRECATION")
            res.updateConfiguration(configuration, res.displayMetrics)
            return context
        }
    }
    
    /**
     * 获取当前语言
     */
    fun getCurrentLocale(context: Context): Locale {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }
    }
}
