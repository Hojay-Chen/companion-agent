package com.luxera.companion.runtime.agent.brain;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * §44 Decision Validator: 每次 Brain Decision 的一致性校验。
 * 检查决策是否符合当前活动/情绪/性格/关系, 是否与刚刚发生的事情矛盾。
 *
 * 优先轻量 Schema Validation + State Constraint(不额外调 LLM):
 * 用规则校验显而易见的矛盾, 只有复杂冲突才考虑 LLM(当前阶段全规则)。
 */
@Component
public class DecisionValidator {

    /** 校验结果 */
    public record ValidationResult(boolean valid, String reason, String correctedAction) {
        public static ValidationResult ok() {
            return new ValidationResult(true, "决策一致", null);
        }

        public static ValidationResult invalid(String reason, String corrected) {
            return new ValidationResult(false, reason, corrected);
        }
    }

    /**
     * 校验 Brain 决策与当前世界状态的一致性。
     *
     * @param decision       Brain 决策(action)
     * @param ctx            Brain 上下文(活动/情绪/关系/手机/注意力)
     * @return 校验结果: 若 invalid, correctedAction 给出一条可接受的替代决策
     */
    public ValidationResult validate(String decision, BrainContext ctx) {
        if (decision == null || ctx == null) return ValidationResult.ok();

        List<String> violations = new ArrayList<>();
        String corrected = null;

        // 约束 1: 睡眠时不应回复(除非消息紧急到必须 —— 用 notice 兜底)
        if (isSleeping(ctx) && (BrainDecision.REPLY.equals(decision) || BrainDecision.SHORT_ACK.equals(decision))) {
            violations.add("正在睡觉, 不应立即回复");
            corrected = BrainDecision.READ_NO_REPLY;
        }

        // 约束 2: 忙且不可打断(会议/工作)时, 不应 REPLY —— 应 DEFER 或 READ_NO_REPLY
        if (isBusyAndUnavailable(ctx)
                && (BrainDecision.REPLY.equals(decision) || BrainDecision.END_CONVERSATION.equals(decision))) {
            violations.add("正在开会/忙, 不应立即回复");
            corrected = corrected == null ? BrainDecision.READ_NO_REPLY : corrected;
        }

        // 约束 3: 手机不在身边/静音/勿扰时, 不可能 CHECK_PHONE_FIRST(没手机可查)
        if (BrainDecision.CHECK_PHONE_FIRST.equals(decision) && !ctx.phoneNearby()) {
            violations.add("手机不在身边, 无法先查看手机");
            corrected = corrected == null ? BrainDecision.READ_NO_REPLY : corrected;
        }

        // 约束 4: 消息完全没被注意到(noticeProbability≈0)时, 不应 REPLY(她根本没看到)
        if (ctx.noticeProbability() < 0.1
                && (BrainDecision.REPLY.equals(decision) || BrainDecision.SHORT_ACK.equals(decision)
                    || BrainDecision.CHECK_PHONE_FIRST.equals(decision))) {
            violations.add("根本没注意到消息, 不应回复");
            corrected = corrected == null ? BrainDecision.IGNORE : corrected;
        }

        // 约束 5: 情绪极度负面 + 刚发生冲突 → 不应 END_CONVERSATION(会加剧关系伤害)
        if (BrainDecision.END_CONVERSATION.equals(decision)
                && (ctx.hurt() + ctx.anger()) > 0.8) {
            violations.add("正情绪激动, 不应在冲突中直接结束对话");
            corrected = corrected == null ? BrainDecision.READ_NO_REPLY : corrected;
        }

        if (violations.isEmpty()) return ValidationResult.ok();
        return ValidationResult.invalid(String.join("; ", violations), corrected);
    }

    /** 睡觉: activityDesc 包含"休息"且 availability=SLEEP 是强信号 */
    private static boolean isSleeping(BrainContext ctx) {
        if (ctx.activityDesc() == null) return false;
        String desc = ctx.activityDesc().toLowerCase();
        return desc.contains("睡觉") || desc.contains("休息")
                || (ctx.availability() != null && ctx.availability().equalsIgnoreCase("SLEEP"));
    }

    /** 忙且不可打断: 活动描述含"开会/忙工作" */
    private static boolean isBusyAndUnavailable(BrainContext ctx) {
        if (ctx.activityDesc() == null) return false;
        String desc = ctx.activityDesc().toLowerCase();
        return desc.contains("开会") || desc.contains("忙") || desc.contains("工作");
    }
}
