package com.carwash.carpayment.data.washflow

/**
 * 超时阶段（752/240/214/102）
 */
enum class TimeoutPhase {
    GATE_CHECK_752,     // 等待 752=0（前车离开）
    GATE_CHECK_240,     // 等待 240=1（设备就绪）
    START_214,          // 等待 214=自动状态（启动确认）
    MONITOR_102         // 监控 102 直到结束
}

/**
 * 超时策略配置（按 MODEL1-4）
 */
data class TimeoutPolicy(
    val model: Int, // 1-4
    val phases: Map<TimeoutPhase, PhaseTimeoutConfig>
) {
    companion object {
        /**
         * 获取默认超时策略（按 MODEL1-4）
         */
        fun getDefaultPolicy(model: Int): TimeoutPolicy {
            return when (model) {
                1 -> TimeoutPolicy(
                    model = 1,
                    phases = mapOf(
                        TimeoutPhase.GATE_CHECK_752 to PhaseTimeoutConfig(
                            softTimeoutSec = 60,      // 1分钟
                            hardTimeoutSec = 300,     // 5分钟
                            pollIntervalMs = 1000     // 1秒
                        ),
                        TimeoutPhase.GATE_CHECK_240 to PhaseTimeoutConfig(
                            softTimeoutSec = 30,      // 30秒
                            hardTimeoutSec = 60,      // 1分钟
                            pollIntervalMs = 500     // 0.5秒
                        ),
                        TimeoutPhase.START_214 to PhaseTimeoutConfig(
                            softTimeoutSec = 5,       // 5秒
                            hardTimeoutSec = 10,      // 10秒
                            pollIntervalMs = 1000     // 1秒
                        ),
                        TimeoutPhase.MONITOR_102 to PhaseTimeoutConfig(
                            softTimeoutSec = 300,     // 5分钟
                            hardTimeoutSec = 600,     // 10分钟
                            pollIntervalMs = 2000     // 2秒
                        )
                    )
                )
                2 -> TimeoutPolicy(
                    model = 2,
                    phases = mapOf(
                        TimeoutPhase.GATE_CHECK_752 to PhaseTimeoutConfig(
                            softTimeoutSec = 60,
                            hardTimeoutSec = 300,
                            pollIntervalMs = 1000
                        ),
                        TimeoutPhase.GATE_CHECK_240 to PhaseTimeoutConfig(
                            softTimeoutSec = 30,
                            hardTimeoutSec = 60,
                            pollIntervalMs = 500
                        ),
                        TimeoutPhase.START_214 to PhaseTimeoutConfig(
                            softTimeoutSec = 5,
                            hardTimeoutSec = 10,
                            pollIntervalMs = 1000
                        ),
                        TimeoutPhase.MONITOR_102 to PhaseTimeoutConfig(
                            softTimeoutSec = 600,     // 10分钟（Mode 2 更长）
                            hardTimeoutSec = 1200,   // 20分钟
                            pollIntervalMs = 2000
                        )
                    )
                )
                3 -> TimeoutPolicy(
                    model = 3,
                    phases = mapOf(
                        TimeoutPhase.GATE_CHECK_752 to PhaseTimeoutConfig(
                            softTimeoutSec = 60,
                            hardTimeoutSec = 300,
                            pollIntervalMs = 1000
                        ),
                        TimeoutPhase.GATE_CHECK_240 to PhaseTimeoutConfig(
                            softTimeoutSec = 30,
                            hardTimeoutSec = 60,
                            pollIntervalMs = 500
                        ),
                        TimeoutPhase.START_214 to PhaseTimeoutConfig(
                            softTimeoutSec = 5,
                            hardTimeoutSec = 10,
                            pollIntervalMs = 1000
                        ),
                        TimeoutPhase.MONITOR_102 to PhaseTimeoutConfig(
                            softTimeoutSec = 900,     // 15分钟（Mode 3 更长）
                            hardTimeoutSec = 1800,   // 30分钟
                            pollIntervalMs = 2000
                        )
                    )
                )
                4 -> TimeoutPolicy(
                    model = 4,
                    phases = mapOf(
                        TimeoutPhase.GATE_CHECK_752 to PhaseTimeoutConfig(
                            softTimeoutSec = 60,
                            hardTimeoutSec = 300,
                            pollIntervalMs = 1000
                        ),
                        TimeoutPhase.GATE_CHECK_240 to PhaseTimeoutConfig(
                            softTimeoutSec = 30,
                            hardTimeoutSec = 60,
                            pollIntervalMs = 500
                        ),
                        TimeoutPhase.START_214 to PhaseTimeoutConfig(
                            softTimeoutSec = 5,
                            hardTimeoutSec = 10,
                            pollIntervalMs = 1000
                        ),
                        TimeoutPhase.MONITOR_102 to PhaseTimeoutConfig(
                            softTimeoutSec = 1200,    // 20分钟（Mode 4 最长）
                            hardTimeoutSec = 2400,    // 40分钟
                            pollIntervalMs = 2000
                        )
                    )
                )
                else -> getDefaultPolicy(1) // 默认使用 Model 1
            }
        }
    }
}

/**
 * 阶段超时配置
 */
data class PhaseTimeoutConfig(
    val softTimeoutSec: Int,      // 软超时（秒）：只提示/告警/延长
    val hardTimeoutSec: Int,      // 硬超时（秒）：进入 Refunding 或 ManualInterventionRequired
    val pollIntervalMs: Long      // 轮询间隔（毫秒）
)

/**
 * 超时处理结果
 */
data class TimeoutHandling(
    val phase: TimeoutPhase,
    val level: TimeoutLevel,
    val recommendedAction: String,
    val elapsedMs: Long
) {
    enum class TimeoutLevel {
        SOFT,   // 软超时：只提示/告警/延长
        HARD    // 硬超时：进入 Refunding 或 ManualInterventionRequired
    }
}
