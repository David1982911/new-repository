package com.carwash.carpayment.data.cashdevice

/**
 * 纸币器钞箱配置（用于路由与风控提示）
 * 
 * 纸币器（SPECTRAL_PAYOUT-0）两钞箱逻辑：
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
 * 路由规则：
 * - 投入某面额纸币时，先判断该面额是否被配置为 "可找零面额"
 * - 若是可找零面额：
 *   - Recycler 未满（<80）→ 进入 Recycler
 *   - Recycler 已满（=80）→ 即使可找零，也必须进入主钞箱
 * - 若是不可找零面额：直接进入主钞箱
 * 
 * 需要实时监控并在 UI 提示：
 * - Recycler 当前张数 / 剩余容量
 * - Cashbox 当前张数 / 是否接近满 / 是否满箱
 * 
 * 注意：如果服务端 API 目前只能给 GetAllLevels（按面额 Stored），但不能区分 Cashbox vs Recycler，
 * 先在 UI 标注"当前 API 未区分钞箱"，把规则实现为"配置与预留"，等拿到更细的 API 再接入。
 */
data class BillCashboxConfig(
    val recyclerCapacity: Int = 80,  // 循环钞箱容量（张）
    val cashboxCapacity: Int = 1000,  // 主钞箱容量（张）
    val recyclableDenominations: List<Int> = listOf(100, 200, 500, 1000, 2000)  // 可找零面额列表（分）
) {
    /**
     * 检查面额是否可找零
     */
    fun isRecyclable(valueCents: Int): Boolean {
        return recyclableDenominations.contains(valueCents)
    }
    
    /**
     * 获取路由规则描述
     */
    fun getRouteRuleDescription(valueCents: Int, recyclerUsed: Int): String {
        return if (isRecyclable(valueCents)) {
            if (recyclerUsed < recyclerCapacity) {
                "可找零面额 → 进入循环钞箱（当前 ${recyclerUsed}/${recyclerCapacity} 张）"
            } else {
                "可找零面额，但循环钞箱已满 → 进入主钞箱"
            }
        } else {
            "不可找零面额 → 进入主钞箱"
        }
    }
}
