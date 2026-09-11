package com.luxera.companion.application.permission;

import com.luxera.companion.contracts.application.ActionSpec;

/**
 * V10 §14/LAP §15 Permission Engine: 应用动作的权限决策。
 *
 * 核心原则: **LLM 永远没有权限决定权。** 认知链只提出"行动意图", 最终能否执行由本引擎决定。
 *
 * 现行策略(第一版, 清晰可预测):
 * - NONE/LOW 风险 → 自动放行(只读/低危游戏动作)
 * - MEDIUM       → 需要确认(中危, 如对外发消息)
 * - HIGH/CRITICAL → 拒绝(高危, 如支付/删数据), 除非未来接入显式授权
 *
 * <p><b>R4 起本引擎还要看 Installation 授权</b>(Principal × Installation grant × Capability ×
 * Action × Risk) —— 今天只有风险这一个轴, 所以"未安装也能调"这件事现在还没被拦住。
 */
public interface PermissionEngine {

    PermissionDecision decide(PermissionRequest request);

    /** 权限决策请求 */
    record PermissionRequest(
            String actionId,
            ActionSpec descriptor,
            String companionId,
            String userId,
            String idempotencyKey
    ) {}
}
