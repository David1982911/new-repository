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
    
    // 设备基线库存：deviceID:channel -> 库存数量 stored
    // 在 OpenConnection 成功后设置（基于 GetCurrencyAssignment）
    // 快照 key 使用：deviceId + ":" + channel（因为同 value 不同 channel 也可能存在）
    private val baselineLevels = mutableMapOf<String, Int>()  // key = "deviceID:channel", value = stored
    
    // 设备当前库存：deviceID:channel -> 库存数量 stored
    // 从 GetCurrencyAssignment 读取（优先使用 stored，而不是 GetAllLevels）
    private val currentLevels = mutableMapOf<String, Int>()  // key = "deviceID:channel", value = stored
    
    // 设备上一次库存（用于计算变化明细）：deviceID:channel -> 库存数量 stored
    private val previousLevels = mutableMapOf<String, Int>()  // key = "deviceID:channel", value = stored
    
    // 设备货币分配快照（用于面额识别和路由配置）：deviceID -> List<CurrencyAssignment>
    private val assignmentSnapshots = mutableMapOf<String, List<com.carwash.carpayment.data.cashdevice.CurrencyAssignment>>()
    
    // 设备面额映射：deviceID:channel -> value（用于金额计算）
    private val valueMap = mutableMapOf<String, Int>()  // key = "deviceID:channel", value = 面额（分）
    
    /**
     * 设置设备基线库存（在 OpenConnection 成功后调用）- 兼容旧接口
     * @param deviceID 设备ID
     * @param levels 基线库存 - 从 GetAllLevels 读取，格式：Map<面额value, 库存数量stored>
     * @deprecated 请使用 setBaselineFromAssignments（基于 GetCurrencyAssignment）
     */
    @Deprecated("请使用 setBaselineFromAssignments", ReplaceWith("setBaselineFromAssignments(deviceID, assignments)"))
    fun setBaseline(deviceID: String, levels: Map<Int, Int>) {
        // 兼容旧接口：将 value -> stored 转换为 deviceID:channel -> stored
        // 假设 channel=0（旧接口没有 channel 信息）
        levels.forEach { (value, stored) ->
            val key = "$deviceID:0"
            baselineLevels[key] = stored
            currentLevels[key] = stored
            previousLevels[key] = stored
            valueMap[key] = value
        }
        val baselineTotal = calculateTotalFromLevels(levels)
        Log.d(TAG, "设置设备基线库存（兼容旧接口）: deviceID=$deviceID, 条目数=${levels.size}, baselineTotalCents=$baselineTotal (${baselineTotal / 100.0}元)")
    }
    
    /**
     * 从货币分配设置设备基线库存（基于 GetCurrencyAssignment 的 stored + storedInCashbox）
     * 
     * 金额统计口径修复：
     * - 设备总库存金额：sum((Stored + StoredInCashbox) * Value)
     * - 本次会话已收金额：用快照差分，delta 的基准基于 (Stored + StoredInCashbox) 的合计
     * 
     * 纸币器工作原理：
     * - 纸币器有 主钞箱（cashbox，容量1000张） 与 循环钞箱/循环鼓（recycler，容量80张）
     * - Stored = recycler 库存（可找零）
     * - StoredInCashbox = cashbox 库存（不可找零）
     * - 总库存 = Stored + StoredInCashbox
     * 
     * @param deviceID 设备ID
     * @param assignments 货币分配列表
     */
    fun setBaselineFromAssignments(deviceID: String, assignments: List<com.carwash.carpayment.data.cashdevice.CurrencyAssignment>) {
        // 保存货币分配快照
        assignmentSnapshots[deviceID] = assignments.toList()
        
        // 构建基线库存：key = "deviceID:channel", value = stored + storedInCashbox（总库存）
        // 注意：快照 key 使用 deviceId + ":" + channel（因为同 value 不同 channel 也可能存在）
        assignments.forEach { assignment ->
            val channel = assignment.channel ?: 0
            val key = "$deviceID:$channel"
            // 总库存 = Stored（recycler） + StoredInCashbox（cashbox）
            val totalStored = assignment.stored + assignment.storedInCashbox
            val value = assignment.value
            baselineLevels[key] = totalStored
            currentLevels[key] = totalStored  // 初始时当前库存等于基线
            previousLevels[key] = totalStored  // 初始时上一次库存等于基线
            valueMap[key] = value  // 保存面额映射（用于金额计算）
        }
        
        val baselineTotal = calculateTotalFromAssignments(assignments)
        Log.d(TAG, "从货币分配设置设备基线库存: deviceID=$deviceID, 面额数=${assignments.size}, baselineTotalCents=$baselineTotal (${baselineTotal / 100.0}元)")
    }
    
    /**
     * 计算货币分配的总金额（分）- 所有面额的 value * (stored + storedInCashbox) 总和
     * 注意：value 单位从日志看是 cents（比如纸币 500=5€），硬币 200=2€
     * 金额统计口径：设备总库存金额 = sum((Stored + StoredInCashbox) * Value)
     */
    private fun calculateTotalFromAssignments(assignments: List<com.carwash.carpayment.data.cashdevice.CurrencyAssignment>): Int {
        return assignments.sumOf { it.value * (it.stored + it.storedInCashbox) }
    }
    
    /**
     * 从货币分配更新设备当前库存（轮询时调用）
     * 基于 GetCurrencyAssignment 的 stored + storedInCashbox 做快照差分
     * 
     * 金额统计口径修复：
     * - 设备总库存金额：sum((Stored + StoredInCashbox) * Value)
     * - 本次会话已收金额：用快照差分，delta 的基准基于 (Stored + StoredInCashbox) 的合计
     * 
     * 快照 key 使用：deviceId + ":" + channel（因为同 value 不同 channel 也可能存在）
     * 金额计算用 value * (totalStoredDelta)；注意 value 单位从日志看是 cents（比如纸币 500=5€），硬币 200=2€
     * 
     * @param deviceID 设备ID
     * @param assignments 货币分配列表
     * @return 本次会话累计金额（分）- sessionDeltaCents
     */
    fun updateFromAssignments(deviceID: String, assignments: List<com.carwash.carpayment.data.cashdevice.CurrencyAssignment>): Int {
        // 保存货币分配快照
        assignmentSnapshots[deviceID] = assignments.toList()
        
        // 保存上一次库存（用于计算变化明细）
        // 先保存当前库存作为上一次库存
        assignments.forEach { assignment ->
            val channel = assignment.channel ?: 0
            val key = "$deviceID:$channel"
            previousLevels[key] = currentLevels[key] ?: 0
        }
        
        // 更新当前库存和面额映射
        // 总库存 = Stored（recycler） + StoredInCashbox（cashbox）
        assignments.forEach { assignment ->
            val channel = assignment.channel ?: 0
            val key = "$deviceID:$channel"
            val totalStored = assignment.stored + assignment.storedInCashbox  // 总库存
            val value = assignment.value
            currentLevels[key] = totalStored
            valueMap[key] = value  // 保存面额映射（用于金额计算）
        }
        
        // 计算会话差值：sessionDeltaCents = Σ(value * (totalStored_now - totalStored_baseline))
        // 其中 totalStored = stored + storedInCashbox
        var sessionDeltaCents = 0
        assignments.forEach { assignment ->
            val channel = assignment.channel ?: 0
            val key = "$deviceID:$channel"
            val baselineTotalStored = baselineLevels[key] ?: 0
            val currentTotalStored = assignment.stored + assignment.storedInCashbox
            val totalStoredDelta = currentTotalStored - baselineTotalStored
            val value = assignment.value
            sessionDeltaCents += value * totalStoredDelta
        }
        
        // 确保不会出现负数
        sessionDeltaCents = maxOf(0, sessionDeltaCents)
        
        val currentTotalCents = calculateTotalFromAssignments(assignments)
        val baselineTotalCents = baselineLevels.entries
            .filter { it.key.startsWith("$deviceID:") }
            .sumOf { (key, stored) -> (valueMap[key] ?: 0) * stored }
        
        // 详细日志（每次金额轮询）
        Log.d(TAG, "金额轮询（从货币分配）: deviceID=$deviceID, baselineTotalCents=$baselineTotalCents, currentTotalCents=$currentTotalCents, sessionDeltaCents=$sessionDeltaCents, 面额数=${assignments.size}")
        
        val totalCents = getTotalCents()
        Log.d(TAG, "总金额: ${totalCents}分 (${totalCents / 100.0}元), 设备数量=${assignmentSnapshots.size}")
        
        return sessionDeltaCents
    }
    
    /**
     * 获取设备货币分配快照（用于 UI 显示和路由配置）
     * @param deviceID 设备ID
     * @return 货币分配列表
     */
    fun getDeviceAssignments(deviceID: String): List<com.carwash.carpayment.data.cashdevice.CurrencyAssignment> {
        return assignmentSnapshots[deviceID] ?: emptyList()
    }
    
    /**
     * 更新设备当前库存（轮询时调用）- 兼容旧接口
     * @param deviceID 设备ID
     * @param levelsResponse 库存响应（从 GetAllLevels 获取）
     * @return 本次会话累计金额（分）- sessionDeltaCents
     * @deprecated 请使用 updateFromAssignments（基于 GetCurrencyAssignment）
     */
    @Deprecated("请使用 updateFromAssignments", ReplaceWith("updateFromAssignments(deviceID, assignments)"))
    fun update(deviceID: String, levelsResponse: LevelsResponse): Int {
        val levels = levelsResponse.levels?.associate { it.value to it.stored } ?: emptyMap()
        
        // 兼容旧接口：将 value -> stored 转换为 deviceID:channel -> stored
        // 假设 channel=0（旧接口没有 channel 信息）
        levels.forEach { (value, stored) ->
            val key = "$deviceID:0"
            previousLevels[key] = currentLevels[key] ?: 0
            currentLevels[key] = stored
            valueMap[key] = value
        }
        
        // 计算会话差值
        var sessionDeltaCents = 0
        levels.forEach { (value, currentStored) ->
            val key = "$deviceID:0"
            val baselineStored = baselineLevels[key] ?: 0
            val storedDelta = currentStored - baselineStored
            sessionDeltaCents += value * storedDelta
        }
        sessionDeltaCents = maxOf(0, sessionDeltaCents)
        
        // 详细日志（每次金额轮询）
        Log.d(TAG, "金额轮询（兼容旧接口）: deviceID=$deviceID, sessionDeltaCents=$sessionDeltaCents, levels条目数=${levels.size}")
        
        val totalCents = getTotalCents()
        Log.d(TAG, "总金额: ${totalCents}分 (${totalCents / 100.0}元), 设备数量=${assignmentSnapshots.size}")
        
        return sessionDeltaCents
    }
    
    /**
     * 获取设备最近一次变化明细（用于 UI 显示）
     * 通过快照差分计算：deltaCount = newStored - oldStored
     * 
     * @param deviceID 设备ID
     * @return 变化明细列表（如 "+5€ x1"）
     */
    fun getRecentChanges(deviceID: String): List<AmountChange> {
        val changes = mutableListOf<AmountChange>()
        
        // 计算所有 channel 的变化（key = "deviceID:channel"）
        val allKeys = (currentLevels.keys + previousLevels.keys).filter { it.startsWith("$deviceID:") }
        for (key in allKeys) {
            val currentStored = currentLevels[key] ?: 0
            val previousStored = previousLevels[key] ?: 0
            val deltaCount = currentStored - previousStored
            
            if (deltaCount != 0) {
                val value = valueMap[key] ?: 0
                changes.add(AmountChange(
                    denomination = value,
                    count = deltaCount
                ))
            }
        }
        
        return changes
    }
    
    /**
     * 金额变化明细（用于显示最近一次变化）
     */
    data class AmountChange(
        val denomination: Int,  // 面额（分）
        val count: Int,  // 数量变化（正数表示增加，负数表示减少）
        val timestamp: Long = System.currentTimeMillis()  // 变化时间戳
    ) {
        /**
         * 获取变化金额（分）
         */
        val changeCents: Int
            get() = denomination * count
        
        /**
         * 获取变化金额（元）
         */
        val changeAmount: Double
            get() = changeCents / 100.0
        
        /**
         * 获取显示文本（如 "+5€ x1"）
         */
        fun getDisplayText(): String {
            val sign = if (count > 0) "+" else ""
            return "$sign${String.format("%.2f", changeAmount)}€ x${Math.abs(count)}"
        }
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
     * 获取指定设备的当前库存列表（用于 UI 显示）- 兼容旧接口
     * @deprecated 请使用 getDeviceAssignments
     */
    @Deprecated("请使用 getDeviceAssignments", ReplaceWith("getDeviceAssignments(deviceID)"))
    fun getDeviceCurrentLevels(deviceID: String): Map<Int, Int> {
        // 兼容旧接口：将 deviceID:channel -> stored 转换为 value -> stored
        val result = mutableMapOf<Int, Int>()
        currentLevels.forEach { (key, stored) ->
            if (key.startsWith("$deviceID:")) {
                val value = valueMap[key] ?: 0
                result[value] = (result[value] ?: 0) + stored
            }
        }
        return result
    }
    
    /**
     * 获取总金额（分）- 所有设备的本次会话累计金额总和
     */
    fun getTotalCents(): Int {
        // 计算所有设备的会话差值：Σ(value * (stored_now - stored_baseline))
        var totalCents = 0
        currentLevels.forEach { (key, currentStored) ->
            val baselineStored = baselineLevels[key] ?: 0
            val storedDelta = currentStored - baselineStored
            val value = valueMap[key] ?: 0
            totalCents += value * storedDelta
        }
        return maxOf(0, totalCents)
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
        var sessionCents = 0
        currentLevels.forEach { (key, currentStored) ->
            if (key.startsWith("$deviceID:")) {
                val baselineStored = baselineLevels[key] ?: 0
                val storedDelta = currentStored - baselineStored
                val value = valueMap[key] ?: 0
                sessionCents += value * storedDelta
            }
        }
        return maxOf(0, sessionCents)
    }
    
    /**
     * 获取指定设备的当前总金额（分）
     * 金额统计口径：设备总库存金额 = sum((Stored + StoredInCashbox) * Value)
     * 注意：currentLevels 中存储的已经是 stored + storedInCashbox 的合计
     */
    fun getDeviceCurrentCents(deviceID: String): Int {
        var totalCents = 0
        currentLevels.forEach { (key, totalStored) ->
            if (key.startsWith("$deviceID:")) {
                val value = valueMap[key] ?: 0
                totalCents += value * totalStored
            }
        }
        return totalCents
    }
    
    /**
     * 获取指定设备的基线总金额（分）
     */
    fun getDeviceBaselineCents(deviceID: String): Int {
        var totalCents = 0
        baselineLevels.forEach { (key, stored) ->
            if (key.startsWith("$deviceID:")) {
                val value = valueMap[key] ?: 0
                totalCents += value * stored
            }
        }
        return totalCents
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
