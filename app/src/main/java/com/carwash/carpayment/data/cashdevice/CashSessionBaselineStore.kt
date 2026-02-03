package com.carwash.carpayment.data.cashdevice

import android.util.Log

/**
 * 现金会话基线存储（in-memory）
 * 用于记录纸币器和硬币器的会话基线，支持重置基线功能
 */
class CashSessionBaselineStore {
    
    companion object {
        private const val TAG = "CashSessionBaselineStore"
    }
    
    // 设备ID -> baseline totalReceivedCents
    private val baselines = mutableMapOf<String, Int>()
    
    // 设备ID -> baseline LevelsResponse（用于 Levels 差分计算）
    private val baselineLevels = mutableMapOf<String, LevelsResponse>()
    
    /**
     * 设置基线（在开始会话时调用）
     * @param deviceID 设备ID
     * @param baselineCents 基线金额（分）
     */
    fun setBaseline(deviceID: String, baselineCents: Int) {
        baselines[deviceID] = baselineCents
        Log.d(TAG, "设置会话基线: deviceID=$deviceID, baselineCents=$baselineCents (${baselineCents / 100.0}€)")
    }
    
    /**
     * 设置基线 Levels（在开始会话时调用，用于 Levels 差分计算）
     * @param deviceID 设备ID
     * @param baselineLevelsResponse 基线 LevelsResponse
     */
    fun setBaselineLevels(deviceID: String, baselineLevelsResponse: LevelsResponse) {
        baselineLevels[deviceID] = baselineLevelsResponse
        Log.d(TAG, "设置会话基线 Levels: deviceID=$deviceID, levelsCount=${baselineLevelsResponse.levels?.size ?: 0}")
    }
    
    /**
     * 获取基线
     * @param deviceID 设备ID
     * @return 基线金额（分），如果不存在返回0
     */
    fun getBaseline(deviceID: String): Int {
        return baselines[deviceID] ?: 0
    }
    
    /**
     * 获取基线 Levels（用于 Levels 差分计算）
     * @param deviceID 设备ID
     * @return 基线 LevelsResponse，如果不存在返回 null
     */
    fun getBaselineLevels(deviceID: String): LevelsResponse? {
        return baselineLevels[deviceID]
    }
    
    /**
     * 重置基线（将基线设为当前值，相当于让delta归零）
     * @param deviceID 设备ID
     * @param currentCents 当前总收款金额（分）
     */
    fun resetBaseline(deviceID: String, currentCents: Int) {
        baselines[deviceID] = currentCents
        Log.d(TAG, "重置会话基线: deviceID=$deviceID, newBaselineCents=$currentCents (${currentCents / 100.0}€)")
    }
    
    /**
     * 清除基线（会话结束时调用）
     * @param deviceID 设备ID
     */
    fun clearBaseline(deviceID: String) {
        baselines.remove(deviceID)
        baselineLevels.remove(deviceID)
        Log.d(TAG, "清除会话基线: deviceID=$deviceID")
    }
    
    /**
     * 清除所有基线
     */
    fun clearAll() {
        baselines.clear()
        baselineLevels.clear()
        Log.d(TAG, "清除所有会话基线")
    }
    
    /**
     * 获取所有基线（用于调试）
     */
    fun getAllBaselines(): Map<String, Int> {
        return baselines.toMap()
    }
}
