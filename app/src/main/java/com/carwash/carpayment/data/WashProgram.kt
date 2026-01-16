package com.carwash.carpayment.data

/**
 * 洗车程序数据模型
 */
data class WashProgram(
    val id: String,           // 程序ID
    val name: String,         // 程序名称（本地化后的显示名称）
    val minutes: Int,         // 时长（分钟）
    val price: Double,        // 价格（欧元）
    val addons: List<String>  // 增值服务名称列表
)
