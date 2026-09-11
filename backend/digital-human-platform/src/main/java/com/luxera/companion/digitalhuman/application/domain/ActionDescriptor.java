package com.luxera.companion.digitalhuman.application.domain;

import java.util.Map;

/**
 * V10 §14/LAP §14 动作描述符。
 *
 * 声明式描述一个应用动作: 标识 / 输入 schema 约束(最小校验) / 风险 / 权限 / 注意力策略。
 * 这是"LLM 读描述符而非读 Java Class"的最小落点 —— 第一版用 Java 常量而非完整 JSON Schema,
 * 避免为"未来生态"提前引入 JSON Schema 解析器; 需要第三方接入时再升级为声明式 manifest。
 *
 * @param actionId        全局唯一动作 id, 如 "game.make_move"
 * @param appCode         所属应用 code, 如 "tictactoe"
 * @param description     一句话说明(供 LLM/日志可读)
 * @param permissionLevel 所需权限等级
 * @param riskLevel       风险等级(决定是否需确认)
 * @param attention       事件注意力策略(复用 PerceptionLevel, 见 {@link AttentionPolicy})
 */
public record ActionDescriptor(
        String actionId,
        String appCode,
        String description,
        PermissionLevel permissionLevel,
        RiskLevel riskLevel,
        AttentionPolicy attention
) {

    public static ActionDescriptor of(String actionId, String appCode, String description,
                                      PermissionLevel perm, RiskLevel risk) {
        return new ActionDescriptor(actionId, appCode, description, perm, risk, AttentionPolicy.AWARE);
    }

    public static ActionDescriptor of(String actionId, String appCode, String description,
                                      PermissionLevel perm, RiskLevel risk, AttentionPolicy attention) {
        return new ActionDescriptor(actionId, appCode, description, perm, risk, attention);
    }

    /** 注意力策略(V10 §10/LAP §10): 决定该动作触发的事件以什么感知等级进入认知链 */
    public enum AttentionPolicy {
        /** 完全不进认知(高频噪音, 如播放进度) */
        NONE,
        /** 潜意识感知 */
        SUBCONSCIOUS,
        /** 意识到(默认) */
        AWARE,
        /** 高度关注(比赛结束/失败/对方落子) */
        FOCUSED;

        public com.luxera.companion.digitalhuman.perception.PerceptionLevel toPerceptionLevel() {
            return switch (this) {
                case NONE -> com.luxera.companion.digitalhuman.perception.PerceptionLevel.NONE;
                case SUBCONSCIOUS -> com.luxera.companion.digitalhuman.perception.PerceptionLevel.SUBCONSCIOUS;
                case AWARE -> com.luxera.companion.digitalhuman.perception.PerceptionLevel.AWARE;
                case FOCUSED -> com.luxera.companion.digitalhuman.perception.PerceptionLevel.FOCUSED;
            };
        }
    }

    /** 组装一个携带注意力策略的动作描述符的便捷工具(map 形式, 供日志) */
    public Map<String, Object> asMap() {
        return Map.of(
                "actionId", actionId,
                "appCode", appCode,
                "description", description == null ? "" : description,
                "permission", permissionLevel.name(),
                "risk", riskLevel.name(),
                "attention", attention.name());
    }
}