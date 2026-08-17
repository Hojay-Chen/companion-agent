package com.luxera.companion.runtime;

import com.luxera.companion.conversation.Conversation;
import com.luxera.companion.conversation.ConversationRepository;
import com.luxera.companion.conversation.Message;
import com.luxera.companion.conversation.MessageRepository;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
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
 * 整个会话一并已读: 用户连发多条消息(分批到达), Agent 处理最后一批时,
 * 该会话全部未读用户消息应一起变已读(真人拿起手机看到的是整个会话)。
 */
@ActiveProfiles("test")
@SpringBootTest
class AgentReadAllTest {

    @Autowired
    AgentRuntime agentRuntime;
    @Autowired
    CompanionRepository companionRepository;
    @Autowired
    ConversationRepository conversationRepository;
    @Autowired
    MessageRepository messageRepository;
    @Autowired
    AgentStateRepository agentStateRepository;
    @Autowired
    SleepModel sleepModel;
    @Autowired
    com.luxera.companion.sleep.CircadianStateRepository circadianRepository;

    private String companionId;
    private String conversationId;
    private String userId = "readall-user";

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

        Conversation conv = new Conversation();
        conv.setId(UUID.randomUUID().toString());
        conv.setUserId(userId);
        conv.setCompanionId(companionId);
        conv.setTitle("测试");
        conversationRepository.save(conv);
        conversationId = conv.getId();

        // 固定醒着, 不受运行时刻影响
        var circ = sleepModel.getOrCreate(companionId, LocalDateTime.now());
        circ.setSleeping(false);
        circ.setSleepPressure(0.3);
        circadianRepository.save(circ);

        // 建关系(亲密) → 消息才会被认真对待(真实用户创建伴侣时必有关系)
        com.luxera.companion.relationship.Relationship rel = new com.luxera.companion.relationship.Relationship();
        rel.setUserId(userId);
        rel.setCompanionId(companionId);
        rel.setUserPersonId(userId);
        rel.setAgentPersonId(companionId);
        com.luxera.companion.relationship.RelationshipTypes.applyInitial(rel, com.luxera.companion.relationship.RelationshipTypes.LOVER);
        relationshipRepository.save(rel);
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.luxera.companion.relationship.RelationshipRepository relationshipRepository;

    private Message userMessage(String content) {
        Message m = new Message();
        m.setConversationId(conversationId);
        m.setSenderType("user");
        m.setContent(content);
        m.setDeliveryStatus(MessageLifecycle.DELIVERED);
        return messageRepository.save(m);
    }

    @Test
    void processingLastBatchMarksEntireConversationRead() {
        // 用户连发两条(分批到达): 第一条先到(未读), 第二条稍后到
        Message first = userMessage("在吗?");
        Message second = userMessage("怎么一直不回我");

        // Agent 处理第二批(模拟异步分批处理)
        agentRuntime.process(userId, companionId, conversationId, List.of(second));

        // 整个会话全部已读(不是只读最新一条); companion 的回复消息不参与已读
        List<Message> all = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        List<Message> userMsgs = all.stream().filter(m -> "user".equals(m.getSenderType())).toList();
        assertTrue(userMsgs.size() >= 2, "应至少有两条用户消息");
        for (Message m : userMsgs) {
            assertEquals("READ", m.getDeliveryStatus(),
                    "会话内所有用户消息应一并已读: " + m.getContent() + " 状态=" + m.getDeliveryStatus());
        }
    }

    @Test
    void deferredStillMarksEntireConversationRead() {
        // 她看到了但决定稍后回(DEFER) → 全部消息也已读
        Message first = userMessage("我最近被裁员了,心里很难受");
        Message second = userMessage("想跟你说说");

        agentRuntime.process(userId, companionId, conversationId, List.of(first, second));

        List<Message> all = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        List<Message> userMsgs = all.stream().filter(m -> "user".equals(m.getSenderType())).toList();
        assertTrue(userMsgs.size() >= 2, "应至少有两条用户消息");
        for (Message m : userMsgs) {
            assertEquals("READ", m.getDeliveryStatus(),
                    "DEFER 也应全部已读: " + m.getContent() + " 状态=" + m.getDeliveryStatus());
        }
    }
}
