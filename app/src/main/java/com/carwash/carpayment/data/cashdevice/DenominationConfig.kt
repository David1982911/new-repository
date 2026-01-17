package com.carwash.carpayment.data.cashdevice

import android.util.Log

/**
 * 面额配置（用于纸币器找零策略）
 * 
 * ===== 纸币器（SPECTRAL_PAYOUT-0）两钞箱逻辑 =====
 * 
 * 纸币器有两个钞箱：
 * 
 * 1. 循环钞箱 / 循环鼓（Recycler）：容量 80 张
 *    - 只有被设置为"可找零面额"的纸币，才会优先进入循环钞箱
 *    - 当循环钞箱达到 80 张后，即便该面额被设置为可找零，新收的钱也会直接进入主钞箱
 *    - 循环钞箱里的钱 可以用于找零吐出
 *    - 一旦找零吐出了纸币，循环钞箱腾出位置，又可以继续接收新的"可找零面额"进入
 * 
 * 2. 主钞箱（Cashbox/Stacker）：容量 1000 张
 *    - 被设置为"不可找零面额"的纸币，直接进入主钞箱
 *    - 主钞箱里的钱 不能用于找零
 *    - 主钞箱满 1000 张后，设备应拒收新钱（需要状态/错误提示）
 * 
 * ===== 路由规则（必须写进注释 + 后续用于配置） =====
 * 
 * 投入某面额纸币时，先判断该面额是否被配置为 "可找零面额"：
 * 
 * - 若是可找零面额：
 *   - Recycler 未满（<80）→ 进入 Recycler
 *   - Recycler 已满（=80）→ 即使可找零，也必须进入主钞箱
 * 
 * - 若是不可找零面额：直接进入主钞箱
 * 
 * Recycler 会因为找零而减少：当发生 dispense（找零）后，Recycler 释放容量 → 可以再次接收新的"可找零面额"
 * 
 * ===== 需要实时监控并在 UI 提示 =====
 * 
 * - Recycler 当前张数 / 剩余容量
 * - Cashbox 当前张数 / 是否接近满 / 是否满箱
 * 
 * 注意：如果服务端 API 目前只能给 GetAllLevels（按面额 Stored），但不能区分 Cashbox vs Recycler，
 * 先在 UI 标注"当前 API 未区分钞箱"，把规则实现为"配置与预留"，等拿到更细的 API 再接入。
 */
data class DenominationConfig(
    val valueCents: Int,  // 面额（分），如 500 表示 5€
    val isRecyclable: Boolean  // 是否可找零（可进入循环钞箱）
) {
    companion object {
        private const val TAG = "DenominationConfig"
        
        /**
         * 默认配置：常见的可找零面额
         * 例如：5€、10€、20€ 可找零，50€、100€ 不可找零
         */
        fun getDefaultConfig(): List<DenominationConfig> {
            return listOf(
                DenominationConfig(100, isRecyclable = true),   // 1€ 可找零
                DenominationConfig(200, isRecyclable = true),   // 2€ 可找零
                DenominationConfig(500, isRecyclable = true),   // 5€ 可找零
                DenominationConfig(1000, isRecyclable = true),  // 10€ 可找零
                DenominationConfig(2000, isRecyclable = true),  // 20€ 可找零
                DenominationConfig(5000, isRecyclable = false), // 50€ 不可找零
                DenominationConfig(10000, isRecyclable = false) // 100€ 不可找零
            )
        }
        
        /**
         * 从面额列表获取配置
         */
        fun fromDenominations(denominations: List<Int>): List<DenominationConfig> {
            val defaultConfig = getDefaultConfig()
            return denominations.map { valueCents ->
                // 查找默认配置，如果不存在则默认为不可找零
                defaultConfig.find { it.valueCents == valueCents }
                    ?: DenominationConfig(valueCents, isRecyclable = false)
            }
        }
        
        /**
         * 检查面额是否可找零
         */
        fun isRecyclable(configs: List<DenominationConfig>, valueCents: Int): Boolean {
            return configs.find { it.valueCents == valueCents }?.isRecyclable ?: false
        }
    }
}

/**
 * 纸币器钞箱信息（如果 API 支持）
 * 注意：当前接口可能不支持拆分库存，先预留结构
 */
data class BillCashboxInfo(
    val recyclerCapacity: Int = 80,  // 循环钞箱容量（张）
    val recyclerUsed: Int = 0,  // 循环钞箱已使用（张）
    val cashboxCapacity: Int = 1000,  // 主钞箱容量（张）
    val cashboxUsed: Int = 0  // 主钞箱已使用（张）
) {
    /**
     * 循环钞箱剩余容量（张）
     */
    val recyclerRemaining: Int
        get() = recyclerCapacity - recyclerUsed
    
    /**
     * 主钞箱剩余容量（张）
     */
    val cashboxRemaining: Int
        get() = cashboxCapacity - cashboxUsed
    
    /**
     * 是否循环钞箱已满
     */
    val isRecyclerFull: Boolean
        get() = recyclerUsed >= recyclerCapacity
    
    /**
     * 是否主钞箱已满
     */
    val isCashboxFull: Boolean
        get() = cashboxUsed >= cashboxCapacity
}
