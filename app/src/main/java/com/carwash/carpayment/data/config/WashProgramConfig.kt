package com.carwash.carpayment.data.config

import kotlinx.serialization.Serializable

/**
 * 洗车程序配置数据模型
 * 用于存储和管理洗车程序的价格、时长等可配置信息
 */
@Serializable
data class WashProgramConfig(
    val id: String,           // 程序ID（不可修改）
    val nameKey: String,       // 名称资源键（不可修改）
    val minutes: Int,          // 时长（分钟）- 可修改
    val price: Double,         // 价格（欧元）- 可修改
    val addons: List<String>   // 增值服务名称列表（可修改）
)

/**
 * 洗车程序配置列表
 */
@Serializable
data class WashProgramConfigList(
    val programs: List<WashProgramConfig>
)
