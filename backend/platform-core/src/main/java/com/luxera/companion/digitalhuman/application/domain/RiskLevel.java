package com.luxera.companion.digitalhuman.application.domain;

/**
 * V10 §14/LAP §14 应用动作风险分级。
 *
 * 风险决定权限引擎的决策路径:
 * - NONE:     纯只读, 永不需确认
 * - LOW:      低风险, 自动放行
 * - MEDIUM:   中风险, 需要确认(仅在权限收紧时)
 * - HIGH:     高风险, 必须用户/系统确认
 * - CRITICAL: 极危险(金额/删除/对外发送), 默认拒绝, 除非显式授权
 */
public enum RiskLevel {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}