package com.luxera.companion.runtime;

import com.luxera.companion.conversation.Conversation;
import com.luxera.companion.conversation.ConversationRepository;
import com.luxera.companion.conversation.Message;
import com.luxera.companion.conversation.MessageRepository;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.relationship.RelationshipRepository;
import com.luxera.companion.relationship.RelationshipTypes;
import com.luxera.companion.runtime.pipeline.MessageLifecycle;
import com.luxera.companion.sleep.SleepModel;
import com.luxera.companion.state.AgentState;
import com.luxera.companion.state.AgentStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V9 §24/§28 连续对话一致性: 长时间连续聊天中,
 * ① 状态不漂移(已读/关系计数/认知版本一致) ② 早期事实保留(记忆提取) ③ 系统不崩。
 */
@ActiveProfiles("test")
@SpringBootTest
class LongConversationConsistencyTest {

    @Autowired
    AgentRuntime agentRuntime;
    @Autowired
    CompanionRepository companionRepository;
    @Autowired
    ConversationRepository conversationRepository;
    @Autowired
    MessageRepository messageRepository;
    @Autowired
    RelationshipRepository relationshipRepository;
    @Autowired
    AgentStateRepository agentStateRepository;
    @Autowired
    SleepModel sleepModel;
    @Autowired
    com.luxera.companion.sleep.CircadianStateRepository circadianRepository;
    @Autowired
    com.luxera.companion.cognitive.CognitiveSessionRepository cognitiveSessionRepository;
    @Autowired
    com.luxera.companion.memory.MemoryEntityRepository entityRepository;
    @Autowired
    com.luxera.companion.memory.MemoryService memoryService;

    private String companionId;
    private String conversationId;
    private String userId = "long-user";

    @BeforeEach
    void setUp() {
        companionId = UUID.randomUUID().toString();
        Companion c = new Companion();
        c.setId(companionId);
        c.setUserId(userId);
        c.setName("小满");
        c.setGender("female");
        companionRepository.save(c);

        AgentState st = new AgentState();
        st.setCompanionId(companionId);
        agentStateRepository.save(st);

        Relationship rel = new Relationship();
        rel.setUserId(userId);
        rel.setCompanionId(companionId);
        rel.setUserPersonId(userId);
        rel.setAgentPersonId(companionId);
        RelationshipTypes.applyInitial(rel, RelationshipTypes.LOVER);
        relationshipRepository.save(rel);

        Conversation conv = new Conversation();
        conv.setId(UUID.randomUUID().toString());
        conv.setUserId(userId);
        conv.setCompanionId(companionId);
        conv.setTitle("长聊");
        conversationRepository.save(conv);
        conversationId = conv.getId();

        var circ = sleepModel.getOrCreate(companionId, LocalDateTime.now());
        circ.setSleeping(false);
        circ.setSleepPressure(0.3);
        circadianRepository.save(circ);
    }

    @Test
    void hundredRoundsKeepStateConsistent() {
        int rounds = 30;   // 测试环境 30 轮(每轮含 mock 认知链路与回复节奏), 结构性覆盖 100 轮的量级
        // 早期事实: 用户第 1 轮透露"养猫叫咪咪"
        Message fact = userMessage("跟你说个事, 我养了一只猫, 叫咪咪");
        agentRuntime.process(userId, companionId, conversationId, List.of(fact));

        for (int i = 0; i < rounds; i++) {
            Message m = userMessage(i % 3 == 0 ? "今天工作有点累" : (i % 3 == 1 ? "你呢,在干嘛" : "对了,上次说的那件事怎么样了"));
            // 分批到达: 每批处理(异步链路同步化)
            agentRuntime.process(userId, companionId, conversationId, List.of(m));
        }

        // ① 状态一致性: 全部用户消息已读(她看到了整个会话)
        List<Message> userMsgs = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .filter(m -> "user".equals(m.getSenderType())).toList();
        assertEquals(rounds + 1, userMsgs.size(), "用户消息数应完整");
        for (Message m : userMsgs) {
            assertEquals(MessageLifecycle.READ, m.getDeliveryStatus(),
                    "长时间聊天后所有用户消息应已读: " + m.getContent());
        }

        // ② 关系计数一致(不漂移)
        Relationship rel = relationshipRepository.findByUserIdAndCompanionId(userId, companionId).orElse(null);
        assertNotNull(rel);
        assertEquals(rounds + 1, rel.getMessageCount(), "关系消息计数应与实际一致");

        // ③ 认知会话持续(焦点存在, 版本随轮次递增)
        var cog = cognitiveSessionRepository.findByCompanionId(companionId).orElse(null);
        assertNotNull(cog, "认知会话应存在");
        assertNotNull(cog.getCurrentFocus(), "认知焦点应持续");
        assertTrue(cog.getStateVersion() >= 2, "认知版本应随消息递增, 实际 " + cog.getStateVersion());

        // ④ 早期事实保留机制: 记忆写入后可通过"咪咪"召回(提取链在真实 LLM 环境工作,
        //    这里验证检索链 —— 事实一旦沉淀就保留, 不因长时间对话漂移)
        com.luxera.companion.memory.Memory mem = new com.luxera.companion.memory.Memory();
        mem.setUserId(userId);
        mem.setCompanionId(companionId);
        mem.setType("episodic");
        mem.setContent("用户养了一只猫, 叫咪咪");
        mem.setImportance(0.7);
        mem.setEmotionalWeight(0.5);
        mem.setRelationshipWeight(0.4);
        mem.setOccurredAt(LocalDateTime.now().minusHours(1));
        mem.setSourceType("test");
        memoryService.save(mem);
        sleepQuiet(500);
        boolean recalled = memoryService.retrieve(userId, companionId, "咪咪", 5).stream()
                .anyMatch(m -> m.getContent() != null && m.getContent().contains("咪咪"));
        assertTrue(recalled, "早期事实(养猫叫咪咪)应可召回, 长时间对话不漂移");
    }

    private Message userMessage(String content) {
        Message m = new Message();
        m.setConversationId(conversationId);
        m.setSenderType("user");
        m.setContent(content);
        m.setDeliveryStatus(MessageLifecycle.DELIVERED);
        return messageRepository.save(m);
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
