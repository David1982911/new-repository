package com.carwash.carpayment.data.cashdevice

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * 现金事件响应（V3.4 规范：事件驱动收款）
 * 
 * 从 GetDeviceStatus 返回的事件列表中提取，用于累加收款金额。
 * 事件类型包括：STORED, STACKED, VALUE_ADDED, COIN_CREDIT
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class CashEventResponse(
    /**
     * 事件类型（字符串形式，如 "STORED", "STACKED", "VALUE_ADDED", "COIN_CREDIT"）
     */
    @SerialName("EventType") @JsonNames("EventType", "eventType") val eventType: String? = null,
    
    /**
     * 事件类型（字符串形式，兼容字段名）
     */
    @SerialName("EventTypeAsString") @JsonNames("EventTypeAsString", "eventTypeAsString") val eventTypeAsString: String? = null,
    
    /**
     * 事件金额（分）
     * 例如：500 表示 5€，1000 表示 10€
     */
    @SerialName("Value") val value: Long = 0L,
    
    /**
     * 面额（分）
     * 兼容字段名
     */
    @SerialName("Denomination") val denomination: Long? = null,
    
    /**
     * 通道号
     */
    @SerialName("Channel") val channel: Int? = null,
    
    /**
     * 货币代码（如 "EUR"）
     */
    @SerialName("CountryCode") val countryCode: String? = null,
    
    /**
     * 设备ID
     */
    @SerialName("DeviceID") val deviceID: String? = null
) {
    /**
     * 获取事件类型字符串（优先使用 eventTypeAsString，其次 eventType）
     */
    val actualEventType: String?
        get() = eventTypeAsString ?: eventType
    
    /**
     * 获取事件金额（分）
     * 优先使用 value，其次 denomination
     */
    val amountCents: Long
        get() = if (value > 0) value else (denomination ?: 0L)
    
    /**
     * 判断是否为收款事件（需要累加金额）
     */
    fun isPaymentEvent(): Boolean {
        val type = actualEventType ?: return false
        return when (type.uppercase()) {
            "STORED", "STACKED", "VALUE_ADDED", "COIN_CREDIT" -> true
            else -> false
        }
    }
}
