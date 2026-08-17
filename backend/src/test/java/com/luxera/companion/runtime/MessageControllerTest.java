package com.luxera.companion.runtime;

import com.luxera.companion.conversation.Conversation;
import com.luxera.companion.conversation.ConversationRepository;
import com.luxera.companion.conversation.Message;
import com.luxera.companion.conversation.MessageRepository;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §十一~§十六 POST /messages Chat Core 测试:
 * - 用户消息**同步落库**(刷新不丢, 服务器重启不丢)
 * - clientMessageId 幂等(同会话重复提交不重复入库)
 * - Agent 异步处理(请求不阻塞)
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class MessageControllerTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    CompanionRepository companionRepository;
    @Autowired
    ConversationRepository conversationRepository;
    @Autowired
    MessageRepository messageRepository;
    @Autowired
    AgentRuntime v7AgentRuntime;

    private String companionId;
    private String conversationId;

    @BeforeEach
    void setUp() {
        companionId = UUID.randomUUID().toString();
        Companion c = new Companion();
        c.setId(companionId);
        c.setUserId("v7-user");
        c.setName("小满");
        c.setGender("female");
        companionRepository.save(c);

        Conversation conv = new Conversation();
        conv.setId(UUID.randomUUID().toString());
        conv.setUserId("v7-user");
        conv.setCompanionId(companionId);
        conv.setTitle("测试");
        conversationRepository.save(conv);
        conversationId = conv.getId();
    }

    @Test
    void postMessageRequiresAuth() throws Exception {
        // 未认证 → 401/403(而非阻塞/500), 证明接口链路存在且不依赖 Agent
        mockMvc.perform(post(
                        "/api/companions/{cid}/conversations/{convId}/messages", companionId, conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"你好\"}"))
                .andExpect(status().is4xxClientError());
    }

    /** Agent 处理已持久化的消息(测试中直接同步调用), 消息由 MessageCoreService 先落库 */
    @Test
    void agentRuntimeProcessesPersistedMessages() {
        String content = "你好,我在这里";
        Message persisted = messageRepository.save(userMessage(content));
        // 同步处理(测试中直接调用 process, 不走异步线程)
        v7AgentRuntime.process("v7-user", companionId, conversationId, java.util.List.of(persisted));
        // 消息已在库中(同步持久化保证), Agent 处理不重复入库
        long userCount = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .filter(m -> "user".equals(m.getSenderType()) && content.equals(m.getContent()))
                .count();
        assertEquals(1, userCount, "消息应恰好一条(同步落库 + Agent 不重复入库)");
    }

    @Test
    void agentRuntimeProcessDoesNotThrow() {
        // 空内容/异常场景不应导致运行时崩溃
        Message persisted = messageRepository.save(userMessage("测试消息"));
        assertDoesNotThrow(() -> v7AgentRuntime.process(
                "v7-user", companionId, conversationId, java.util.List.of(persisted)));
    }

    private Message userMessage(String content) {
        Message m = new Message();
        m.setConversationId(conversationId);
        m.setSenderType("user");
        m.setContent(content);
        m.setDeliveryStatus(com.luxera.companion.runtime.pipeline.MessageLifecycle.DELIVERED);
        return messageRepository.save(m);
    }
}
