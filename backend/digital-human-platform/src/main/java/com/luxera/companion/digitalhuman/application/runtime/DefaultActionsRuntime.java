package com.luxera.companion.digitalhuman.application.runtime;

import com.luxera.companion.digitalhuman.application.domain.ActionDescriptor;
import com.luxera.companion.digitalhuman.application.domain.PermissionLevel;
import com.luxera.companion.digitalhuman.application.domain.RiskLevel;
import com.luxera.companion.digitalhuman.application.permission.PermissionDecision;
import com.luxera.companion.digitalhuman.application.permission.PermissionEngine;
import com.luxera.companion.digitalhuman.application.audit.ActionLogRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V10 §14 DefaultActions Runtime: ActionRuntime 的实现。
 *
 * - 持有 actionId → ActionHandler 的注册表(Registry Pattern)
 * - 执行前经 {@link PermissionEngine} 做权限决策
 * - 执行后经 {@link ActionLogRecorder} 写审计日志
 *
 * 各应用通过 {@link #register(String, ActionHandler, ActionDescriptor)} 注册自己的动作。
 */
@Slf4j
@Service
public class DefaultActionsRuntime implements ActionRuntime {

    private final PermissionEngine permissionEngine;
    private final ActionLogRecorder auditLog;
    private final Map<String, ActionHandler> handlers = new ConcurrentHashMap<>();
    private final Map<String, ActionDescriptor> descriptors = new ConcurrentHashMap<>();

    public DefaultActionsRuntime(PermissionEngine permissionEngine, ActionLogRecorder auditLog) {
        this.permissionEngine = permissionEngine;
        this.auditLog = auditLog;
    }

    /** 应用侧注册动作 */
    public void register(String actionId, ActionHandler handler, ActionDescriptor descriptor) {
        handlers.put(actionId, handler);
        descriptors.put(actionId, descriptor);
        log.info("[ActionRuntime] 注册动作: {} (risk={}, perm={})",
                actionId, descriptor.riskLevel(), descriptor.permissionLevel());
    }

    @Override
    public ActionDescriptor describe(String actionId) {
        return descriptors.get(actionId);
    }

    @Override
    public List<ActionDescriptor> listActions() {
        return List.copyOf(descriptors.values());
    }

    @Override
    public ActionResult execute(String actionId, Map<String, Object> input,
                                String idempotencyKey, ActionContext ctx) {
        ActionDescriptor descriptor = descriptors.get(actionId);
        if (descriptor == null) {
            return ActionResult.fail("UNKNOWN_ACTION", "未知动作: " + actionId);
        }
        ActionHandler handler = handlers.get(actionId);
        if (handler == null) {
            return ActionResult.fail("NO_HANDLER", "动作未绑定处理器: " + actionId);
        }

        // 1. 权限决策(LLM 永远没有权限决定权, 由 Policy 说了算)
        PermissionDecision decision = permissionEngine.decide(
                new PermissionEngine.PermissionRequest(
                        actionId, descriptor, ctx.companionId(), ctx.userId(), idempotencyKey));
        if (decision.denied()) {
            auditLog.record(actionId, ctx, idempotencyKey, "DENIED", decision.reason());
            return ActionResult.deny(decision.code(), decision.reason());
        }
        if (decision.requiresConfirmation()) {
            auditLog.record(actionId, ctx, idempotencyKey, "REQUIRE_CONFIRMATION", decision.reason());
            return ActionResult.requireConfirmation(decision.code(), decision.reason());
        }

        // 2. 执行(各应用自己的逻辑; 异常不冒泡, 记日志)
        try {
            ActionResult outcome = handler.execute(actionId, input, ctx);
            auditLog.record(actionId, ctx, idempotencyKey,
                    outcome.status().name(), outcome.errorMessage());
            return outcome;
        } catch (Exception e) {
            log.warn("[ActionRuntime] 动作执行异常 {}: {}", actionId, e.getMessage());
            auditLog.record(actionId, ctx, idempotencyKey, "FAILED", e.getMessage());
            return ActionResult.fail("EXECUTION_ERROR", e.getMessage());
        }
    }

    /** 动作处理器(由各应用适配器实现) */
    @FunctionalInterface
    public interface ActionHandler {
        ActionResult execute(String actionId, Map<String, Object> input, ActionContext ctx);
    }
}