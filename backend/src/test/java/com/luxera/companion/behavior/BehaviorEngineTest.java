package com.luxera.companion.behavior;

import com.luxera.companion.conversation.Conversation;
import com.luxera.companion.conversation.ConversationRepository;
import com.luxera.companion.conversation.Message;
import com.luxera.companion.conversation.MessageRepository;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.relationship.RelationshipRepository;
import com.luxera.companion.relationship.RelationshipTypes;
import com.luxera.companion.sleep.SleepModel;
import com.luxera.companion.state.AgentState;
import com.luxera.companion.state.AgentStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §三十四~§四十一 BehaviorEngine 中央行为选择器测试:
 * 1. 行为评估总是产出一个候选(绝不崩)
 * 2. 主动联系用户只是候选之一 —— 沉默 + 联系压力高时它会出现, 但不是必然每次
 * 3. 睡眠决策考虑社交参与(深夜陪你聊 → 硬撑不睡)
 * 4. 随机性: 多次评估结果有分布(不是固定答案)
 */
@ActiveProfiles("test")
@SpringBootTest
class BehaviorEngineTest {

    @Autowired
    BehaviorEngine behaviorEngine;
    @Autowired
    CompanionRepository companionRepository;
    @Autowired
    RelationshipRepository relationshipRepository;
    @Autowired
    AgentStateRepository agentStateRepository;
    @Autowired
    MessageRepository messageRepository;
    @Autowired
    ConversationRepository conversationRepository;
    @Autowired
    SleepModel sleepModel;
    @Autowired
    com.luxera.companion.sleep.CircadianStateRepository circadianRepository;

    private String companionId;
    private String userId = "be-user";

    @BeforeEach
    void setUp() {
        companionId = UUID.randomUUID().toString();
        Companion c = new Companion();
        c.setId(companionId);
        c.setUserId(userId);
        c.setName("林夏");
        c.setGender("female");
        companionRepository.save(c);

        AgentState st = new AgentState();
        st.setCompanionId(companionId);
        agentStateRepository.save(st);

        Conversation conv = new Conversation();
        conv.setId(UUID.randomUUID().toString());
        conv.setUserId(userId);
        conv.setCompanionId(companionId);
        conv.setTitle("测试");
        conversationRepository.save(conv);

        // 固定 circadian 为"醒着 + 低睡眠压力", 让测试不受运行时刻影响(深夜跑也不会被初始化成睡着)
        var circ = sleepModel.getOrCreate(companionId, LocalDateTime.now());
        circ.setSleeping(false);
        circ.setSleepPressure(0.3);
        circ.setSleepStartedAt(null);
        circadianRepository.save(circ);
    }

    private Relationship relationship(boolean intimate, double lastInteractionHoursAgo) {
        Relationship r = new Relationship();
        r.setUserId(userId);
        r.setCompanionId(companionId);
        r.setUserPersonId(userId);
        r.setAgentPersonId(companionId);
        RelationshipTypes.applyInitial(r, intimate ? RelationshipTypes.LOVER : RelationshipTypes.FRIEND);
        r.setLastInteractionAt(LocalDateTime.now().minusHours((long) lastInteractionHoursAgo));
        r.setConnectionPressure(intimate ? 0.85 : 0.2);
        return relationshipRepository.save(r);
    }

    @Test
    void evaluateAlwaysProducesDecision() {
        relationship(false, 3);
        BehaviorOutcome outcome = behaviorEngine.evaluate(companionId, LocalDateTime.now(), "TEST");
        assertNotNull(outcome);
        assertNotNull(outcome.action());
        assertTrue(outcome.decidedAt() != null);
    }

    @Test
    void proactiveIsOneCandidateNotCertainty() {
        // 亲密关系 + 长时间沉默(>2天) + 高联系压力 → 主动联系候选得分高
        relationship(true, 60);
        Set<BehaviorAction> seen = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            BehaviorOutcome o = behaviorEngine.evaluate(companionId, LocalDateTime.now(), "TEST");
            seen.add(o.action());
        }
        // 主动联系出现过(不是每次都发, 也不是从不发)
        assertTrue(seen.contains(BehaviorAction.SEND_PROACTIVE_MESSAGE),
                "沉默+高压力下应有机会主动联系, 实际: " + seen);
        // 随机性: 不应 40 次全是一个动作(除非默认候选极强)
        assertTrue(seen.size() >= 2, "行为选择应有分布, 实际: " + seen);
    }

    @Test
    void sleepDecisionRespectsEngagement() {
        relationship(true, 1);
        // 用户 10 分钟前发过消息 → 正在聊 → 不应被 tick 强制入睡
        Conversation conv = conversationRepository.findByUserIdAndCompanionIdOrderByLastMessageAtDesc(userId, companionId).get(0);
        Message m = new Message();
        m.setConversationId(conv.getId());
        m.setSenderType("user");
        m.setContent("睡了吗?");
        m.setDeliveryStatus(com.luxera.companion.runtime.pipeline.MessageLifecycle.DELIVERED);
        messageRepository.save(m);

        sleepModel.getOrCreate(companionId, LocalDateTime.now());
        SleepModel.SleepDecision decision = behaviorEngine.sleepDecision(companionId, LocalDateTime.now());
        assertNotEquals(SleepModel.SleepDecision.SLEEP, decision,
                "正在陪你聊 → 不应入睡(硬撑或延迟)");
    }
}
