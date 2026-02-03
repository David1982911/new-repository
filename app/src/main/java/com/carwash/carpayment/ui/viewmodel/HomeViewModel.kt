package com.carwash.carpayment.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carwash.carpayment.CarPaymentApplication
import com.carwash.carpayment.data.WashProgram
import com.carwash.carpayment.data.carwash.CarWashDeviceRepository
import com.carwash.carpayment.data.carwash.CarWashSnapshot
import com.carwash.carpayment.data.config.ProgramConfigRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 首页洗车机状态 UI 状态
 */
data class CarWashSnapshotUi(
    val snapshot: CarWashSnapshot? = null,
    val isRefreshing: Boolean = false,
    val statusText: String = "",
    val statusType: StatusType = StatusType.UNKNOWN
) {
    enum class StatusType {
        READY,      // 就绪
        FAULT,      // 故障
        OCCUPIED,   // 占用中
        NOT_READY,  // 未就绪
        UNKNOWN,    // 未知/更新中
        OFFLINE     // 离线
    }
}

/**
 * HomeViewModel - 管理首页（选择洗车程序）状态
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "HomeViewModel"
        private const val POLL_INTERVAL_MS = 2000L // 轮询间隔 2 秒
        private const val SNAPSHOT_MAX_AGE_MS = 5000L // 快照最大年龄 5 秒
    }
    
    private val configRepository = ProgramConfigRepository(application)
    
    // 洗车机设备（使用单例）
    private val carWashRepository: CarWashDeviceRepository? by lazy {
        CarPaymentApplication.carWashRepository
    }
    
    // 可用的洗车程序（从配置读取，通过StateFlow提供）
    private val _programs = MutableStateFlow<List<WashProgram>>(emptyList())
    val programs: StateFlow<List<WashProgram>> = _programs.asStateFlow()
    
    // 洗车机状态快照
    private val _snapshotState = MutableStateFlow<CarWashSnapshotUi>(CarWashSnapshotUi())
    val snapshotState: StateFlow<CarWashSnapshotUi> = _snapshotState.asStateFlow()
    
    // 轮询 Job
    private var pollingJob: Job? = null
    
    init {
        // 从配置读取程序列表
        viewModelScope.launch {
            configRepository.configFlow.collect { config ->
                Log.d(TAG, "========== HomeViewModel 程序列表更新 ==========")
                Log.d(TAG, "source=ProgramConfigRepository.configFlow (DataStore)")
                Log.d(TAG, "config.programs=${config.programs.map { "${it.id}: price=${it.price}€ (${(it.price * 100).toInt()}分)" }}")
                
                _programs.value = config.programs.map { programConfig ->
                    val program = WashProgram(
                        id = programConfig.id,
                        name = programConfig.nameKey,  // 实际应从资源获取，这里先用 key
                        minutes = programConfig.minutes,
                        price = programConfig.price,
                        addons = programConfig.addons
                    )
                    Log.d(TAG, "转换: ${programConfig.id} -> WashProgram(id=${program.id}, price=${program.price}€, priceCents=${(program.price * 100).toInt()})")
                    program
                }
                Log.d(TAG, "最终 programs=${_programs.value.map { "${it.id}: ${it.price}€" }}")
                Log.d(TAG, "==============================================")
            }
        }
    }
    
    // 选中的程序
    private val _selectedProgram = MutableStateFlow<WashProgram?>(null)
    val selectedProgram: StateFlow<WashProgram?> = _selectedProgram.asStateFlow()
    
    /**
     * 选择洗车程序
     */
    fun selectProgram(programId: String) {
        Log.d(TAG, "选择洗车程序，programId: $programId")
        viewModelScope.launch {
            val program = programs.value.find { it.id == programId }
            _selectedProgram.value = program
        }
    }
    
    /**
     * 重置选中的程序
     */
    fun resetSelectedProgram() {
        Log.d(TAG, "重置选中的程序")
        _selectedProgram.value = null
    }
    
    /**
     * 更新程序配置（供后台管理使用）
     */
    fun updateProgramConfig(programId: String, minutes: Int? = null, price: Double? = null, addons: List<String>? = null) {
        viewModelScope.launch {
            configRepository.updateProgram(programId, minutes, price, addons)
            Log.d(TAG, "程序配置已更新: $programId")
        }
    }
    
    /**
     * 重置配置为默认值
     */
    fun resetConfig() {
        viewModelScope.launch {
            configRepository.resetToDefault()
            Log.d(TAG, "配置已重置为默认值")
        }
    }
    
    /**
     * 刷新一次快照（进入首页立即调用）
     */
    fun refreshOnce() {
        viewModelScope.launch {
            refreshSnapshot()
        }
    }
    
    /**
     * 开始轮询（首页可见期间调用）
     */
    fun startPolling() {
        if (pollingJob?.isActive == true) {
            Log.d(TAG, "轮询已在运行，跳过启动")
            return
        }
        
        Log.d(TAG, "开始轮询洗车机状态")
        pollingJob = viewModelScope.launch {
            // 立即刷新一次
            refreshSnapshot()
            
            // 然后按固定间隔轮询
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                refreshSnapshot()
            }
        }
    }
    
    /**
     * 停止轮询（首页不可见时调用）
     */
    fun stopPolling() {
        Log.d(TAG, "停止轮询洗车机状态")
        pollingJob?.cancel()
        pollingJob = null
    }
    
    /**
     * 暂停轮询（进入支付/流程时调用）
     */
    fun pausePolling() {
        Log.d(TAG, "暂停轮询洗车机状态（进入支付/流程）")
        pollingJob?.cancel()
        pollingJob = null
    }
    
    /**
     * 恢复轮询（离开支付/流程后调用）
     */
    fun resumePolling() {
        if (pollingJob?.isActive == true) {
            Log.d(TAG, "轮询已在运行，跳过恢复")
            return
        }
        Log.d(TAG, "恢复轮询洗车机状态（离开支付/流程）")
        startPolling()
    }
    
    /**
     * 刷新快照
     */
    private suspend fun refreshSnapshot() {
        try {
            _snapshotState.value = _snapshotState.value.copy(isRefreshing = true)
            
            val repository = carWashRepository
            if (repository == null) {
                Log.w(TAG, "洗车机 Repository 未初始化")
                _snapshotState.value = CarWashSnapshotUi(
                    snapshot = CarWashSnapshot.offline(),
                    isRefreshing = false,
                    statusText = "Offline",
                    statusType = CarWashSnapshotUi.StatusType.OFFLINE
                )
                return
            }
            
            val snapshot = repository.api.readSnapshot()
            
            if (snapshot == null) {
                Log.w(TAG, "读取快照失败")
                _snapshotState.value = CarWashSnapshotUi(
                    snapshot = CarWashSnapshot.offline(),
                    isRefreshing = false,
                    statusText = "Unknown",
                    statusType = CarWashSnapshotUi.StatusType.UNKNOWN
                )
                return
            }
            
            // 更新状态
            val statusType = determineStatusType(snapshot)
            val statusText = getStatusText(snapshot, statusType)
            
            _snapshotState.value = CarWashSnapshotUi(
                snapshot = snapshot,
                isRefreshing = false,
                statusText = statusText,
                statusType = statusType
            )
            
            Log.d(TAG, "快照刷新成功: ${snapshot.getStatusSummary()}, statusType=$statusType")
            
        } catch (e: kotlinx.coroutines.CancellationException) {
            // CancellationException 不能以 Error 打印，避免误导
            Log.d(TAG, "快照刷新被取消（正常行为）")
            throw e // 重新抛出，让协程正确取消
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // JobCancellationException 不能以 Error 打印，避免误导
            Log.d(TAG, "快照刷新被取消（正常行为）")
            throw e // 重新抛出，让协程正确取消
        } catch (e: Exception) {
            Log.e(TAG, "刷新快照异常", e)
            _snapshotState.value = CarWashSnapshotUi(
                snapshot = CarWashSnapshot.offline(),
                isRefreshing = false,
                statusText = "Error",
                statusType = CarWashSnapshotUi.StatusType.UNKNOWN
            )
        }
    }
    
    /**
     * 确定状态类型
     */
    private fun determineStatusType(snapshot: CarWashSnapshot): CarWashSnapshotUi.StatusType {
        // 检查是否过期或离线
        if (!snapshot.isOnline || snapshot.isExpired(SNAPSHOT_MAX_AGE_MS)) {
            return CarWashSnapshotUi.StatusType.UNKNOWN
        }
        
        // 检查故障
        if (snapshot.hasFault()) {
            return CarWashSnapshotUi.StatusType.FAULT
        }
        
        // 检查前车是否还在
        if (snapshot.hasPreviousCar()) {
            return CarWashSnapshotUi.StatusType.OCCUPIED
        }
        
        // 检查是否可洗车
        if (!snapshot.canWash()) {
            return CarWashSnapshotUi.StatusType.NOT_READY
        }
        
        // 在线且无上述问题 → Ready
        return CarWashSnapshotUi.StatusType.READY
    }
    
    /**
     * 获取状态文本（多语言，暂时返回英文，后续接入语言系统）
     */
    private fun getStatusText(snapshot: CarWashSnapshot, statusType: CarWashSnapshotUi.StatusType): String {
        return when (statusType) {
            CarWashSnapshotUi.StatusType.READY -> "Ready"
            CarWashSnapshotUi.StatusType.FAULT -> "Fault"
            CarWashSnapshotUi.StatusType.OCCUPIED -> "Occupied"
            CarWashSnapshotUi.StatusType.NOT_READY -> "Not Ready"
            CarWashSnapshotUi.StatusType.UNKNOWN -> if (snapshot.isExpired(SNAPSHOT_MAX_AGE_MS)) "Updating..." else "Unknown"
            CarWashSnapshotUi.StatusType.OFFLINE -> "Offline"
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}
