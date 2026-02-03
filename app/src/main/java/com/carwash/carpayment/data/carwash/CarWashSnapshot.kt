package com.carwash.carpayment.data.carwash

/**
 * 洗车机状态快照
 */
data class CarWashSnapshot(
    val reg217: Int? = null,  // 故障状态 (0=无故障, 1=有故障)
    val reg752: Int? = null,  // 前车状态 (0=前车已离开, 1=前车还在)
    val reg240: Int? = null,  // 可再次洗车 (0=不可, 1=可以)
    val reg214: Int? = null,  // 启动洗车状态 (0=非自动, 1=自动状态)
    val reg102: Int? = null,  // 车位状态 (0=车未到位, 1=车到位)
    val timestampMillis: Long = System.currentTimeMillis(),
    val isOnline: Boolean = true
) {
    /**
     * 快照是否过期
     */
    fun isExpired(maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS): Boolean {
        return System.currentTimeMillis() - timestampMillis > maxAgeMillis
    }
    
    /**
     * 是否有故障
     */
    fun hasFault(): Boolean = reg217 == 1
    
    /**
     * 前车是否还在
     */
    fun hasPreviousCar(): Boolean = reg752 == 1
    
    /**
     * 是否可洗车
     */
    fun canWash(): Boolean = reg240 == 1
    
    /**
     * 是否在自动状态
     */
    fun isAutoStatus(): Boolean = reg214 == 1
    
    /**
     * 车是否到位
     */
    fun isCarInPosition(): Boolean = reg102 == 1
    
    /**
     * 获取状态摘要（用于日志）
     */
    fun getStatusSummary(): String {
        return "Snapshot(217=${reg217}, 752=${reg752}, 240=${reg240}, 214=${reg214}, 102=${reg102}, online=$isOnline, age=${System.currentTimeMillis() - timestampMillis}ms)"
    }
    
    companion object {
        /**
         * 默认最大年龄（5秒）
         */
        const val DEFAULT_MAX_AGE_MILLIS = 5000L
        
        /**
         * 创建离线快照
         */
        fun offline(): CarWashSnapshot {
            return CarWashSnapshot(isOnline = false)
        }
    }
}
