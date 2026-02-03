package com.carwash.carpayment.data.cashdevice

/**
 * 可接收面额集合计算器
 */
class AcceptedDenominationsCalculator {
    
    /**
     * 计算可接收的面额集合
     * 
     * @param targetAmountCents 目标金额（分）
     * @param paidAmountCents 已付金额（分）
     * @param changeInventory 找零库存
     * @param availableDenominations 设备支持的面额列表（分）
     * @param changeEnabled 是否启用找零
     * @return 可接收的面额集合
     */
    fun calculateAcceptedDenominations(
        targetAmountCents: Int,
        paidAmountCents: Int,
        changeInventory: ChangeInventory,
        availableDenominations: List<Int>,
        changeEnabled: Boolean = true
    ): Set<Int> {
        val remaining = targetAmountCents - paidAmountCents
        
        if (remaining <= 0) {
            // 已付足，不再接收
            return emptySet()
        }
        
        if (!changeEnabled) {
            // 找零未启用：只能接收不超过剩余金额的面额
            return availableDenominations.filter { it <= remaining }.toSet()
        }
        
        // 找零已启用：检查每个面额是否会导致"找零不可达"
        val accepted = mutableSetOf<Int>()
        
        for (denom in availableDenominations) {
            val newPaid = paidAmountCents + denom
            val overpay = newPaid - targetAmountCents
            
            if (overpay <= 0) {
                // 不会超付，允许接收
                accepted.add(denom)
            } else {
                // 会超付，检查是否可以找零
                if (changeInventory.canMakeChange(overpay)) {
                    accepted.add(denom)
                }
                // 如果无法找零，不添加到 accepted（拒收）
            }
        }
        
        return accepted
    }
    
    /**
     * 检查某个面额是否可以接收
     * 
     * @param denomination 面额（分）
     * @param targetAmountCents 目标金额（分）
     * @param paidAmountCents 已付金额（分）
     * @param changeInventory 找零库存
     * @param changeEnabled 是否启用找零
     * @return 是否可以接收
     */
    fun canAcceptDenomination(
        denomination: Int,
        targetAmountCents: Int,
        paidAmountCents: Int,
        changeInventory: ChangeInventory,
        changeEnabled: Boolean = true
    ): Boolean {
        val remaining = targetAmountCents - paidAmountCents
        
        if (remaining <= 0) {
            return false // 已付足
        }
        
        if (!changeEnabled) {
            return denomination <= remaining // 找零未启用，只能接收不超过剩余金额的面额
        }
        
        val newPaid = paidAmountCents + denomination
        val overpay = newPaid - targetAmountCents
        
        if (overpay <= 0) {
            return true // 不会超付
        }
        
        // 会超付，检查是否可以找零
        return changeInventory.canMakeChange(overpay)
    }
}
