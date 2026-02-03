package com.carwash.carpayment.data.cashdevice

/**
 * 面额配置数据类
 * 用于统一配置 routes 和 inhibits
 */
data class DenominationConfig(
    /**
     * 想开启找零的面额集合（Route=1，进入 recycler）
     */
    val recyclerValues: Set<Int> = emptySet(),
    
    /**
     * 想禁用接收的面额集合（Inhibit=true，禁止接收）
     */
    val inhibitValues: Set<Int> = emptySet()
)
