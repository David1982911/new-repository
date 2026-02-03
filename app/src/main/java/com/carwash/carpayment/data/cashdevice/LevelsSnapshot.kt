package com.carwash.carpayment.data.cashdevice

/**
 * Levels 快照数据结构（用于会话差分计算）
 * 
 * DenomKey = (countryCode, value)，value 为 cents（EUR: 100=1€）
 */
data class DenomKey(
    val countryCode: String,
    val valueCents: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DenomKey) return false
        return countryCode == other.countryCode && valueCents == other.valueCents
    }
    
    override fun hashCode(): Int {
        var result = countryCode.hashCode()
        result = 31 * result + valueCents
        return result
    }
}

/**
 * Levels 快照（设备在某个时间点的库存状态）
 */
data class LevelsSnapshot(
    val deviceId: String,
    val ts: Long,
    val levels: Map<DenomKey, Int>  // DenomKey -> Stored 数量
) {
    /**
     * 计算总金额（分）
     */
    fun calculateTotalCents(): Int {
        return levels.entries.sumOf { (key, stored) ->
            key.valueCents * stored
        }
    }
}
