package com.luxera.companion.application.runtime;

import com.luxera.companion.application.audit.ActionLogRepository;
import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.AttentionPolicy;
import com.luxera.companion.contracts.application.PermissionLevel;
import com.luxera.companion.contracts.application.RiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V10 §14/LAP §16 ActionRuntime 集成测试:
 * - 动作注册 → 权限决策 → 执行 → 审计日志落库 全链路
 * - 调用方不直接依赖具体应用类, 只经 ActionRuntime 表达意图
 *
 * <p>R3 起描述符统一为 contracts 的 {@link ActionSpec} —— 这里原先用的
 * {@code digitalhuman.application.domain.ActionDescriptor} 已随搬迁删除。
 */
@ActiveProfiles("test")
@SpringBootTest
class ActionRuntimeIntegrationTest {

    @Autowired
    DefaultActionsRuntime actionRuntime;

    @Autowired
    ActionLogRepository auditLogRepository;

    @Test
    void executeRunsHandlerAndWritesAuditLog() {
        String actionId = "test.echo_" + System.nanoTime();
        actionRuntime.register(actionId,
                (id, input, ctx) -> ActionRuntime.ActionResult.success(
                        Map.of("echo", input.get("say"))),
                spec(actionId, PermissionLevel.READ, RiskLevel.NONE, "回声动作"));

        ActionRuntime.ActionContext ctx = ActionRuntime.ActionContext.of(
                "test-app", "companion-audit-1", "user-1", "sess-1", "corr-1");
        ActionRuntime.ActionResult result = actionRuntime.execute(
                actionId, Map.of("say", "hello"), "idem-" + System.nanoTime(), ctx);

        assertTrue(result.succeeded(), "NONE 风险动作应执行成功");
        assertEquals("hello", result.result().get("echo"));

        assertTrue(auditLogRepository.findByCompanionIdOrderByStartedAtDesc("companion-audit-1")
                .stream().anyMatch(r -> actionId.equals(r.getActionId())
                        && "SUCCEEDED".equals(r.getExecutionStatus())),
                "审计日志应记录这次成功执行");
    }

    @Test
    void highRiskActionIsDeniedAndAudited() {
        String actionId = "test.dangerous_" + System.nanoTime();
        actionRuntime.register(actionId,
                (id, input, ctx) -> ActionRuntime.ActionResult.success(Map.of()),
                spec(actionId, PermissionLevel.EXECUTE, RiskLevel.CRITICAL, "高危动作"));

        ActionRuntime.ActionContext ctx = ActionRuntime.ActionContext.of(
                "test-app", "companion-audit-2", "user-1", "sess-2", "corr-2");
        ActionRuntime.ActionResult result = actionRuntime.execute(
                actionId, Map.of(), "idem-" + System.nanoTime(), ctx);

        assertTrue(result.denied(), "CRITICAL 风险动作应被拒绝");
        assertEquals("RISK_TOO_HIGH", result.errorCode());

        assertTrue(auditLogRepository.findByCompanionIdOrderByStartedAtDesc("companion-audit-2")
                .stream().anyMatch(r -> actionId.equals(r.getActionId())
                        && "DENIED".equals(r.getExecutionStatus())),
                "审计日志应记录这次拒绝");
    }

    @Test
    void unknownActionFailsGracefully() {
        ActionRuntime.ActionContext ctx = ActionRuntime.ActionContext.of(
                "no-such-app", "companion-x", "user-x", null, null);
        ActionRuntime.ActionResult result = actionRuntime.execute(
                "game.no_such_action", Map.of(), "idem-x", ctx);
        assertFalse(result.succeeded());
        assertEquals("UNKNOWN_ACTION", result.errorCode());
    }

    @Test
    void listActionsIncludesRegistered() {
        String actionId = "test.listed_" + System.nanoTime();
        actionRuntime.register(actionId,
                (id, input, ctx) -> ActionRuntime.ActionResult.success(Map.of()),
                spec(actionId, PermissionLevel.READ, RiskLevel.LOW, "列表动作"));
        assertTrue(actionRuntime.listActions().stream()
                .anyMatch(d -> actionId.equals(d.actionId())));
        assertNotNull(actionRuntime.describe(actionId));
    }

    private static ActionSpec spec(String actionId, PermissionLevel perm, RiskLevel risk, String description) {
        return new ActionSpec(actionId, "test-app", "test.cap", description,
                perm, risk, AttentionPolicy.AWARE, null, null);
    }
}
