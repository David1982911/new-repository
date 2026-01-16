package com.carwash.carpayment.data.cashdevice

import android.util.Log
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import retrofit2.HttpException
import java.io.IOException

/**
 * 授权拦截器
 * 自动为 CashDevice/ 开头的请求添加 Authorization 头
 * 处理 401 错误，自动重新认证并重试
 */
class AuthorizationInterceptor(
    private val api: CashDeviceApi,
    private val defaultUsername: String = "admin",
    private val defaultPassword: String = "password"
) : Interceptor {
    
    companion object {
        private const val TAG = "AuthorizationInterceptor"
        private const val HEADER_RETRY = "X-Retry-After-401"  // 标记这是 401 重试后的请求，避免无限循环
    }
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url.toString()
        val path = originalRequest.url.encodedPath
        
        // 如果这是 401 重试后的请求，不再处理 401（避免无限循环）
        val isRetryRequest = originalRequest.header(HEADER_RETRY) == "true"
        
        // Users/Authenticate 请求不添加 token
        val isAuthRequest = path.contains("/Users/Authenticate", ignoreCase = true)
        
        // 为 CashDevice/ 开头的请求添加 Authorization 头
        val requestBuilder = originalRequest.newBuilder()
        if (!isAuthRequest && path.contains("/CashDevice/", ignoreCase = true)) {
            val token = TokenStore.getToken()
            if (token != null) {
                requestBuilder.addHeader("Authorization", "Bearer $token")
                Log.d(TAG, "已附带授权头: ${originalRequest.method} $path")
            } else {
                Log.d(TAG, "未附带授权头（token 为空）: ${originalRequest.method} $path")
            }
        } else if (isAuthRequest) {
            Log.d(TAG, "认证请求，不添加授权头: ${originalRequest.method} $path")
        }
        
        val request = requestBuilder.build()
        var response = chain.proceed(request)
        
        // 处理 401 错误：自动重新认证并重试（但重试请求不再处理，避免无限循环）
        if (response.code == 401 && !isAuthRequest && !isRetryRequest) {
            Log.w(TAG, "收到 401 响应，尝试重新认证: ${originalRequest.method} $path")
            
            // 读取错误响应体（使用 peekBody 避免消耗 body）
            val errorBody = try {
                response.peekBody(1024).string() ?: "Unauthorised"
            } catch (e: Exception) {
                "Unauthorised"
            }
            Log.e(TAG, "401 错误详情: $errorBody")
            
            // 关闭旧响应
            response.close()
            
            // 清空旧 token
            TokenStore.clearToken()
            
            // 重新认证
            val authSuccess = runBlocking {
                try {
                    val authResponse = api.authenticate(
                        AuthenticateRequest(defaultUsername, defaultPassword)
                    )
                    if (authResponse.token != null) {
                        TokenStore.setToken(authResponse.token)
                        Log.d(TAG, "重新认证成功，token 已更新")
                        true
                    } else {
                        Log.e(TAG, "重新认证失败: ${authResponse.error}")
                        false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "重新认证异常", e)
                    false
                }
            }
            
            // 如果重新认证成功，重试原请求
            if (authSuccess) {
                val newToken = TokenStore.getToken()
                if (newToken != null) {
                    val retryRequest = originalRequest.newBuilder()
                        .removeHeader("Authorization")
                        .addHeader("Authorization", "Bearer $newToken")
                        .addHeader(HEADER_RETRY, "true")  // 标记为重试请求
                        .build()
                    Log.d(TAG, "重试请求: ${originalRequest.method} $path")
                    response = chain.proceed(retryRequest)
                    
                    // 如果重试后仍 401，记录详细错误
                    if (response.code == 401) {
                        val retryErrorBody = try {
                            response.peekBody(1024).string() ?: "Unauthorised"
                        } catch (e: Exception) {
                            "Unauthorised"
                        }
                        Log.e(TAG, "重试后仍 401: $retryErrorBody")
                    }
                } else {
                    // 重新认证成功但 token 为空，标记为重试请求后重新执行（避免无限循环）
                    Log.e(TAG, "重新认证成功但 token 为空，重新执行原请求")
                    val retryRequest = originalRequest.newBuilder()
                        .addHeader(HEADER_RETRY, "true")
                        .build()
                    response = chain.proceed(retryRequest)
                }
            } else {
                // 重新认证失败，标记为重试请求后重新执行（避免无限循环）
                Log.e(TAG, "重新认证失败，重新执行原请求")
                val retryRequest = originalRequest.newBuilder()
                    .addHeader(HEADER_RETRY, "true")
                    .build()
                response = chain.proceed(retryRequest)
            }
        }
        
        return response
    }
}
