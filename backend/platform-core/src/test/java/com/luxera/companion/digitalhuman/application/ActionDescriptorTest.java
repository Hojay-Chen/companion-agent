package com.luxera.companion.digitalhuman.application;

import com.luxera.companion.digitalhuman.application.domain.ActionDescriptor;
import com.luxera.companion.digitalhuman.application.domain.PermissionLevel;
import com.luxera.companion.digitalhuman.application.domain.RiskLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V10 §14/LAP §3 ActionDescriptor 单元测试: 描述符携带风险/权限/注意力策略。
 */
class ActionDescriptorTest {

    @Test
    void descriptorCarriesRiskAndPermission() {
        ActionDescriptor d = ActionDescriptor.of("game.make_move", "tictactoe", "落子",
                PermissionLevel.WRITE, RiskLevel.LOW);
        assertEquals("game.make_move", d.actionId());
        assertEquals("tictactoe", d.appCode());
        assertEquals(PermissionLevel.WRITE, d.permissionLevel());
        assertEquals(RiskLevel.LOW, d.riskLevel());
    }

    @Test
    void defaultAttentionIsAware() {
        ActionDescriptor d = ActionDescriptor.of("game.state", "tictactoe", "读局面",
                PermissionLevel.READ, RiskLevel.NONE);
        assertEquals(ActionDescriptor.AttentionPolicy.AWARE, d.attention());
    }

    @Test
    void attentionPolicyMapsToPerceptionLevel() {
        assertEquals("NONE", ActionDescriptor.AttentionPolicy.NONE.toPerceptionLevel().name());
        assertEquals("FOCUSED", ActionDescriptor.AttentionPolicy.FOCUSED.toPerceptionLevel().name());
        assertEquals("AWARE", ActionDescriptor.AttentionPolicy.AWARE.toPerceptionLevel().name());
    }

    @Test
    void asMapIsReadable() {
        ActionDescriptor d = ActionDescriptor.of("game.make_move", "tictactoe", "落子",
                PermissionLevel.WRITE, RiskLevel.LOW);
        assertEquals("game.make_move", d.asMap().get("actionId"));
        assertEquals("LOW", d.asMap().get("risk"));
        assertEquals("WRITE", d.asMap().get("permission"));
    }
}