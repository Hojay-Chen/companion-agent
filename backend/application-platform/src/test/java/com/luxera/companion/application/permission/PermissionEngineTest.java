package com.luxera.companion.application.permission;

import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.AttentionPolicy;
import com.luxera.companion.contracts.application.PermissionLevel;
import com.luxera.companion.contracts.application.RiskLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V10 §14/LAP §15 权限引擎单元测试。
 *
 * 核心原则: LLM 永远没有权限决定权 —— 风险等级决定 ALLOW/CONFIRM/DENY。
 */
class PermissionEngineTest {

    private final PermissionEngine engine = new DefaultPermissionEngine();

    private PermissionEngine.PermissionRequest req(String actionId, RiskLevel risk) {
        return new PermissionEngine.PermissionRequest(
                actionId,
                new ActionSpec(actionId, "tictactoe", "game.play", "测试动作",
                        PermissionLevel.WRITE, risk, AttentionPolicy.AWARE, null, null),
                "companion-1", "user-1", "idem-1");
    }

    @Test
    void lowRiskIsAllowed() {
        PermissionDecision d = engine.decide(req("game.make_move", RiskLevel.LOW));
        assertTrue(d.allowed(), "LOW 风险应自动放行");
    }

    @Test
    void noneRiskIsAllowed() {
        PermissionDecision d = engine.decide(req("game.state", RiskLevel.NONE));
        assertTrue(d.allowed(), "NONE 风险(只读)应自动放行");
    }

    @Test
    void mediumRiskRequiresConfirmation() {
        PermissionDecision d = engine.decide(req("social.send_message", RiskLevel.MEDIUM));
        assertTrue(d.requiresConfirmation(), "MEDIUM 风险应需确认");
    }

    @Test
    void highRiskIsDenied() {
        PermissionDecision d = engine.decide(req("calendar.delete_event", RiskLevel.HIGH));
        assertTrue(d.denied(), "HIGH 风险应拒绝");
    }

    @Test
    void criticalRiskIsDenied() {
        PermissionDecision d = engine.decide(req("shop.purchase", RiskLevel.CRITICAL));
        assertTrue(d.denied(), "CRITICAL 风险(支付)应拒绝");
    }
}
