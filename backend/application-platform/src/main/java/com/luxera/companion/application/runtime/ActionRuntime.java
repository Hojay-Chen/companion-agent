package com.luxera.companion.application.runtime;

import com.luxera.companion.contracts.application.ActionSpec;

import java.util.List;
import java.util.Map;

/**
 * V10 §14/LAP §16-16 Action Runtime: 应用动作在平台内的执行入口。
 *
 * 关键设计:
 * - 调用方(认知链 / REST / MCP)只通过本 Runtime 表达"我要做什么"(actionId + 参数),
 *   不直接依赖任何具体应用的 Java 类 —— 消除 `AgentRuntime → TicTacToeGameService` 硬耦合。
 * - 本 Runtime 负责: 解析动作 → 权限校验 → 派发给对应适配器 → 记录审计日志。
 *
 * <p><b>R3 起动作描述符统一为 {@link ActionSpec}</b>(contracts), 原先 DH 内部的
 * `ActionDescriptor` / `PermissionLevel` / `RiskLevel` 三份副本已删除 —— 描述符是
 * "平台的调用方也要读"的东西, 所以它属于 contracts。
 *
 * <p>{@link ActionResult} 里原先还有 `realityPayload` / `emittedEventType` 两个字段,
 * 已删除: 那是应用隔着模块边界规定 {@code digitalhuman.reality.RealityEventType} 的语义,
 * 方向反了。**账本词汇表只由数字人决定**(见 {@code AgentApplicationFlow.appendReality})。
 *
 * <p><b>R4 起本接口被 {@code ActionGateway} 取代</b>(真实幂等 + Resource CAS + 归属校验)。
 * R3 保留它, 是为了让 {@code /api/v10} 与搬迁期的行为逐字不变。
 */
public interface ActionRuntime {

    /**
     * 执行一个应用动作。
     *
     * @param actionId       全局唯一动作 id, 如 "game.make_move"
     * @param input          动作入参(语义由各适配器定义)
     * @param idempotencyKey 幂等键(同键重试不重复执行; READ 动作为 null)
     * @param ctx            执行上下文(appCode/companionId/userId/sessionId/correlationId)
     */
    ActionResult execute(String actionId, Map<String, Object> input,
                         String idempotencyKey, ActionContext ctx);

    /** 查询指定动作的描述符(供认知链/诊断了解"能做什么") */
    ActionSpec describe(String actionId);

    /** 列出所有已注册动作(供 LLM 上下文或诊断端点) */
    List<ActionSpec> listActions();

    /** 执行上下文: 谁是行动者, 哪个资源, 因果链 */
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
            String errorMessage
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

        public static ActionResult success(Map<String, Object> result) {
            return new ActionResult(Status.SUCCEEDED, result, null, null);
        }

        public static ActionResult deny(String errorCode, String message) {
            return new ActionResult(Status.DENIED, Map.of(), errorCode, message);
        }

        public static ActionResult requireConfirmation(String errorCode, String message) {
            return new ActionResult(Status.REQUIRE_CONFIRMATION, Map.of(), errorCode, message);
        }

        public static ActionResult fail(String errorCode, String message) {
            return new ActionResult(Status.FAILED, Map.of(), errorCode, message);
        }
    }
}
