package com.luxera.companion.runtime;

import com.luxera.companion.state.AgentState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 情绪归约器单元测试(V5 §99): 所有情绪状态变更走 Reducer, 可追踪。 */
class EmotionReducerTest {

    private final EmotionReducer reducer = new EmotionReducer();

    @Test
    void appliesDeltaToState() {
        AgentState s = new AgentState();
        s.setHurt(0.2);
        s.setAnger(0.1);
        s.setWarmth(0.3);

        reducer.apply(s, EmotionDelta.of(0.3, 0.2, 0.4, -0.1, 0.5));

        assertEquals(0.4, s.getAnger(), 1e-9);    // 0.1 + 0.3
        assertEquals(0.4, s.getHurt(), 1e-9);     // 0.2 + 0.2
        assertEquals(0.4, s.getSadness(), 1e-9);  // 0 + 0.4
        assertEquals(0.2, s.getWarmth(), 1e-9);   // 0.3 - 0.1
        assertEquals(0.5, s.getAnxiety(), 1e-9);  // 0 + 0.5
    }

    @Test
    void clampsToBounds() {
        AgentState s = new AgentState();
        reducer.apply(s, EmotionDelta.of(5, -3, 2, 2, -5));
        assertEquals(1.0, s.getAnger(), 1e-9);
        assertEquals(0.0, s.getHurt(), 1e-9);
        assertEquals(1.0, s.getWarmth(), 1e-9);
        assertEquals(0.0, s.getAnxiety(), 1e-9);
    }

    @Test
    void emptyDeltaIsNoOp() {
        AgentState s = new AgentState();
        s.setMood("平静的");
        s.setStress(0.2);
        reducer.apply(s, EmotionDelta.NEUTRAL);
        assertEquals(0.2, s.getStress(), 1e-9);
        assertEquals(0.0, s.getAnger(), 1e-9);
    }

    @Test
    void negativeEmotionRaisesStress() {
        AgentState s = new AgentState();
        s.setStress(0.2);
        reducer.apply(s, EmotionDelta.of(0.5, 0, 0, 0, 0));
        assertEquals(0.2 + 0.15, s.getStress(), 1e-9);
    }

    @Test
    void warmDeltaLowersStressAndSetsMood() {
        AgentState s = new AgentState();
        s.setStress(0.5);
        reducer.apply(s, EmotionDelta.of(0, 0, 0, 0.6, 0));
        assertEquals(0.5 - 0.09, s.getStress(), 1e-9);
        assertEquals("平静的", s.getMood());
    }

    @Test
    void dominantMoodReflectsStrongestEmotion() {
        AgentState s = new AgentState();
        reducer.apply(s, EmotionDelta.of(0.1, 0.5, 0.1, 0, 0.1));
        assertEquals("有点受伤", s.getMood());
    }
}
