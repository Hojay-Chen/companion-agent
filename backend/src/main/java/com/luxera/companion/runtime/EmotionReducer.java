package com.luxera.companion.runtime;

import com.luxera.companion.state.AgentState;
import org.springframework.stereotype.Component;

/**
 * 情绪状态归约器(V5 §61/§99): EmotionDelta → AgentState。
 * 唯一允许修改情绪维度的入口。所有情绪变化必须经过这里, 可追踪。
 */
@Component
public class EmotionReducer implements StateReducer<EmotionDelta, AgentState> {

    @Override
    public AgentState apply(AgentState state, EmotionDelta delta) {
        if (state == null || delta == null || delta.isEmpty()) return state;
        state.setAnger(clamp(state.getAnger() + delta.anger()));
        state.setHurt(clamp(state.getHurt() + delta.hurt()));
        state.setSadness(clamp(state.getSadness() + delta.sadness()));
        state.setAnxiety(clamp(state.getAnxiety() + delta.anxiety()));
        state.setWarmth(clamp(state.getWarmth() + delta.warmth()));

        // 压力联动: 高负面情绪 → 压力上升; 温暖 → 压力略降
        double negative = Math.max(0, delta.anger() * 0.3 + delta.hurt() * 0.25 + delta.sadness() * 0.2 + delta.anxiety() * 0.3);
        double comfort = Math.max(0, delta.warmth());
        state.setStress(clamp(state.getStress() + negative - comfort * 0.15));

        // 情绪主导 mood
        state.setMood(dominantMood(state));
        return state;
    }

    private static String dominantMood(AgentState s) {
        double hurt = s.getHurt(), anger = s.getAnger(), sad = s.getSadness(),
                anxiety = s.getAnxiety(), warmth = s.getWarmth();
        if (anger > 0.35 && anger >= hurt && anger >= sad) return "有点生气";
        if (hurt > 0.35 && hurt >= sad && hurt >= anxiety) return "有点受伤";
        if (sad > 0.35 && sad >= anxiety) return "有点难过";
        if (anxiety > 0.35) return "有点不安";
        if (warmth > 0.4 && hurt < 0.2 && anger < 0.2) return "平静的";
        if (hurt < 0.15 && anger < 0.15 && sad < 0.15 && anxiety < 0.15) return "平静的";
        return s.getMood() != null ? s.getMood() : "平静的";
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
