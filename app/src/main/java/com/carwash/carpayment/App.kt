package com.carwash.carpayment

import android.os.StrictMode
import com.carwash.carpayment.BuildConfig

/**
 * Application 类，启用 StrictMode 以检测 ANR 问题
 * 
 * 继承 CarPaymentApplication 以保留所有现有初始化逻辑
 */
class App : CarPaymentApplication() {
    
    override fun onCreate() {
        super.onCreate()
        enableStrictMode()
    }

    /**
     * 启用 StrictMode 以检测主线程违规
     * 仅在 Debug 模式下启用，避免影响 Release 版本性能
     */
    private fun enableStrictMode() {
        if (BuildConfig.DEBUG) {
            // 线程策略：检测所有主线程违规（磁盘读写、网络等）
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            // VM策略：检测内存泄漏等
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }
    }
}
