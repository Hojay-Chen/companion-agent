package com.luxera.companion.digitalhuman.application.runtime;

import com.luxera.companion.digitalhuman.application.domain.ActionDescriptor;
import com.luxera.companion.digitalhuman.application.domain.RiskLevel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V10 §14/LAP §16-16 Action Runtime: 数字人执行"应用动作"的统一入口。
 *
 * 关键设计:
 * - Agent(认知链)只通过本 Runtime 表达"我要做什么"(actionId + 参数),
 *   不直接依赖任何具体应用的 Java 类 —— 消除 `AgentRuntime → TicTacToeGameService` 硬耦合。
 * - 本 Runtime 负责: 解析动作 → 权限校验 → 派发给对应适配器 → 记录审计日志。
 *
 * 这是 V10「Chat Platform ≠ Digital Human」之后第二块解耦拼图:
 * 「Application ≠ Digital Human」。
 */
public interface ActionRuntime {

    /**
     * 执行一个应用动作。
     *
     * @param actionId       全局唯一动作 id, 如 "game.make_move"
     * @param input          动作入参(语义由各适配器定义)
     * @param idempotencyKey 幂等键(同键重试不重复执行)
     * @param ctx            执行上下文(companionId/userId/sessionId/correlationId)
     * @return 结构化执行结果(含状态/结果/事件/现实写入建议)
     */
    ActionResult execute(String actionId, Map<String, Object> input,
                         String idempotencyKey, ActionContext ctx);

    /** 查询指定动作的描述符(供认知链/诊断了解"能做什么") */
    ActionDescriptor describe(String actionId);

    /** 列出所有已注册动作(供 LLM 上下文或诊断端点) */
    java.util.List<ActionDescriptor> listActions();

    /** 执行上下文: 谁是行动者, 哪个会话, 因果链 */
    record ActionContext(
            String appCode,
            String companionId,
            String userId,
            String sessionId,
            String correlationId
    ) {
        public static ActionContext of(String appCode, String companionId, String userId,
                                       String sessionId, String correlationId) {
            return new ActionContext(appCode, companionId, userId, sessionId, correlationId);
        }
    }

    /** 执行结果 */
    record ActionResult(
            Status status,
            Map<String, Object> result,
            String errorCode,
            String errorMessage,
            /** 该动作应写入 Reality Ledger 的 payload(非空时由调用方/本层写账) */
            Map<String, Object> realityPayload,
            /** 该动作触发的应用事件类型(供事件发布) */
            String emittedEventType
    ) {
        public enum Status {
            SUCCEEDED,
            ACCEPTED,
            RUNNING,
            FAILED,
            DENIED,
            REQUIRE_CONFIRMATION,
            CANCELLED
        }

        public boolean succeeded() {
            return status == Status.SUCCEEDED;
        }

        public boolean requiresConfirmation() {
            return status == Status.REQUIRE_CONFIRMATION;
        }

        public boolean denied() {
            return status == Status.DENIED;
        }

        public static ActionResult success(Map<String, Object> result, Map<String, Object> reality,
                                           String eventType) {
            return new ActionResult(Status.SUCCEEDED, result, null, null, reality, eventType);
        }

        public static ActionResult success(Map<String, Object> result) {
            return success(result, null, null);
        }

        public static ActionResult deny(String errorCode, String message) {
            return new ActionResult(Status.DENIED, Map.of(), errorCode, message, null, null);
        }

        public static ActionResult requireConfirmation(String errorCode, String message) {
            return new ActionResult(Status.REQUIRE_CONFIRMATION, Map.of(), errorCode, message, null, null);
        }

        public static ActionResult fail(String errorCode, String message) {
            return new ActionResult(Status.FAILED, Map.of(), errorCode, message, null, null);
        }
    }
}