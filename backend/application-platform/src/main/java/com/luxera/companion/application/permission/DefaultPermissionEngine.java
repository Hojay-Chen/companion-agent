package com.luxera.companion.application.permission;

import com.luxera.companion.contracts.application.RiskLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * V10 §14/LAP §15 默认权限引擎实现。
 *
 * 风险 → 决策映射(清晰可预测):
 *   NONE / LOW   → ALLOW
 *   MEDIUM       → REQUIRE_CONFIRMATION
 *   HIGH / CRITICAL → DENY
 *
 * <p>switch 不写 default: {@link RiskLevel} 新增档位时这里必须编译失败, 而不是静默放行。
 */
@Slf4j
@Component
public class DefaultPermissionEngine implements PermissionEngine {

    @Override
    public PermissionDecision decide(PermissionRequest request) {
        RiskLevel risk = request.descriptor().riskLevel();
        return switch (risk) {
            case NONE, LOW -> PermissionDecision.allow();
            case MEDIUM -> PermissionDecision.requireConfirmation(
                    "CONFIRM_REQUIRED",
                    "动作 " + request.actionId() + " 为中风险, 需确认后执行");
            case HIGH, CRITICAL -> PermissionDecision.deny(
                    "RISK_TOO_HIGH",
                    "动作 " + request.actionId() + " 为 " + risk + " 风险, 默认拒绝");
        };
    }
}
