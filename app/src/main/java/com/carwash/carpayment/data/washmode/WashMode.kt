package com.carwash.carpayment.data.washmode

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 洗车模式实体（数据库表）
 */
@Entity(tableName = "wash_mode")
data class WashMode(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,                    // 模式名称（资源键，如 "basic_wash"）
    val description: String? = null,     // 描述（资源键，可选）
    val price: Double,                   // 价格（欧元）
    val sortOrder: Int,                  // 排序顺序
    val durationMinutes: Int,            // 时长（分钟）
    val isActive: Boolean = true,        // 是否启用
    val imageResId: Int? = null         // 图片资源ID（可选）
)
