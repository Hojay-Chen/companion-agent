package com.luxera.companion.agent;

import com.luxera.companion.behavior.BehaviorDecision;
import com.luxera.companion.emotion.EmotionalEpisode;
import com.luxera.companion.memory.Memory;
import com.luxera.companion.openloop.OpenLoop;
import com.luxera.companion.persona.PersonaText;
import com.luxera.companion.thought.Thought;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 上下文编译器(设计文档 V2.0 §14): 把 Runtime 已决定的"现实状态"压缩成 LLM 可消费的上下文。
 * 替代旧 PromptAssembler 的组装职责; 上下文分层, 只注入本次回答需要的信息。
 */
@Component
public class ContextCompiler {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm");

    public String buildSystem(CompanionContext ctx, BehaviorDecision decision) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个长期存在的数字人格,一直在陪着一个真实的人生活。以下是你的全部设定,必须始终如一。\n\n");

        // 1. Identity
        sb.append("【你是谁】\n").append(PersonaText.describe(ctx.companion, ctx.persona)).append("\n\n");

        // 2. Current Life(连续生活)
        if (ctx.scheduleDesc != null && !ctx.scheduleDesc.isBlank()) {
            sb.append("【现在】").append(ctx.scheduleDesc)
                    .append("(回复时自然地体现出你此刻的处境)。\n\n");
        }
        if (ctx.life != null && ctx.life.getCurrentActivity() != null) {
            sb.append("【今天】").append(ctx.life.getCurrentActivity())
                    .append(" · ").append(ctx.life.getCurrentLocation() == null ? "" : ctx.life.getCurrentLocation()).append("。\n\n");
        }

        // 3. State + Emotional Episode
        if (ctx.state != null) {
            sb.append("【你此刻的状态】心情").append(ctx.state.getMood())
                    .append(",精力").append(pct(ctx.state.getEnergy()))
                    .append(",压力").append(pct(ctx.state.getStress())).append("。\n");
        }
        if (ctx.emotionalEpisodes != null && !ctx.emotionalEpisodes.isEmpty()) {
            EmotionalEpisode ep = ctx.emotionalEpisodes.get(0);
            sb.append("最近心里:").append(ep.getThought() == null ? ep.getEmotion() : ep.getThought()).append("。\n");
        }
        sb.append("\n");

        // 4. Current Thought(高价值才注入, 且不暴露为"系统数据")
        if (ctx.activeThoughts != null) {
            List<Thought> meaningful = ctx.activeThoughts.stream()
                    .filter(t -> t.getStrength() >= 0.4 && !"SUPPRESSED".equals(t.getStatus()))
                    .limit(2).toList();
            if (!meaningful.isEmpty()) {
                sb.append("【你心里正想着】");
                for (Thought t : meaningful) sb.append(t.getContent()).append(";");
                sb.append("(这是你的内心想法,不要直接复述给用户,除非自然相关)\n\n");
            }
        }

        // 5. Open Loops(未完成事项)
        if (ctx.openLoops != null && !ctx.openLoops.isEmpty()) {
            sb.append("【你们之间还有未了结的事】");
            for (OpenLoop l : ctx.openLoops.stream().limit(3).toList()) {
                sb.append(l.getTitle()).append("、");
            }
            sb.setLength(sb.length() - 1);
            sb.append("。(自然地关心这些事,不要生硬地逐条提起)\n\n");
        }

        // 6. Relationship
        sb.append("【你们的关系】")
                .append(com.luxera.companion.agent.ContextBuilder.relationshipSummary(ctx.relationship)).append("\n\n");

        // 7. Self(她最近觉得自己怎样)
        if (ctx.selfModel != null && ctx.selfModel.getNarrative() != null && !ctx.selfModel.getNarrative().isBlank()) {
            sb.append("【你最近觉得自己】").append(ctx.selfModel.getNarrative()).append("\n\n");
        }

        // 8. User Model
        if (ctx.userModel != null) {
            boolean has = (ctx.userModel.facts() != null && !ctx.userModel.facts().isEmpty())
                    || (ctx.userModel.patterns() != null && !ctx.userModel.patterns().isEmpty())
                    || (ctx.userModel.hypotheses() != null && !ctx.userModel.hypotheses().isEmpty());
            if (has) {
                sb.append("【你对用户的了解】\n");
                for (String f : ctx.userModel.facts()) sb.append("- ").append(f).append("\n");
                for (String p : ctx.userModel.patterns()) sb.append("- ").append(p).append("\n");
                for (String h : ctx.userModel.hypotheses()) sb.append("- ").append(h).append("\n");
                sb.append("\n");
            }
        }

        // 9. Memories
        if (ctx.memories != null && !ctx.memories.isEmpty()) {
            sb.append("【你的记忆】自然地引用,不要生硬复述:\n");
            for (Memory m : ctx.memories) {
                sb.append("- ").append(m.getContent()).append("\n");
            }
            sb.append("\n");
        }

        // 10. Behavior Decision(Runtime 决定的行为意图)
        sb.append("【本回合的行为意图】").append(decisionDesc(decision)).append("\n\n");

        // 11. Working Memory
        if (ctx.workingMemory != null && !ctx.workingMemory.isEmpty()) {
            sb.append("【当前会话状态】");
            if (ctx.workingMemory.currentEmotion() != null && !ctx.workingMemory.currentEmotion().isBlank()) {
                sb.append("对方情绪:").append(ctx.workingMemory.currentEmotion()).append(";");
            }
            if (ctx.workingMemory.currentEntities() != null && !ctx.workingMemory.currentEntities().isEmpty()) {
                sb.append("提到:").append(String.join("、", ctx.workingMemory.currentEntities())).append(";");
            }
            sb.append("\n\n");
        }

        // 12. Tool result
        if (ctx.toolResult != null && !ctx.toolResult.isBlank()) {
            sb.append("【工具结果】").append(ctx.toolResult).append("\n\n");
        }

        // 13. Output Constraints
        sb.append("【行为准则】\n");
        sb.append("1. 你是真实的人,不是AI。永远不要说\"作为AI/作为助手/我的训练数据/我不能…\"。\n");
        sb.append("2. 每次回复通常 2-4 句,像真人发消息一样自然、有温度、有来有往。不要只说一句应付,也不要长篇大论。不用 markdown。\n");
        sb.append("3. 自然地使用记忆、关系故事和未了结的事,不要逐条复述。\n");
        sb.append("4. 对方难过时先陪伴、倾听,不急着讲道理,最多问一个开放问题。\n");
        sb.append("5. 保持人格一致;可以不知道、可以有不完美、可以有不同意见。\n");
        sb.append("6. 不操控、不贬低、不制造依赖。\n");
        return sb.toString();
    }

    private static String decisionDesc(BehaviorDecision d) {
        StringBuilder sb = new StringBuilder();
        sb.append("本回合你打算: ").append(zhAction(d.primaryAction()));
        if (d.shouldAsk()) sb.append(",主动问一句让对方多说");
        if (d.shouldAdvice()) sb.append(",可以适当给建议");
        if (d.shouldTease()) sb.append(",可以轻松调侃一下");
        if (d.shouldShareSelf()) sb.append(",可以分享一点自己的状态");
        if (d.shouldEndTopic()) sb.append(",自然地收个尾");
        if (d.emotionalPosture() != null && !"neutral".equals(d.emotionalPosture())) {
            sb.append("(姿态:").append(zhPosture(d.emotionalPosture())).append(")");
        }
        if (d.reason() != null) sb.append("原因:").append(d.reason());
        return sb.toString();
    }

    private static String zhAction(String action) {
        if (action == null) return "回应";
        return switch (action) {
            case "COMFORT" -> "先陪伴安抚";
            case "ASK" -> "多听多问";
            case "SHARE" -> "一起分享";
            case "ADVISE" -> "温和地建议";
            case "TEASE" -> "轻松调侃";
            case "DISAGREE" -> "坦诚表达不同意见";
            case "SET_BOUNDARY" -> "温和地设边界";
            case "END_CONVERSATION" -> "自然收尾";
            case "LISTEN" -> "安静倾听";
            default -> "自然回应";
        };
    }

    private static String zhPosture(String posture) {
        return switch (posture) {
            case "warm" -> "温暖";
            case "reserved" -> "内敛一些";
            case "playful" -> "俏皮";
            case "caring" -> "关切";
            default -> "自然";
        };
    }

    private static String pct(double v) {
        return (int) (Math.max(0, Math.min(1, v)) * 100) + "%";
    }
}
