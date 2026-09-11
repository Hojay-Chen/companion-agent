package com.luxera.companion.digitalhuman.application.permission;

/**
 * V10 §14/LAP §15 权限决策结果。
 */
public record PermissionDecision(
        Decision decision,
        String code,
        String reason
) {
    public enum Decision {
        /** 允许执行 */
        ALLOW,
        /** 拒绝(风险过高/无授权) */
        DENY,
        /** 需要确认(中高风险, 需用户/系统确认后执行) */
        REQUIRE_CONFIRMATION
    }

    public boolean denied() {
        return decision == Decision.DENY;
    }

    public boolean requiresConfirmation() {
        return decision == Decision.REQUIRE_CONFIRMATION;
    }

    public boolean allowed() {
        return decision == Decision.ALLOW;
    }

    public static PermissionDecision allow() {
        return new PermissionDecision(Decision.ALLOW, null, null);
    }

    public static PermissionDecision deny(String code, String reason) {
        return new PermissionDecision(Decision.DENY, code, reason);
    }

    public static PermissionDecision requireConfirmation(String code, String reason) {
        return new PermissionDecision(Decision.REQUIRE_CONFIRMATION, code, reason);
    }
}