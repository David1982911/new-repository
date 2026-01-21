package com.carwash.carpayment.data.cashdevice

import android.util.Log

/**
 * 设备面额配置（可接收面额白名单 / 可找零面额白名单）
 * 
 * 关键规则：
 * 
 * A. SetInhibits（接收面额）
 * - 厂商确认：SetInhibits 是设置可接收面额，要逐个面额配置
 * - 约定：Inhibit = true 表示禁止接收该面额；Inhibit = false 表示允许接收该面额
 * - APP 必须提供一份"可接收面额白名单"（每个设备独立）
 * 
 * B. SetRoutes（找零面额/路由）
 * - 厂商确认：SetRoutes 是设置找零面额，决定某面额进入 recycler（可找零）还是 cashbox（不可找零）
 * - APP 必须提供一份"可找零面额白名单"（只对白名单面额设置为 recycler 路由）
 * 
 * C. 纸币器双钞箱逻辑
 * - 循环找零箱（recycler / 循环鼓）：容量 80 张，这里的钱可用于找零
 * - 主钞箱（cashbox / stacker）：容量 1000 张，这里的钱不可用于找零
 * - 投入纸币时：
 *   - 若该面额被配置为"可找零面额"：优先进入 recycler；当 recycler 满（80）后，即便是可找零面额也会进入主钞箱
 *   - 若该面额被配置为"不可找零面额"：直接进入主钞箱
 * - 需要实时监控 recycler 库存变化：找零吐出后 recycler 有空间，应允许新的可找零面额继续进入 recycler
 */
object DeviceDenominationConfig {
    
    private const val TAG = "DeviceDenominationConfig"
    
    /**
     * 纸币器（SPECTRAL_PAYOUT-0）可接收面额白名单（分）
     * 只有在这个列表中的面额才会被允许接收
     */
    val BILL_ACCEPTABLE_DENOMINATIONS: List<Int> = listOf(
        100,   // 1€
        200,   // 2€
        500,   // 5€
        1000,  // 10€
        2000,  // 20€
        5000,  // 50€
        10000  // 100€
    )
    
    /**
     * 纸币器（SPECTRAL_PAYOUT-0）可找零面额白名单（分）
     * 只有在这个列表中的面额才会被设置为 recycler 路由（可找零）
     * 其他面额进入 cashbox（不可找零）
     */
    val BILL_RECYCLABLE_DENOMINATIONS: List<Int> = listOf(
        100,   // 1€ - 可找零
        200,   // 2€ - 可找零
        500,   // 5€ - 可找零
        1000,  // 10€ - 可找零
        2000   // 20€ - 可找零
        // 50€ 和 100€ 不可找零，进入 cashbox
    )
    
    /**
     * 硬币器（SMART_COIN_SYSTEM-1）可接收面额白名单（分）
     * 只有在这个列表中的面额才会被允许接收
     */
    val COIN_ACCEPTABLE_DENOMINATIONS: List<Int> = listOf(
        1,     // 1 分
        2,     // 2 分
        5,     // 5 分
        10,    // 10 分
        20,    // 20 分
        50,    // 50 分
        100,   // 1€
        200    // 2€
    )
    
    /**
     * 硬币器（SMART_COIN_SYSTEM-1）可找零面额白名单（分）
     * 硬币器通常所有面额都可以找零
     */
    val COIN_RECYCLABLE_DENOMINATIONS: List<Int> = COIN_ACCEPTABLE_DENOMINATIONS
    
    /**
     * 获取设备可接收面额白名单
     * @param deviceName 设备名称（SPECTRAL_PAYOUT-0 或 SMART_COIN_SYSTEM-1）
     * @return 可接收面额列表（分）
     */
    fun getAcceptableDenominations(deviceName: String): List<Int> {
        return when (deviceName) {
            "SPECTRAL_PAYOUT-0" -> BILL_ACCEPTABLE_DENOMINATIONS
            "SMART_COIN_SYSTEM-1" -> COIN_ACCEPTABLE_DENOMINATIONS
            else -> {
                Log.w(TAG, "未知设备名称: $deviceName，返回空列表")
                emptyList()
            }
        }
    }
    
    /**
     * 获取设备可找零面额白名单
     * @param deviceName 设备名称（SPECTRAL_PAYOUT-0 或 SMART_COIN_SYSTEM-1）
     * @return 可找零面额列表（分）
     */
    fun getRecyclableDenominations(deviceName: String): List<Int> {
        return when (deviceName) {
            "SPECTRAL_PAYOUT-0" -> BILL_RECYCLABLE_DENOMINATIONS
            "SMART_COIN_SYSTEM-1" -> COIN_RECYCLABLE_DENOMINATIONS
            else -> {
                Log.w(TAG, "未知设备名称: $deviceName，返回空列表")
                emptyList()
            }
        }
    }
    
    /**
     * 检查面额是否可接收
     * @param deviceName 设备名称
     * @param denomination 面额（分）
     * @return true 表示可接收，false 表示不可接收
     */
    fun isAcceptable(deviceName: String, denomination: Int): Boolean {
        return getAcceptableDenominations(deviceName).contains(denomination)
    }
    
    /**
     * 检查面额是否可找零
     * @param deviceName 设备名称
     * @param denomination 面额（分）
     * @return true 表示可找零（进入 recycler），false 表示不可找零（进入 cashbox）
     */
    fun isRecyclable(deviceName: String, denomination: Int): Boolean {
        return getRecyclableDenominations(deviceName).contains(denomination)
    }
}
