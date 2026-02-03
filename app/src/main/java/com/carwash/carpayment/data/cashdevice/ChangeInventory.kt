package com.carwash.carpayment.data.cashdevice

/**
 * 找零库存明细（按面额）
 */
data class ChangeInventory(
    /**
     * 面额 -> 数量
     * 例如：mapOf(500 to 10, 200 to 20, 100 to 50) 表示 5€ 有 10 张，2€ 有 20 张，1€ 有 50 张
     */
    val denominations: Map<Int, Int> = emptyMap()
) {
    /**
     * 获取总金额（分）
     */
    fun getTotalCents(): Int {
        return denominations.entries.sumOf { (denom, count) -> denom * count }
    }
    
    /**
     * 获取某个面额的数量
     */
    fun getCount(denomination: Int): Int {
        return denominations[denomination] ?: 0
    }
    
    /**
     * 检查是否有足够库存找零
     * @param changeAmountCents 需要找零的金额（分）
     * @return 是否可以找零
     */
    fun canMakeChange(changeAmountCents: Int): Boolean {
        if (changeAmountCents <= 0) return true
        if (denominations.isEmpty()) return false
        
        // 简单的可行性判定（贪心算法）
        var remaining = changeAmountCents
        val sortedDenoms = denominations.keys.sortedDescending()
        
        for (denom in sortedDenoms) {
            val count = denominations[denom] ?: 0
            if (count == 0) continue
            
            val needed = remaining / denom
            val used = minOf(needed, count)
            remaining -= used * denom
            
            if (remaining == 0) return true
        }
        
        return remaining == 0
    }
    
    /**
     * 检查是否有足够库存找零（带失败原因）
     * @param changeAmountCents 需要找零的金额（分）
     * @return ChangeResult 包含是否成功和失败原因
     */
    data class ChangeResult(
        val canMakeChange: Boolean,
        val failureReason: ChangeFailureReason? = null,
        val missingDenoms: List<Int> = emptyList(),  // 缺少的面额（用于诊断）
        val remainingAmount: Int = 0  // 无法找零的剩余金额
    )
    
    enum class ChangeFailureReason {
        LEVELS_UNAVAILABLE,  // 库存明细不可用
        INSUFFICIENT_SUM,     // 总金额不足
        NO_SMALL_DENOMS,      // 缺少小面额（无法找零）
        ALGO_NO_SOLUTION      // 算法无法找到解（虽然总金额足够，但面额组合无法凑出）
    }
    
    /**
     * 检查是否有足够库存找零（带失败原因和诊断信息）
     * @param changeAmountCents 需要找零的金额（分）
     * @return ChangeResult 包含是否成功、失败原因和诊断信息
     */
    fun canMakeChangeWithReason(changeAmountCents: Int): ChangeResult {
        if (changeAmountCents <= 0) {
            return ChangeResult(canMakeChange = true)
        }
        
        if (denominations.isEmpty()) {
            return ChangeResult(
                canMakeChange = false,
                failureReason = ChangeFailureReason.LEVELS_UNAVAILABLE,
                remainingAmount = changeAmountCents
            )
        }
        
        // 检查总金额是否足够
        val totalAvailable = getTotalCents()
        if (totalAvailable < changeAmountCents) {
            return ChangeResult(
                canMakeChange = false,
                failureReason = ChangeFailureReason.INSUFFICIENT_SUM,
                remainingAmount = changeAmountCents - totalAvailable
            )
        }
        
        // 贪心算法尝试找零
        var remaining = changeAmountCents
        val sortedDenoms = denominations.keys.sortedDescending()
        val usedDenoms = mutableListOf<Int>()
        
        for (denom in sortedDenoms) {
            val count = denominations[denom] ?: 0
            if (count == 0) continue
            
            val needed = remaining / denom
            val used = minOf(needed, count)
            if (used > 0) {
                usedDenoms.add(denom)
                remaining -= used * denom
            }
            
            if (remaining == 0) {
                return ChangeResult(canMakeChange = true)
            }
        }
        
        // 如果无法找零，找出缺少的小面额
        if (remaining > 0) {
            // 找出小于剩余金额的最小面额（用于诊断）
            val minDenom = sortedDenoms.minOrNull() ?: 0
            val missingDenoms = if (minDenom > 0 && remaining < minDenom) {
                // 需要更小的面额
                listOf(remaining)  // 缺少这个金额的面额
            } else {
                emptyList()
            }
            
            return ChangeResult(
                canMakeChange = false,
                failureReason = ChangeFailureReason.NO_SMALL_DENOMS,
                missingDenoms = missingDenoms,
                remainingAmount = remaining
            )
        }
        
        return ChangeResult(canMakeChange = true)
    }
    
    /**
     * 计算找零方案（返回使用的面额和数量）
     * @param changeAmountCents 需要找零的金额（分）
     * @return 找零方案（面额 -> 数量），如果无法找零返回 null
     */
    fun calculateChange(changeAmountCents: Int): Map<Int, Int>? {
        if (changeAmountCents <= 0) return emptyMap()
        if (denominations.isEmpty()) return null
        
        val result = mutableMapOf<Int, Int>()
        var remaining = changeAmountCents
        val sortedDenoms = denominations.keys.sortedDescending()
        
        for (denom in sortedDenoms) {
            val count = denominations[denom] ?: 0
            if (count == 0) continue
            
            val needed = remaining / denom
            val used = minOf(needed, count)
            
            if (used > 0) {
                result[denom] = used
                remaining -= used * denom
            }
            
            if (remaining == 0) return result
        }
        
        return if (remaining == 0) result else null
    }
    
    companion object {
        /**
         * 从 LevelEntry 列表创建库存（GetAllLevels 响应）
         */
        fun fromLevels(levels: List<LevelEntry>): ChangeInventory {
            val denominations = mutableMapOf<Int, Int>()
            
            levels.forEach { entry ->
                val denom = entry.value
                val count = entry.stored
                if (denom > 0 && count > 0) {
                    denominations[denom] = count
                }
            }
            
            return ChangeInventory(denominations = denominations)
        }
        
        /**
         * 从多个 LevelEntry 列表合并创建库存（纸币 + 硬币）
         */
        fun fromLevels(
            billLevels: List<LevelEntry>?,
            coinLevels: List<LevelEntry>?
        ): ChangeInventory {
            val denominations = mutableMapOf<Int, Int>()
            
            billLevels?.forEach { entry ->
                val denom = entry.value
                val count = entry.stored
                if (denom > 0 && count > 0) {
                    denominations[denom] = (denominations[denom] ?: 0) + count
                }
            }
            
            coinLevels?.forEach { entry ->
                val denom = entry.value
                val count = entry.stored
                if (denom > 0 && count > 0) {
                    denominations[denom] = (denominations[denom] ?: 0) + count
                }
            }
            
            return ChangeInventory(denominations = denominations)
        }
    }
}
