package com.carwash.carpayment.data.cashdevice

import android.util.Log

/**
 * 找零安全性计算器
 * 使用动态规划算法判断在给定库存下能否精确凑出找零金额
 */
object ChangeSafetyCalculator {
    
    private const val TAG = "ChangeSafetyCalculator"
    
    /**
     * 判断在给定库存下能否精确凑出金额 changeCents
     * 
     * 算法：动态规划（多重背包优化）
     * 修复：正确处理多张相同面额的情况，确保能准确判断能否精确找零
     * 
     * @param changeCents 需要找零的金额（分）
     * @param inventory 当前 recycler 库存，Map<面额（分）, 数量>
     * @return true 表示可以精确找零，false 表示无法精确找零
     */
    fun canMakeChange(changeCents: Int, inventory: Map<Int, Int>): Boolean {
        // ⚠️ 日志：函数入口
        Log.d(TAG, "========== canMakeChange 函数调用开始 ==========")
        Log.d(TAG, "进入 canMakeChange，参数 changeCents=${changeCents}分 (${changeCents / 100.0}€), inventory=${inventory.entries.joinToString { "${it.key}分×${it.value}" }}")
        
        // ⚠️ 日志：边界检查
        if (changeCents == 0) {
            Log.d(TAG, "需要找零的金额为 0，直接返回 true")
            Log.d(TAG, "========== canMakeChange 函数调用结束，返回 true ==========")
            return true
        }
        if (changeCents < 0) {
            Log.w(TAG, "无效的找零金额 changeCents=$changeCents")
            Log.d(TAG, "========== canMakeChange 函数调用结束，返回 false ==========")
            return false
        }
        if (inventory.isEmpty()) {
            Log.w(TAG, "库存为空，无法进行找零")
            Log.d(TAG, "========== canMakeChange 函数调用结束，返回 false ==========")
            return false
        }
        
        // ⚠️ 日志：初始化动态规划数组
        Log.d(TAG, "初始化 dp 数组，大小为 ${changeCents + 1}")
        val dp = BooleanArray(changeCents + 1)
        dp[0] = true
        Log.d(TAG, "dp[0] = true (金额0总是可以凑出)")

        // 遍历每种面额及其数量
        var denominationIndex = 0
        for ((value, count) in inventory) {
            denominationIndex++
            Log.d(TAG, "---------- 处理面额 #$denominationIndex: value=${value}分, count=$count ----------")
            
            if (value <= 0 || count <= 0) {
                Log.w(TAG, "跳过无效面额: value=$value, count=$count")
                continue
            }
            
            // 采用"多重背包"优化：从大到小更新dp，避免同一种面额多次使用影响后续
            var updateCount = 0
            for (x in changeCents downTo 0) {
                if (dp[x]) {
                    var total = x
                    var usedCount = 0
                    // 尝试使用 1 到 count 张该面额
                    repeat(count) {
                        total += value
                        if (total > changeCents) {
                            Log.d(TAG, "  从金额 $x 使用 ${usedCount + 1} 张${value}分，总金额 $total > $changeCents，停止")
                            return@repeat
                        }
                        if (!dp[total]) {
                            dp[total] = true
                            updateCount++
                            usedCount++
                            Log.d(TAG, "更新 dp[$total] 为 true")
                            Log.d(TAG, "  从金额 $x 使用 $usedCount 张${value}分，可凑出金额 $total (dp[$total]=true)")
                        } else {
                            usedCount++
                            Log.d(TAG, "  从金额 $x 使用 $usedCount 张${value}分，金额 $total 已可达 (dp[$total]已为true)")
                        }
                    }
                }
            }
            Log.d(TAG, "面额 ${value}分×$count 处理完成，新增可达金额数: $updateCount")
        }
        
        // ⚠️ 日志：计算结果
        val result = dp[changeCents]
        val reachableAmounts = dp.indices.filter { dp[it] }
        Log.d(TAG, "动态规划完成，可达金额: ${reachableAmounts.joinToString { "$it" }}")
        
        if (result) {
            Log.d(TAG, "成功找到可以凑出的金额 changeCents=$changeCents")
        } else {
            Log.d(TAG, "无法凑出金额 changeCents=$changeCents")
        }
        
        // ⚠️ 日志：函数结束
        Log.d(TAG, "退出 canMakeChange，返回 dp[$changeCents]: $result")
        Log.d(TAG, "========== canMakeChange 函数调用结束，返回 $result ==========")
        return result
    }
    
    /**
     * 计算需要禁用的不安全面额列表
     * 
     * @param targetCents 目标金额（分）
     * @param allDenominations 所有支持的面额列表（分）
     * @param recyclerInventory 当前 recycler 库存，Map<面额（分）, 数量>
     * @return 需要禁用的面额列表（分）
     */
    fun calculateUnsafeDenominations(
        targetCents: Int,
        allDenominations: List<Int>,
        recyclerInventory: Map<Int, Int>
    ): List<Int> {
        val unsafeDenominations = mutableListOf<Int>()
        
        for (denomination in allDenominations) {
            if (denomination > targetCents) {
                // 需要找零
                val changeCents = denomination - targetCents
                
                // ⚠️ 日志：调用 canMakeChange 之前
                Log.d(TAG, "========== 准备调用 canMakeChange ==========")
                Log.d(TAG, "检查调用路径：准备调用 canMakeChange")
                Log.d(TAG, "调用参数: changeCents=${changeCents}分 (${changeCents / 100.0}€), denomination=${denomination}分, targetCents=${targetCents}分")
                Log.d(TAG, "调用参数: inventory=${recyclerInventory.entries.joinToString { "${it.key}分×${it.value}" }}")
                
                // 检查能否精确找零
                val canMake = canMakeChange(changeCents, recyclerInventory)
                
                // ⚠️ 日志：调用 canMakeChange 之后
                if (canMake) {
                    Log.d(TAG, "canMakeChange 函数被成功调用，返回 true")
                    Log.d(TAG, "面额 ${denomination}分 可以安全接收（找零 ${changeCents}分 可行）")
                } else {
                    Log.d(TAG, "canMakeChange 函数被成功调用，返回 false")
                    Log.w(TAG, "不安全面额: ${denomination}分 (需要找零 ${changeCents}分，但库存不足)")
                    unsafeDenominations.add(denomination)
                }
                Log.d(TAG, "========== canMakeChange 调用完成 ==========")
            }
        }
        
        Log.d(TAG, "不安全面额计算完成: targetCents=$targetCents, 总面额数=${allDenominations.size}, 不安全面额数=${unsafeDenominations.size}")
        return unsafeDenominations
    }
}
