package com.carwash.carpayment

/**
 * App Build 标记
 * 用于标识 APK 构建版本，便于在设备上确认是否是最新版本
 * 
 * 格式：YYYY-MM-DD-HH（年-月-日-小时）
 * 例如：2026-01-17-01 表示 2026年1月17日 01时构建
 */
object AppBuildMark {
    /**
     * Build 标记字符串
     * 每次构建新版本时更新此值
     */
    const val BUILD_MARK = "2026-01-17-01"
    
    /**
     * 获取完整的 Build 标记字符串（带前缀）
     */
    fun getFullMark(): String {
        return "APP_BUILD_MARK=$BUILD_MARK"
    }
}
