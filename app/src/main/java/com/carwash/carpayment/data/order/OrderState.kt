package com.carwash.carpayment.data.order

/**
 * 订单状态枚举（V3.4 规范）
 * 
 * 订单状态独立于 WashFlowState，反映订单在交易维度的生命周期。
 * 订单终态（COMPLETED / REFUNDED / MANUAL）一旦确定，不可变更。
 */
enum class OrderState {
    /**
     * 订单创建，待支付
     * 触发时机：用户点击开始，生成订单
     */
    ORDER_PAYMENT_INIT,
    
    /**
     * 已支付
     * 触发时机：支付成功（PaymentAuthorized）
     */
    ORDER_PAID,
    
    /**
     * MODE已发送，等待PLC运行确认
     * 触发时机：发送 MODE 成功后立即设置
     * 
     * V3.4 规范：订单在发送 MODE 后即进入待确认状态，等待 PLC 运行确认。
     * 若在 1500ms（可配置）内检测到 214=1 或 102≠0，则订单自动变为 ORDER_COMPLETED。
     * 若超时未检测到运行态，订单转为 ORDER_MANUAL。
     */
    ORDER_PENDING_CONFIRMATION,
    
    /**
     * 服务完成（确认PLC运行后）
     * 触发时机：检测到 PLC 进入运行态（214=1 或 102≠0）后设置
     * 
     * V3.4 规范：ORDER_COMPLETED 仅表示"服务已启动确认"，不代表服务结束。
     * 洗车结束属于设备侧运行过程，不作为订单终态判断。
     * 订单一旦进入 ORDER_COMPLETED，不再允许任何操作。
     */
    ORDER_COMPLETED,
    
    /**
     * 退款中
     * 触发时机：进入 Refunding 状态
     */
    ORDER_REFUNDING,
    
    /**
     * 已退款
     * 触发时机：Refunded 状态到达
     */
    ORDER_REFUNDED,
    
    /**
     * 需人工处理
     * 触发时机：ManualInterventionRequired 状态到达
     * 
     * V3.4 规范：需人工介入（运营跟进）。
     * 订单一旦进入 ORDER_MANUAL，不再允许任何操作。
     */
    ORDER_MANUAL
}

/**
 * 检查订单是否处于终态
 * 
 * V3.4 规范：订单终态包括 ORDER_COMPLETED、ORDER_REFUNDED、ORDER_MANUAL。
 * 一旦进入终态，不再允许任何操作。
 */
fun OrderState.isFinalState(): Boolean {
    return when (this) {
        OrderState.ORDER_COMPLETED,
        OrderState.ORDER_REFUNDED,
        OrderState.ORDER_MANUAL -> true
        else -> false
    }
}
