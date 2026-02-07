package com.carwash.carpayment.data.cashdevice

import android.util.Log

/**
 * 现金金额跟踪器（基于 GetAllLevels 差分）
 * 
 * ⚠️ 关键修复：本版本以 GetAllLevels 差分为唯一判定口径
 * 
 * 金额计算方式：
 * - 支付判定：使用 GetAllLevels 差分（Levels delta）
 * - 设备状态：使用 deviceStates[deviceID].sessionDeltaCents 聚合
 * - 禁止使用：GetCounters/avgVal 参与支付成功/失败判定（仅允许作为日志维护统计）
 * 
 * LEVELS 用途：
 * - 支付判定（唯一口径）
 * - 找零库存显示
 * - 运维/诊断日志
 * 
 * ⚠️ 历史遗留：devicePaidAmounts/deviceBaselinePaidAmounts 相关字段与方法未启用，不用于支付判定
 */
class CashAmountTracker {
    
    companion object {
        private const val TAG = "CashAmountTracker"
    }
    
    // ⚠️ 关键修复：并发保护 - 使用 synchronized 确保一次轮询计算是原子性的
    private val lock = Any()
    
    // ⚠️ 历史遗留：以下字段未启用，不用于支付判定（本版本以 GetAllLevels 差分为唯一口径）
    // 设备已收金额（分）：deviceID -> 本次会话累计收款金额
    // 数据来源：GetCounters API 的 stackedTotalCents（已停用）
    @Suppress("unused")
    private val devicePaidAmounts = mutableMapOf<String, Int>()  // key = deviceID, value = 已收金额（分）
    
    // 设备基线收款金额（在支付开始时记录，用于计算会话增量）（已停用）
    @Suppress("unused")
    private val deviceBaselinePaidAmounts = mutableMapOf<String, Int>()  // key = deviceID, value = 基线金额（分）
    
    // ⚠️ 关键修复：baseline 状态（按 device 维度）
    private enum class BaselineStatus {
        NOT_SET,  // 未设置
        SET       // 已设置（会话过程中不得覆盖）
    }
    private val baselineStatus = mutableMapOf<String, BaselineStatus>()  // key = deviceID, value = 状态
    
    // ⚠️ 关键修复：设备状态（用于聚合汇总和对账）
    data class DeviceState(
        val deviceID: String,
        val baselineLevels: Map<Int, Int>,  // Map<denomValue, storedCount>
        val lastLevels: Map<Int, Int>,      // Map<denomValue, storedCount>
        val sessionDeltaCents: Int          // 本次会话增量（分）
    )
    private val deviceStates = mutableMapOf<String, DeviceState>()  // key = deviceID
    
    // 保留库存相关字段（仅用于找零库存显示和运维日志，不参与支付判断）
    private val baselineLevels = mutableMapOf<String, Int>()  // key = "deviceID:channel", value = stored
    private val currentLevels = mutableMapOf<String, Int>()  // key = "deviceID:channel", value = stored
    private val previousLevels = mutableMapOf<String, Int>()  // key = "deviceID:channel", value = stored
    private val assignmentSnapshots = mutableMapOf<String, List<com.carwash.carpayment.data.cashdevice.CurrencyAssignment>>()
    // ⚠️ 金额单位确认：valueMap 中的 value 为 cents（分），例如：100=1€，200=2€，500=5€，1000=10€
    private val valueMap = mutableMapOf<String, Int>()  // key = "deviceID:channel", value = 面额（分，cents）
    
    /**
     * 设置设备基线库存（在支付开始时调用，基于 GetAllLevels）
     * ⚠️ 关键修复：baseline 只允许在"会话启动后第一次成功 GetAllLevels"时写入一次
     * @param deviceID 设备ID
     * @param levels 基线库存 - 从 GetAllLevels 读取，格式：Map<面额value, 库存数量stored>
     * @param dataSource 数据源标识（用于日志）："LEVELS" 或 "ASSIGNMENT"
     * @param reason 设置原因（用于日志）："SESSION_START" 等
     */
    fun setBaselineFromLevels(deviceID: String, levels: Map<Int, Int>, dataSource: String = "LEVELS", reason: String = "SESSION_START") {
        // ⚠️ 关键修复：如果 baseline 已设置，禁止覆盖（除非会话结束/重启）
        val currentStatus = baselineStatus[deviceID]
        if (currentStatus == BaselineStatus.SET) {
            Log.w(TAG, "⚠️ baseline 已设置，禁止覆盖: deviceID=$deviceID, reason=$reason")
            Log.w(TAG, "说明: 会话过程中 baseline 不得被覆盖，如需重置请先调用 clearBaselineForDevice")
            return
        }
        
        // ⚠️ 关键修复：baseline 的内容必须来自真实返回，禁止任何"默认给 1"/"缺省填充"逻辑
        // 如果某些面额在 levels 里不存在，就不应出现在 baseline 里
        val realLevels = levels.filter { (_, stored) -> stored >= 0 }  // 只保留有效的 stored 值
        
        // ⚠️ 关键修复：key 必须包含面额 value（因为 GetAllLevels 没有 channel）
        // 使用 "$deviceID:$value" 而不是 "$deviceID:0"，避免所有面额覆盖同一个 key
        realLevels.forEach { (value, stored) ->
            val key = "$deviceID:$value"
            baselineLevels[key] = stored
            currentLevels[key] = stored
            previousLevels[key] = stored
            valueMap[key] = value
        }
        
        // ⚠️ 关键修复：更新设备状态
        val baselineTotal = calculateTotalFromLevels(realLevels)
        deviceStates[deviceID] = DeviceState(
            deviceID = deviceID,
            baselineLevels = realLevels,
            lastLevels = realLevels,
            sessionDeltaCents = 0
        )
        
        // ⚠️ 关键修复：标记 baseline 为已设置
        baselineStatus[deviceID] = BaselineStatus.SET
        
        Log.d(TAG, "设置设备基线库存（基于$dataSource）: deviceID=$deviceID, 条目数=${realLevels.size}, baselineTotalCents=$baselineTotal (${baselineTotal / 100.0}元), reason=$reason")
    }
    
    /**
     * 设置设备基线库存（在 OpenConnection 成功后调用）- 兼容旧接口
     * @param deviceID 设备ID
     * @param levels 基线库存 - 从 GetAllLevels 读取，格式：Map<面额value, 库存数量stored>
     * @deprecated 请使用 setBaselineFromLevels（基于 GetAllLevels）或 setBaselineFromAssignments（基于 GetCurrencyAssignment）
     */
    @Deprecated("请使用 setBaselineFromLevels 或 setBaselineFromAssignments", ReplaceWith("setBaselineFromLevels(deviceID, levels)"))
    fun setBaseline(deviceID: String, levels: Map<Int, Int>) {
        setBaselineFromLevels(deviceID, levels, "LEVELS")
    }
    
    /**
     * 从货币分配设置设备基线库存（基于 GetCurrencyAssignment 的 stored + storedInCashbox）
     * ⚠️ 关键修复：baseline 只允许在"会话启动后第一次成功 GetAllLevels"时写入一次
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
     * @param reason 设置原因（用于日志）："SESSION_START" 等
     */
    fun setBaselineFromAssignments(deviceID: String, assignments: List<com.carwash.carpayment.data.cashdevice.CurrencyAssignment>, reason: String = "SESSION_START") {
        // ⚠️ 关键修复：如果 baseline 已设置，禁止覆盖（除非会话结束/重启）
        val currentStatus = baselineStatus[deviceID]
        if (currentStatus == BaselineStatus.SET) {
            Log.w(TAG, "⚠️ baseline 已设置，禁止覆盖: deviceID=$deviceID, reason=$reason")
            Log.w(TAG, "说明: 会话过程中 baseline 不得被覆盖，如需重置请先调用 clearBaselineForDevice")
            return
        }
        // ⚠️ 关键修复：baseline 的内容必须来自真实返回，禁止任何"默认给 1"/"缺省填充"逻辑
        // 构建基线库存：key = "deviceID:channel", value = stored + storedInCashbox（总库存）
        // 注意：快照 key 使用 deviceId + ":" + channel（因为同 value 不同 channel 也可能存在）
        val realBaselineLevels = mutableMapOf<String, Int>()
        assignments.forEach { assignment ->
            val channel = assignment.channel ?: 0
            val key = "$deviceID:$channel"
            // 总库存 = Stored（recycler） + StoredInCashbox（cashbox）
            val totalStored = assignment.stored + assignment.storedInCashbox
            val value = assignment.value
            // ⚠️ 关键修复：只保存有效的 stored 值（>= 0），禁止填充默认值
            if (totalStored >= 0) {
            baselineLevels[key] = totalStored
            currentLevels[key] = totalStored  // 初始时当前库存等于基线
            previousLevels[key] = totalStored  // 初始时上一次库存等于基线
            valueMap[key] = value  // 保存面额映射（用于金额计算）
                realBaselineLevels[key] = totalStored
            }
        }
        
        // 保存货币分配快照
        assignmentSnapshots[deviceID] = assignments.toList()
        
        // ⚠️ 关键修复：转换为 Map<denomValue, storedCount> 格式用于 DeviceState
        val baselineLevelsMap = mutableMapOf<Int, Int>()
        assignments.forEach { assignment ->
            val totalStored = assignment.stored + assignment.storedInCashbox
            if (totalStored >= 0) {
                val value = assignment.value
                baselineLevelsMap[value] = (baselineLevelsMap[value] ?: 0) + totalStored
            }
        }
        
        val baselineTotal = calculateTotalFromAssignments(assignments)
        
        // ⚠️ 关键修复：更新设备状态
        deviceStates[deviceID] = DeviceState(
            deviceID = deviceID,
            baselineLevels = baselineLevelsMap,
            lastLevels = baselineLevelsMap,
            sessionDeltaCents = 0
        )
        
        // ⚠️ 关键修复：标记 baseline 为已设置
        baselineStatus[deviceID] = BaselineStatus.SET
        
        Log.d(TAG, "从货币分配设置设备基线库存: deviceID=$deviceID, 面额数=${assignments.size}, baselineTotalCents=$baselineTotal (${baselineTotal / 100.0}元), reason=$reason")
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
    fun updateFromAssignments(deviceID: String, assignments: List<com.carwash.carpayment.data.cashdevice.CurrencyAssignment>, sessionActive: Boolean = true, attemptId: String = ""): Int = synchronized(lock) {
        // ⚠️ 关键修复：sessionActive 防护 - sessionActive=false 时，不再触发更新
        if (!sessionActive) {
            Log.d(TAG, "⚠️ 会话已结束，跳过更新: deviceID=$deviceID, attemptId=$attemptId")
            return 0
        }
        
        // ⚠️ 关键修复：确保设备已注册（如果 baseline 未设置，说明设备未注册）
        val deviceState = deviceStates[deviceID]
        if (deviceState == null) {
            Log.w(TAG, "⚠️ 设备未注册，无法更新: deviceID=$deviceID, attemptId=$attemptId")
            Log.w(TAG, "说明: 请先调用 setBaselineFromAssignments 注册设备")
            return 0
        }
        
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
        
        // ⚠️ 关键修复：转换为 Map<denomValue, storedCount> 格式用于 DeviceState
        val currentLevelsMap = mutableMapOf<Int, Int>()
        assignments.forEach { assignment ->
            val totalStored = assignment.stored + assignment.storedInCashbox
            if (totalStored >= 0) {
                val value = assignment.value
                currentLevelsMap[value] = (currentLevelsMap[value] ?: 0) + totalStored
            }
        }
        
        // 计算会话差值：sessionDeltaCents = Σ(value * (totalStored_now - totalStored_baseline))
        // ⚠️ 关键修复：使用 deviceState.baselineLevels 作为基准（确保一致性）
        var sessionDeltaCents = 0
        var baselineTotalCents = 0
        var currentTotalCents = 0
        
        // ⚠️ 关键修复：使用可变副本以便自愈
        val baselineLevelsMap = deviceState.baselineLevels.toMutableMap()
        
        assignments.forEach { assignment ->
            val channel = assignment.channel ?: 0
            val key = "$deviceID:$channel"
            // ⚠️ 关键修复：优先使用 deviceState.baselineLevels，fallback 到 currentTotalStored（自愈）
            val value = assignment.value
            val currentTotalStored = assignment.stored + assignment.storedInCashbox
            // ⚠️ 关键修复：baselineStored 默认值必须是 0 或 currentStored（推荐 currentStored），绝不能是 1
            // 如果 baselineMap 缺某面额：baselineStored = currentStored（最稳）
            val baselineStored = baselineLevelsMap[value] ?: currentTotalStored
            val totalStoredDelta = currentTotalStored - baselineStored
            
            baselineTotalCents += value * baselineStored
            currentTotalCents += value * currentTotalStored
            
            if (totalStoredDelta > 0) {
            sessionDeltaCents += value * totalStoredDelta
            } else if (totalStoredDelta < 0) {
                // ⚠️ 关键修复：库存回退自愈 - 重建该面额的 baseline
                Log.w(TAG, "检测到库存回退: deviceID=$deviceID, value=${value}分, baselineStored=$baselineStored, currentStored=$currentTotalStored, delta=$totalStoredDelta")
                Log.w(TAG, "⚠️ 执行自愈：重建 baseline，baselineStored=$baselineStored -> currentStored=$currentTotalStored")
                
                // 立刻对该面额进行自愈：baselineStored = currentTotalStored（写回 baselineMap）
                baselineLevelsMap[value] = currentTotalStored
                // 同时更新 baselineLevels（用于兼容旧代码）
                baselineLevels[key] = currentTotalStored
                
                // 将该面额贡献的 delta 视为 0（不要让 sessionDeltaCents/paidDelta 变负）
                // totalStoredDelta 已经是负数，不累加到 sessionDeltaCents 即可
                Log.d(TAG, "自愈完成: value=${value}分, 新baselineStored=$currentTotalStored, delta贡献=0")
            }
        }
        
        // 确保不会出现负数
        sessionDeltaCents = maxOf(0, sessionDeltaCents)
        
        // ⚠️ 关键修复：只写一次 deviceStates，使用 newState 变量，避免重复覆盖
        val newState = deviceState.copy(
            baselineLevels = baselineLevelsMap,
            lastLevels = currentLevelsMap,
            sessionDeltaCents = sessionDeltaCents
        )
        deviceStates[deviceID] = newState
        
        // ⚠️ 关键修复：统一日志口径（确保可对账）
        val deltaCents = currentTotalCents - baselineTotalCents
        val deviceCount = deviceStates.size
        val baselineMapSize = newState.baselineLevels.size  // ⚠️ 使用 newState 而不是旧的 deviceState
        val totalCents = getTotalCents()
        val threadName = Thread.currentThread().name
        
        Log.d(TAG, "金额轮询（从货币分配）: deviceID=$deviceID, attemptId=$attemptId, sessionActive=$sessionActive, thread=$threadName")
        Log.d(TAG, "  baselineTotalCents=$baselineTotalCents, currentTotalCents=$currentTotalCents, deltaCents=$deltaCents, sessionDeltaCents=$sessionDeltaCents")
        Log.d(TAG, "  deviceMap.size=$deviceCount, baselineMap.size=$baselineMapSize, 面额数=${assignments.size}")
        Log.d(TAG, "总金额: ${totalCents}分 (${totalCents / 100.0}元), 设备数量=$deviceCount")
        
        // ⚠️ 关键修复：验证对账一致性
        if (deltaCents != sessionDeltaCents) {
            Log.w(TAG, "⚠️ 对账不一致: deltaCents=$deltaCents vs sessionDeltaCents=$sessionDeltaCents")
        }
        
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
     * 更新设备当前库存（轮询时调用，基于 GetAllLevels）
     * ⚠️ 关键修复：更新 deviceStates，确保聚合汇总可对账
     * ⚠️ 关键修复：使用 synchronized 保护，确保一次轮询计算是原子性的
     * @param deviceID 设备ID
     * @param levels 当前库存 - 从 GetAllLevels 获取，格式：Map<面额value, 库存数量stored>
     * @param dataSource 数据源标识（用于日志）："LEVELS" 或 "ASSIGNMENT"
     * @param sessionActive 会话是否活跃（用于并发保护）
     * @param attemptId 轮询尝试ID（用于日志）
     * @return 本次会话累计金额（分）- sessionDeltaCents
     */
    fun updateFromLevels(deviceID: String, levels: Map<Int, Int>, dataSource: String = "LEVELS", sessionActive: Boolean = true, attemptId: String = ""): Int = synchronized(lock) {
        // ⚠️ 关键修复：sessionActive 防护 - sessionActive=false 时，不再触发更新
        if (!sessionActive) {
            Log.d(TAG, "⚠️ 会话已结束，跳过更新: deviceID=$deviceID, attemptId=$attemptId")
            return 0
        }
        
        // ⚠️ 关键修复：确保设备已注册（如果 baseline 未设置，说明设备未注册）
        val deviceState = deviceStates[deviceID]
        if (deviceState == null) {
            Log.w(TAG, "⚠️ 设备未注册，无法更新: deviceID=$deviceID, attemptId=$attemptId")
            Log.w(TAG, "说明: 请先调用 setBaselineFromLevels 注册设备")
            return 0
        }
        
        // ⚠️ 关键修复：保存上一次库存（用于计算变化明细）
        // key 必须包含面额 value，使用 "$deviceID:$value" 而不是 "$deviceID:0"
        levels.keys.forEach { value ->
            val key = "$deviceID:$value"
            previousLevels[key] = currentLevels[key] ?: 0
        }
        
        // ⚠️ 关键修复：更新当前库存和面额映射
        // key 必须包含面额 value，使用 "$deviceID:$value" 而不是 "$deviceID:0"
        levels.forEach { (value, stored) ->
            val key = "$deviceID:$value"
            currentLevels[key] = stored
            valueMap[key] = value
        }
        
        // 计算会话差值：sessionDeltaCents = max(0, Σ(value * (currentStored - baselineStored)))
        var sessionDeltaCents = 0
        var billBaselineTotal = 0
        var billCurrentTotal = 0
        
        // ⚠️ 关键修复：使用 deviceState.baselineLevels 作为基准（确保一致性）
        val baselineLevelsMap = deviceState.baselineLevels.toMutableMap()  // 使用可变副本以便自愈
        
        levels.forEach { (value, currentStored) ->
            // ⚠️ 关键修复：baselineStored 默认值必须是 0 或 currentStored（推荐 currentStored），绝不能是 1
            // 如果 baselineMap 缺某面额：baselineStored = currentStored（最稳）
            val baselineStored = baselineLevelsMap[value] ?: currentStored
            val storedDelta = currentStored - baselineStored
            
            billBaselineTotal += value * baselineStored
            billCurrentTotal += value * currentStored
            
            if (storedDelta > 0) {
                sessionDeltaCents += value * storedDelta
            } else if (storedDelta < 0) {
                // ⚠️ 关键修复：库存回退自愈 - 重建该面额的 baseline
                Log.w(TAG, "检测到库存回退: deviceID=$deviceID, value=${value}分, baselineStored=$baselineStored, currentStored=$currentStored, delta=$storedDelta")
                Log.w(TAG, "⚠️ 执行自愈：重建 baseline，baselineStored=$baselineStored -> currentStored=$currentStored")
                
                // 立刻对该面额进行自愈：baselineStored = currentStored（写回 baselineMap）
                baselineLevelsMap[value] = currentStored
                // ⚠️ 关键修复：同时更新 baselineLevels（用于兼容旧代码），key 必须包含 value
                val key = "$deviceID:$value"
                baselineLevels[key] = currentStored
                
                // 将该面额贡献的 delta 视为 0（不要让 sessionDeltaCents/paidDelta 变负）
                // storedDelta 已经是负数，不累加到 sessionDeltaCents 即可
                Log.d(TAG, "自愈完成: value=${value}分, 新baselineStored=$currentStored, delta贡献=0")
            }
        }
        
        sessionDeltaCents = maxOf(0, sessionDeltaCents)
        
        // ⚠️ 关键修复：只写一次 deviceStates，使用 newState 变量，避免重复覆盖
        val newState = deviceState.copy(
            baselineLevels = baselineLevelsMap,
            lastLevels = levels,
            sessionDeltaCents = sessionDeltaCents
        )
        deviceStates[deviceID] = newState
        
        // ⚠️ 关键修复：统一日志口径（确保可对账）
        val billDelta = billCurrentTotal - billBaselineTotal
        val deviceCount = deviceStates.size
        val baselineMapSize = newState.baselineLevels.size  // ⚠️ 使用 newState 而不是旧的 deviceState
        val totalCents = getTotalCents()
        val threadName = Thread.currentThread().name
        
        Log.d(TAG, "金额轮询（基于$dataSource）: deviceID=$deviceID, attemptId=$attemptId, sessionActive=$sessionActive, thread=$threadName")
        Log.d(TAG, "  baselineTotalCents=$billBaselineTotal, currentTotalCents=$billCurrentTotal, deltaCents=$billDelta, sessionDeltaCents=$sessionDeltaCents")
        Log.d(TAG, "  deviceMap.size=$deviceCount, baselineMap.size=$baselineMapSize, levels条目数=${levels.size}")
        Log.d(TAG, "总金额: ${totalCents}分 (${totalCents / 100.0}元), 设备数量=$deviceCount")
        
        // ⚠️ 关键修复：验证对账一致性
        if (billDelta != sessionDeltaCents) {
            Log.w(TAG, "⚠️ 对账不一致: billDelta=$billDelta vs sessionDeltaCents=$sessionDeltaCents")
        }
        
        return sessionDeltaCents
    }
    
    /**
     * 更新设备当前库存（轮询时调用）- 兼容旧接口
     * @param deviceID 设备ID
     * @param levelsResponse 库存响应（从 GetAllLevels 获取）
     * @return 本次会话累计金额（分）- sessionDeltaCents
     * @deprecated 请使用 updateFromLevels（基于 GetAllLevels）或 updateFromAssignments（基于 GetCurrencyAssignment）
     */
    @Deprecated("请使用 updateFromLevels 或 updateFromAssignments", ReplaceWith("updateFromLevels(deviceID, levels)"))
    fun update(deviceID: String, levelsResponse: LevelsResponse): Int {
        val levels = levelsResponse.levels?.associate { it.value to it.stored } ?: emptyMap()
        return updateFromLevels(deviceID, levels, "LEVELS")
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
     * 设置设备基线库存总金额（在支付开始时调用）
     * ⚠️ 临时方案：使用 GetCurrencyAssignment 的 stored 字段计算库存总金额
     * 会话累计金额 = 当前库存总金额 - 基线库存总金额
     * 
     * @param deviceID 设备ID
     * @param storedTotalCents 基线库存总金额（分）- 从 GetCurrencyAssignment 的 stored 计算
     */
    fun setBaselineStoredTotal(deviceID: String, storedTotalCents: Int) {
        deviceBaselinePaidAmounts[deviceID] = storedTotalCents
        devicePaidAmounts[deviceID] = storedTotalCents  // 初始时当前金额等于基线
        Log.d(TAG, "设置设备基线库存总金额: deviceID=$deviceID, baselineStoredTotalCents=$storedTotalCents (${storedTotalCents / 100.0}€)")
    }
    
    /**
     * 更新设备当前库存总金额（轮询时调用）
     * ⚠️ 临时方案：使用 GetCurrencyAssignment 的 stored 字段计算库存总金额
     * 
     * @param deviceID 设备ID
     * @param storedTotalCents 当前库存总金额（分）- 从 GetCurrencyAssignment 的 stored 计算
     */
    fun updateStoredTotal(deviceID: String, storedTotalCents: Int) {
        val previousStored = devicePaidAmounts[deviceID] ?: 0
        devicePaidAmounts[deviceID] = storedTotalCents
        val delta = storedTotalCents - previousStored
        Log.d(TAG, "更新设备当前库存总金额: deviceID=$deviceID, storedTotalCents=$storedTotalCents (${storedTotalCents / 100.0}€), delta=$delta (${delta / 100.0}€)")
    }
    
    /**
     * 获取指定设备的本次会话累计收款金额（分）
     * ⚠️ 关键修复：使用 deviceStates 而不是 devicePaidAmounts（本版本以 GetAllLevels 差分为唯一口径）
     * @param deviceID 设备ID
     * @return 本次会话累计收款金额（分）
     */
    fun getDeviceSessionCents(deviceID: String): Int {
        // ⚠️ 关键修复：使用 deviceStates 而不是 devicePaidAmounts
        val deviceState = deviceStates[deviceID]
        return deviceState?.sessionDeltaCents ?: 0
    }
    
    /**
     * 获取总金额（分）- 所有设备的本次会话累计收款金额总和
     * ⚠️ 关键修复：使用 deviceStates 聚合，确保汇总值与单设备值一致
     * ⚠️ 关键修复：使用 synchronized 保护读取
     */
    fun getTotalCents(): Int = synchronized(lock) {
        // ⚠️ 关键修复：从 deviceStates 聚合所有设备的 sessionDeltaCents（本版本以 GetAllLevels 差分为唯一口径）
        var totalCents = 0
        deviceStates.forEach { (deviceID, state) ->
            totalCents += state.sessionDeltaCents
        }
        
        return totalCents
    }
    
    /**
     * 获取设备数量（用于日志和验证）
     */
    fun getDeviceCount(): Int = synchronized(lock) {
        return deviceStates.size
    }
    
    /**
     * 获取设备状态快照（用于日志和调试）
     */
    fun getDeviceStatesSnapshot(): Map<String, DeviceState> = synchronized(lock) {
        return deviceStates.toMap()
    }
    
    /**
     * 获取总金额（元）
     */
    fun getTotalAmount(): Double {
        return getTotalCents() / 100.0
    }
    
    /**
     * 获取指定设备的本次会话累计金额（分）- 基于库存差值（已废弃，仅用于找零库存显示）
     * @deprecated 请使用基于真实收款金额的 getDeviceSessionCents（已重载）
     */
    @Deprecated("请使用基于真实收款金额的版本", ReplaceWith("getDeviceSessionCents(deviceID)"))
    fun getDeviceSessionCentsFromLevels(deviceID: String): Int {
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
     * 清除指定设备的 baseline（会话结束时调用）
     * ⚠️ 关键修复：会话结束必须清理 baseline
     */
    fun clearBaselineForDevice(deviceID: String) {
        Log.d(TAG, "清除设备 baseline: deviceID=$deviceID")
        baselineStatus[deviceID] = BaselineStatus.NOT_SET
        deviceStates.remove(deviceID)
        // 清除库存相关字段
        baselineLevels.keys.removeAll { it.startsWith("$deviceID:") }
        currentLevels.keys.removeAll { it.startsWith("$deviceID:") }
        previousLevels.keys.removeAll { it.startsWith("$deviceID:") }
        valueMap.keys.removeAll { it.startsWith("$deviceID:") }
        assignmentSnapshots.remove(deviceID)
    }
    
    /**
     * 重置所有金额（用于新的支付会话）
     * ⚠️ 关键修复：不清除 baseline，只清除当前状态
     * ⚠️ 关键修复：使用 synchronized 保护，避免与轮询并发
     * 注意：不清除基线收款金额，基线只在断开连接时清除
     * 重新采集基线时，会覆盖旧的基线
     */
    fun reset() = synchronized(lock) {
        Log.d(TAG, "重置金额跟踪器（清除当前收款金额，保留基线）")
        // ⚠️ 历史遗留：devicePaidAmounts 已停用，不清除也不影响
        devicePaidAmounts.clear()
        // ⚠️ 关键修复：重置 deviceStates 的 sessionDeltaCents，但保留 baselineLevels
        deviceStates.forEach { (deviceID, state) ->
            deviceStates[deviceID] = state.copy(
                lastLevels = state.baselineLevels,
                sessionDeltaCents = 0
            )
        }
        // 保留库存相关字段（用于找零库存显示）
        currentLevels.clear()
    }
    
    /**
     * 移除指定设备（断开连接时调用）
     * ⚠️ 关键修复：清除所有相关状态
     */
    fun removeDevice(deviceID: String) {
        Log.d(TAG, "移除设备: deviceID=$deviceID")
        clearBaselineForDevice(deviceID)
        deviceBaselinePaidAmounts.remove(deviceID)
        devicePaidAmounts.remove(deviceID)
    }
}
