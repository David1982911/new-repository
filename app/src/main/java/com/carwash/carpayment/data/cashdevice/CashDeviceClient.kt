package com.carwash.carpayment.data.cashdevice

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * 现金设备 API 客户端工厂
 */
object CashDeviceClient {
    
    private const val TAG = "CashDeviceClient"
    private const val DEFAULT_BASE_URL = "http://127.0.0.1:5000/api/"  // 厂商文档：ITL Android REST Server（Retrofit 要求末尾必须有 /）
    private const val PREFS_NAME = "cash_device_config"
    private const val KEY_BASE_URL = "base_url"
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        useAlternativeNames = true  // 启用 @JsonNames 支持（用于 DeviceID/deviceID 字段映射）
    }
    
    private val contentType = "application/json".toMediaType()
    
    /**
     * 获取配置的 baseUrl（从 SharedPreferences 或使用默认值）
     */
    fun getBaseUrl(context: Context? = null): String {
        return if (context != null) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        } else {
            DEFAULT_BASE_URL
        }
    }
    
    /**
     * 设置 baseUrl（保存到 SharedPreferences）
     */
    fun setBaseUrl(context: Context, baseUrl: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_BASE_URL, baseUrl).apply()
        Log.d(TAG, "已保存 baseUrl: $baseUrl")
    }
    
    /**
     * 创建带指定超时的 Retrofit 实例（用于探测）
     * @param baseUrl 基础 URL，如果为 null 则从配置读取或使用默认值
     * @param context 用于读取配置的 Context（可选）
     * @param timeoutSeconds 超时时间（秒），默认 12 秒（OpenConnection 需要等待设备响应）
     */
    fun createWithTimeout(baseUrl: String? = null, context: Context? = null, timeoutSeconds: Long = 12): CashDeviceApi {
        val rawBaseUrl = baseUrl ?: getBaseUrl(context)
        val normalizedBaseUrl = if (rawBaseUrl.endsWith("/")) rawBaseUrl else "$rawBaseUrl/"
        
        val tempRetrofit = Retrofit.Builder()
            .baseUrl(normalizedBaseUrl)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
        val tempApi = tempRetrofit.create(CashDeviceApi::class.java)
        val authInterceptor = AuthorizationInterceptor(tempApi)
        
        val loggingInterceptor = HttpLoggingInterceptor { message ->
            Log.d(TAG, message)
        }.apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
        
        return Retrofit.Builder()
            .baseUrl(normalizedBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(CashDeviceApi::class.java)
    }
    
    /**
     * 创建 Retrofit 实例
     * @param baseUrl 基础 URL，如果为 null 则从配置读取或使用默认值
     * @param context 用于读取配置的 Context（可选）
     */
    fun create(baseUrl: String? = null, context: Context? = null): CashDeviceApi {
        val rawBaseUrl = baseUrl ?: getBaseUrl(context)
        
        // Retrofit 要求 baseUrl 必须以 "/" 结尾，进行标准化处理
        val normalizedBaseUrl = if (rawBaseUrl.endsWith("/")) rawBaseUrl else "$rawBaseUrl/"
        
        // 打印最终使用的 baseUrl（标准化后）
        Log.d(TAG, "创建 CashDeviceApi，baseUrl: $normalizedBaseUrl")
        
        // 先创建临时 API 实例用于 Interceptor（用于 401 重试时的重新认证）
        val tempRetrofit = Retrofit.Builder()
            .baseUrl(normalizedBaseUrl)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
        val tempApi = tempRetrofit.create(CashDeviceApi::class.java)
        
        // 创建授权拦截器（自动添加 Authorization 头，处理 401）
        val authInterceptor = AuthorizationInterceptor(tempApi)
        
        val loggingInterceptor = HttpLoggingInterceptor { message ->
            Log.d(TAG, message)
        }.apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)  // 授权拦截器（先执行，处理 401）
            .addInterceptor(loggingInterceptor)  // 日志拦截器（后执行，记录最终请求）
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        
        return Retrofit.Builder()
            .baseUrl(normalizedBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(CashDeviceApi::class.java)
    }
}
