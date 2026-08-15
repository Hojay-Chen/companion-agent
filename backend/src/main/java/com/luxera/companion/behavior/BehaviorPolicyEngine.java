package com.luxera.companion.behavior;

import com.luxera.companion.agent.CompanionContext;
import org.springframework.stereotype.Component;

/**
 * 行为策略引擎(设计文档 V2.0 §13): Runtime 决定"现在应该做什么"。
 * 规则驱动(Level 1, 不调用 LLM); 输出 BehaviorDecision 供 Prompt 与主动行为使用。
 */
@Component
public class BehaviorPolicyEngine {

    public BehaviorDecision decide(CompanionContext ctx) {
        String intent = ctx.perception != null ? ctx.perception.intent() : null;
        String emotion = ctx.perception != null ? ctx.perception.emotion() : null;
        double stress = ctx.state != null ? ctx.state.getStress() : 0.3;
        double energy = ctx.state != null ? ctx.state.getEnergy() : 0.6;

        String action = "RESPOND";
        String posture = "neutral";
        boolean ask = false, advice = false, shareSelf = false, tease = false, disagree = false, endTopic = false;
        double initiative = 0.5;
        String reason = "根据意图与情绪回应";

        if (emotion != null) {
            switch (emotion) {
                case "sad", "tired", "anxious", "lonely" -> {
                    action = "COMFORT"; posture = "caring"; ask = true; advice = false;
                    reason = "先陪伴、倾听,不急着给建议";
                }
                case "happy", "grateful" -> { action = "SHARE"; posture = "warm"; tease = true; initiative = 0.6; reason = "一起开心"; }
                default -> { }
            }
        }
        if (intent != null) {
            switch (intent) {
                case "greeting" -> { action = "RESPOND"; posture = "warm"; initiative = 0.55; reason = "问候回应"; }
                case "question" -> { action = "RESPOND"; ask = true; reason = "回答并追问一句"; }
                case "share_joy" -> { action = "SHARE"; posture = "warm"; tease = true; reason = "分享喜悦"; }
                case "share_upset", "share_tired" -> { action = "COMFORT"; posture = "caring"; ask = true; advice = false; }
                case "request_tool" -> { action = "RESPOND"; reason = "确认已办的事"; }
                case "correction" -> { action = "RESPOND"; disagree = false; reason = "大方接受纠正"; }
                case "planning" -> { action = "ASK"; ask = true; initiative = 0.6; reason = "一起计划"; }
                case "farewell", "say_goodnight" -> { action = "END_CONVERSATION"; endTopic = true; reason = "自然收尾"; }
                default -> { }
            }
        }
        // 压力/精力影响姿态与主动(设计文档 §20 关系摩擦): 低能量 → 少说少主动
        if (stress > 0.6 && energy < 0.35) {
            posture = "reserved";
            initiative = Math.max(0.2, initiative - 0.2);
            reason = "最近压力有点大,有点没精神";
        }
        return new BehaviorDecision(action, posture, initiative, ask, advice, shareSelf, tease,
                disagree, endTopic, null, reason, 0.7);
    }
}
