package com.carwash.carpayment.data.cashdevice

import android.util.Log

/**
 * 现金金额跟踪器（基于库存差值机制）
 * 使用本次会话累计金额（分）：
 * sessionDelta = Σ(value * (stored_now - stored_baseline))
 * 
 * 金额来源：GetAllLevels（各面额的库存数量）
 * 不使用 GetStoredValue（服务端返回 404）
 * 不使用 GetCounters（那只是计数）
 */
class CashAmountTracker {
    
    companion object {
        private const val TAG = "CashAmountTracker"
    }
    
    // 设备基线库存：deviceID -> (面额 value -> 库存数量 stored)
    // 在 OpenConnection 成功后设置
    private val baselineLevels = mutableMapOf<String, Map<Int, Int>>()
    
    // 设备当前库存：deviceID -> (面额 value -> 库存数量 stored)
    // 从 GetAllLevels 读取
    private val currentLevels = mutableMapOf<String, Map<Int, Int>>()
    
    /**
     * 设置设备基线库存（在 OpenConnection 成功后调用）
     * @param deviceID 设备ID
     * @param levels 基线库存 - 从 GetAllLevels 读取，格式：Map<面额value, 库存数量stored>
     */
    fun setBaseline(deviceID: String, levels: Map<Int, Int>) {
        baselineLevels[deviceID] = levels.toMap()  // 创建副本
        val baselineTotal = calculateTotalFromLevels(levels)
        Log.d(TAG, "设置设备基线库存: deviceID=$deviceID, 条目数=${levels.size}, baselineTotalCents=$baselineTotal (${baselineTotal / 100.0}元)")
    }
    
    /**
     * 更新设备当前库存（轮询时调用）
     * 实时金额逻辑：
     * - baselineTotalCents：进入"收款会话"时记录一次
     * - currentTotalCents：轮询 GetAllLevels 汇总
     * - sessionDeltaCents = max(0, currentTotalCents - baselineTotalCents)
     * 
     * @param deviceID 设备ID
     * @param levelsResponse 库存响应（从 GetAllLevels 获取）
     * @return 本次会话累计金额（分）- sessionDeltaCents
     */
    fun update(deviceID: String, levelsResponse: LevelsResponse): Int {
        val levels = levelsResponse.levels?.associate { it.value to it.stored } ?: emptyMap()
        currentLevels[deviceID] = levels
        
        val baseline = baselineLevels[deviceID] ?: emptyMap()
        val currentTotalCents = calculateTotalFromLevels(levels)
        val baselineTotalCents = calculateTotalFromLevels(baseline)
        
        // 计算会话差值：sessionDeltaCents = max(0, currentTotalCents - baselineTotalCents)
        val sessionDeltaCents = maxOf(0, currentTotalCents - baselineTotalCents)
        
        // 详细日志（每次金额轮询）
        Log.d(TAG, "金额轮询: deviceID=$deviceID, baselineTotalCents=$baselineTotalCents, currentTotalCents=$currentTotalCents, sessionDeltaCents=$sessionDeltaCents, levels条目数=${levels.size}")
        
        val totalCents = getTotalCents()
        Log.d(TAG, "总金额: ${totalCents}分 (${totalCents / 100.0}元), 设备数量=${baselineLevels.size}")
        
        return sessionDeltaCents
    }
    
    /**
     * 计算库存总金额（分）- 所有面额的 value * stored 总和
     */
    private fun calculateTotalFromLevels(levels: Map<Int, Int>): Int {
        return levels.entries.sumOf { (value, stored) -> value * stored }
    }
    
    /**
     * 计算库存差值（分）- sessionDeltaCents = max(0, currentTotalCents - baselineTotalCents)
     */
    private fun calculateDelta(current: Map<Int, Int>, baseline: Map<Int, Int>): Int {
        val currentTotalCents = calculateTotalFromLevels(current)
        val baselineTotalCents = calculateTotalFromLevels(baseline)
        return maxOf(0, currentTotalCents - baselineTotalCents)
    }
    
    /**
     * 获取指定设备的当前库存列表（用于 UI 显示）
     */
    fun getDeviceCurrentLevels(deviceID: String): Map<Int, Int> {
        return currentLevels[deviceID] ?: emptyMap()
    }
    
    /**
     * 获取总金额（分）- 所有设备的本次会话累计金额总和
     */
    fun getTotalCents(): Int {
        return baselineLevels.keys.map { deviceID ->
            val current = currentLevels[deviceID] ?: emptyMap()
            val baseline = baselineLevels[deviceID] ?: emptyMap()
            calculateDelta(current, baseline)
        }.sum()
    }
    
    /**
     * 获取总金额（元）
     */
    fun getTotalAmount(): Double {
        return getTotalCents() / 100.0
    }
    
    /**
     * 获取指定设备的本次会话累计金额（分）
     */
    fun getDeviceSessionCents(deviceID: String): Int {
        val current = currentLevels[deviceID] ?: emptyMap()
        val baseline = baselineLevels[deviceID] ?: emptyMap()
        return calculateDelta(current, baseline)
    }
    
    /**
     * 获取指定设备的当前总金额（分）
     */
    fun getDeviceCurrentCents(deviceID: String): Int {
        val current = currentLevels[deviceID] ?: emptyMap()
        return calculateTotalFromLevels(current)
    }
    
    /**
     * 获取指定设备的基线总金额（分）
     */
    fun getDeviceBaselineCents(deviceID: String): Int {
        val baseline = baselineLevels[deviceID] ?: emptyMap()
        return calculateTotalFromLevels(baseline)
    }
    
    /**
     * 重置所有金额（用于新的支付会话）
     * 注意：不清除基线，基线只在断开连接时清除
     * 重新采集基线时，会覆盖旧的基线
     */
    fun reset() {
        Log.d(TAG, "重置金额跟踪器（清除当前库存，保留基线）")
        currentLevels.clear()
    }
    
    /**
     * 移除指定设备（断开连接时调用）
     * 清除基线库存和当前库存
     */
    fun removeDevice(deviceID: String) {
        Log.d(TAG, "移除设备: deviceID=$deviceID")
        baselineLevels.remove(deviceID)
        currentLevels.remove(deviceID)
    }
}
